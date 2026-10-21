/**
 * GDPR deletion (docs/BRIEF.md §4.12 and §6; docs/DECISIONS.md "Radering av familj och konto"). Two callables, both
 * idempotent, called from the app's Integritet screen:
 *
 * - `deleteFamily({familyId})`: a parent deletes the family for everyone in it.
 * - `deleteAccount({familyId?})`: anyone deletes their own account. `familyId` is the family on the calling phone, a hint
 *   in case `users/{uid}.familyIds` missed it; membership is always checked against the access index.
 *
 * Deleted: the family document with every subcollection, the top-level documents that point at it (invites, calendar
 * feeds, handover links) and every account's link to it (`users/{uid}.familyIds`). Kept: other people's Firebase accounts,
 * because they may belong to other families or use the app on their own. A tombstone (`deletedFamilies/{id}`, only a
 * timestamp) makes the security rules refuse to create the family again, so a phone that still has it offline cannot
 * bring it back.
 *
 * Logs carry ids and counts only, never names, e-mails or anything a person typed.
 */
import * as logger from "firebase-functions/logger";
import { type CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import {
  FieldValue,
  type DocumentReference,
  type Firestore,
  type Query,
  type QueryDocumentSnapshot,
  getFirestore,
} from "firebase-admin/firestore";
import { REGION } from "./setup.js";

/** Top-level collections whose documents carry a `familyId` (firestore.rules). */
const FAMILY_LINKED = ["invites", "calendarFeeds", "handoverShares"] as const;

/** Firestore document ids the app creates (UUIDs); anything else is refused before a path is built from it. */
const FAMILY_ID = /^[A-Za-z0-9_-]{1,128}$/;

/** gRPC status codes that mean "already done" for an idempotent step. */
const ALREADY_EXISTS = 6;
const NOT_FOUND = 5;

const OPTIONS = {
  region: REGION,
  timeoutSeconds: 300,
  memory: "512MiB" as const,
  // The app does not send App Check tokens yet. Turn this on together with Firestore enforcement (docs/SETUP.md, App Check).
  enforceAppCheck: false,
};

/** Error messages are stable reason codes the app maps to its own text (DeletionCalls.refusalFor). */
export const Reasons = {
  notSignedIn: "not-signed-in",
  invalidFamilyId: "invalid-family-id",
  notAMember: "not-a-member",
  parentsOnly: "parents-only",
  lastParent: "last-parent",
} as const;

interface PurgeCounts {
  accounts: number;
  invites: number;
  calendarFeeds: number;
  handoverShares: number;
}

type Plan =
  | { kind: "leave"; familyId: string; memberId: string | undefined }
  | { kind: "delete-family"; familyId: string }
  | { kind: "last-parent"; familyId: string }
  | { kind: "not-member"; familyId: string };

export const deleteFamily = onCall(OPTIONS, async (request) => {
  const uid = callerUid(request);
  const familyId = familyIdFrom(request.data, true);
  if (familyId === undefined) throw new HttpsError("invalid-argument", Reasons.invalidFamilyId);
  const db = getFirestore();
  const [access, tombstone] = await db.getAll(db.doc(`families/${familyId}/access/${uid}`), tombstoneRef(db, familyId));
  if (tombstone.exists) {
    // A parent already asked for this. Finish whatever an interrupted run left behind and say it is done.
    const counts = await purgeFamily(db, familyId);
    logger.info("deleteFamily: already deleted", { familyId, ...counts });
    return { status: "already-deleted" };
  }
  if (!access.exists) throw new HttpsError("permission-denied", Reasons.notAMember);
  if (access.get("role") !== "parent") throw new HttpsError("permission-denied", Reasons.parentsOnly);
  const counts = await purgeFamily(db, familyId);
  logger.info("deleteFamily", { familyId, uid, ...counts });
  return { status: "deleted" };
});

export const deleteAccount = onCall(OPTIONS, async (request) => {
  const uid = callerUid(request);
  const hint = familyIdFrom(request.data, false);
  const db = getFirestore();
  const user = db.doc(`users/${uid}`);
  const stored = (await user.get()).get("familyIds");
  const familyIds = new Set<string>(Array.isArray(stored) ? stored.filter(isFamilyId) : []);
  if (hint !== undefined) familyIds.add(hint);

  // Look at every family first and change nothing if one of them refuses.
  const plans = await Promise.all([...familyIds].map(async (fid) => planFor(uid, fid, await accessDocs(db, fid))));
  const blocked = plans.find((p) => p.kind === "last-parent");
  if (blocked !== undefined) {
    logger.warn("deleteAccount refused", { uid, familyId: blocked.familyId, reason: Reasons.lastParent });
    throw lastParent(blocked.familyId);
  }

  let familiesDeleted = 0;
  let familiesLeft = 0;
  let calendarFeeds = 0;
  for (const plan of plans) {
    if (plan.kind === "not-member") continue;
    // Planned again inside a transaction: someone may have joined or left since.
    const outcome = await leaveFamily(db, uid, plan.familyId);
    if (outcome === "alone") {
      await purgeFamily(db, plan.familyId);
      familiesDeleted++;
    } else if (outcome === "left") {
      calendarFeeds += await deleteMyCalendarFeeds(db, uid, plan.familyId);
      familiesLeft++;
    }
  }

  // users/{uid} with its devices (FCM tokens), then the Firebase account itself.
  await db.recursiveDelete(user);
  await getAuth()
    .deleteUser(uid)
    .catch((e: unknown) => {
      if ((e as { code?: unknown }).code !== "auth/user-not-found") throw e;
    });
  logger.info("deleteAccount", { uid, familiesDeleted, familiesLeft, calendarFeeds });
  return { status: "deleted", familiesDeleted, familiesLeft };
});

function callerUid(request: CallableRequest<unknown>): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", Reasons.notSignedIn);
  return uid;
}

function isFamilyId(value: unknown): value is string {
  return typeof value === "string" && FAMILY_ID.test(value);
}

function familyIdFrom(data: unknown, required: boolean): string | undefined {
  const value = typeof data === "object" && data !== null ? (data as { familyId?: unknown }).familyId : undefined;
  if (value === undefined || value === null) {
    if (required) throw new HttpsError("invalid-argument", Reasons.invalidFamilyId);
    return undefined;
  }
  if (!isFamilyId(value)) throw new HttpsError("invalid-argument", Reasons.invalidFamilyId);
  return value;
}

function lastParent(familyId: string): HttpsError {
  return new HttpsError("failed-precondition", Reasons.lastParent, { reason: Reasons.lastParent, familyId });
}

function tombstoneRef(db: Firestore, familyId: string): DocumentReference {
  return db.doc(`deletedFamilies/${familyId}`);
}

async function accessDocs(db: Firestore, familyId: string): Promise<QueryDocumentSnapshot[]> {
  return (await db.collection(`families/${familyId}/access`).get()).docs;
}

/** What deleting [uid]'s account means for one family, from its access index (doc id = uid). */
function planFor(uid: string, familyId: string, access: QueryDocumentSnapshot[]): Plan {
  const mine = access.find((d) => d.id === uid);
  if (mine === undefined) return { kind: "not-member", familyId };
  const others = access.filter((d) => d.id !== uid);
  if (others.length === 0) return { kind: "delete-family", familyId };
  if (mine.get("role") === "parent" && !others.some((d) => d.get("role") === "parent")) return { kind: "last-parent", familyId };
  const memberId = mine.get("memberId");
  return { kind: "leave", familyId, memberId: typeof memberId === "string" ? memberId : undefined };
}

/**
 * Removes [uid] from one family in a transaction, so two parents leaving at the same time cannot leave the family without
 * a parent. The member stays as a person without an account ("Johan" keeps his events), only `uid` is removed.
 */
async function leaveFamily(db: Firestore, uid: string, familyId: string): Promise<"left" | "alone" | "not-member"> {
  return db.runTransaction(async (tx) => {
    const access = (await tx.get(db.collection(`families/${familyId}/access`))).docs;
    const plan = planFor(uid, familyId, access);
    if (plan.kind === "last-parent") throw lastParent(familyId);
    if (plan.kind === "not-member") return "not-member";
    if (plan.kind === "delete-family") return "alone";
    if (plan.memberId !== undefined) {
      const member = db.doc(`families/${familyId}/members/${plan.memberId}`);
      const snap = await tx.get(member);
      if (snap.exists && snap.get("uid") === uid) {
        // Stamped like a client write, so the other phones' incremental listeners (updatedAt > watermark) see it.
        tx.update(member, { uid: FieldValue.delete(), updatedAt: FieldValue.serverTimestamp(), updatedBy: uid });
      }
    }
    tx.delete(db.doc(`families/${familyId}/access/${uid}`));
    return "left";
  });
}

/** A personal calendar feed link would keep serving the family's calendar after its owner left. */
async function deleteMyCalendarFeeds(db: Firestore, uid: string, familyId: string): Promise<number> {
  const feeds = await db.collection("calendarFeeds").where("familyId", "==", familyId).get();
  const mine = feeds.docs.filter((d) => d.get("createdBy") === uid);
  await Promise.all(mine.map((d) => d.ref.delete()));
  return mine.length;
}

/**
 * Deletes a family for everyone. Safe to run again: every step skips what is already gone. The order matters:
 * 1. the tombstone, so the rules refuse to create the family again from now on;
 * 2. every account's link to it, while the access index still says who they are;
 * 3. the top-level documents that point at it;
 * 4. the access index, so no phone can write any more;
 * 5. the family document and everything below it (also catches a write that was already on its way).
 */
async function purgeFamily(db: Firestore, familyId: string): Promise<PurgeCounts> {
  const family = db.doc(`families/${familyId}`);
  await tombstoneRef(db, familyId)
    .create({ deletedAt: FieldValue.serverTimestamp() })
    .catch((e: unknown) => {
      if ((e as { code?: unknown }).code !== ALREADY_EXISTS) throw e;
    });
  const access = await accessDocs(db, familyId);
  await Promise.all(access.map((d) => unlinkUser(db, d.id, familyId)));
  const counts: PurgeCounts = { accounts: access.length, invites: 0, calendarFeeds: 0, handoverShares: 0 };
  for (const name of FAMILY_LINKED) {
    counts[name] = await deleteAll(db.collection(name).where("familyId", "==", familyId));
  }
  await Promise.all(access.map((d) => d.ref.delete()));
  await db.recursiveDelete(family);
  return counts;
}

/** Removes [familyId] from `users/{uid}.familyIds` without creating a profile for someone who never had one. */
async function unlinkUser(db: Firestore, uid: string, familyId: string): Promise<void> {
  await db
    .doc(`users/${uid}`)
    .update({ familyIds: FieldValue.arrayRemove(familyId) })
    .catch((e: unknown) => {
      if ((e as { code?: unknown }).code !== NOT_FOUND) throw e;
    });
}

async function deleteAll(query: Query): Promise<number> {
  const snap = await query.get();
  await Promise.all(snap.docs.map((d) => d.ref.delete()));
  return snap.size;
}
