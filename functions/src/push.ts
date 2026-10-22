/**
 * Push between phones with Firebase Cloud Messaging (BRIEF §4.10, docs/DECISIONS.md ADR-042). Three Firestore triggers:
 * - a new hand-off request (ADR-030) to the one asked, or to everyone but the sender for a request to anyone;
 * - an answer to it (a yes, or a no) to the one who asked;
 * - an item added to a list while someone else is shopping from it (ADR-018) to that shopper;
 * - a child reported sick, or who stays home decided (ADR-047), to everyone else in the family, caregivers too (their
 *   pick-up may be off).
 *
 * Messages are data-only and carry ids and the least needed to say it ("pickup", a date, the item as written): the app words
 * the notification from its own string resources, in the reader's language, and opens what it is about. Tokens live in
 * `users/{uid}/devices/{deviceId}` (written by the app, firestore.rules); a token FCM no longer knows is removed. In the
 * emulator nothing is sent (there is no FCM emulator): the messages are written to `_pushOutbox` for the tests. Logs carry
 * the kind and counts only.
 */
import * as logger from "firebase-functions/logger";
import { defineString } from "firebase-functions/params";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { FieldValue, type Firestore, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { REGION } from "./setup.js";

/**
 * Firestore triggers run where the database is: europe-north1 by default; for the `eur3` multi-region set
 * TRIGGER_REGION=europe-west1 in functions/.env (docs/SETUP.md 2.4).
 */
const TRIGGER_REGION = defineString("TRIGGER_REGION", { default: REGION });

/** Who gets a message and what it says, as data. */
export interface Push {
  memberIds: string[];
  data: Record<string, string>;
}

interface RequestDoc {
  eventId?: string;
  occurrenceDate?: string;
  kind?: string;
  fromMemberId?: string;
  toMemberId?: string | null;
  status?: string;
  respondedBy?: string | null;
  declines?: Record<string, unknown>;
  deletedAt?: unknown;
}

interface ItemDoc {
  listId?: string;
  name?: string;
  addedBy?: string | null;
  checked?: boolean;
  deletedAt?: unknown;
}

interface SickDoc {
  childId?: string;
  date?: string;
  reportedBy?: string | null;
  homeWith?: string | null;
  updatedBy?: string;
  deletedAt?: unknown;
}

/** A member and the account behind it, so the one who wrote a change is not told about it. */
export interface MemberRef {
  id: string;
  uid?: string;
}

export interface PresenceDoc {
  memberId: string;
  listId: string;
  until: Date;
}

const ANSWERED = new Set(["accepted", "declined"]);

function requestData(type: string, familyId: string, requestId: string, r: RequestDoc): Record<string, string> {
  return {
    type,
    familyId,
    requestId,
    eventId: r.eventId ?? "",
    date: r.occurrenceDate ?? "",
    kind: r.kind ?? "pickup",
  };
}

/** A new request: to the one asked, or to everyone in [members] but the sender. */
export function requestCreated(familyId: string, requestId: string, r: RequestDoc, members: string[]): Push | null {
  if (r.deletedAt || (r.status ?? "pending") !== "pending" || !r.fromMemberId) return null;
  const to = r.toMemberId ? [r.toMemberId] : members;
  const memberIds = to.filter((id) => id !== r.fromMemberId);
  if (memberIds.length === 0) return null;
  return { memberIds, data: { ...requestData("request", familyId, requestId, r), from: r.fromMemberId } };
}

/** An answer: a yes or a no on the request, or a new "no" from one of several asked. Goes to the one who asked. */
export function requestAnswered(familyId: string, requestId: string, before: RequestDoc, after: RequestDoc): Push | null {
  if (after.deletedAt || !after.fromMemberId) return null;
  let by: string | null = null;
  let status: string | null = null;
  if (after.status !== before.status && ANSWERED.has(after.status ?? "")) {
    by = after.respondedBy ?? null;
    status = after.status ?? null;
  } else {
    const fresh = Object.keys(after.declines ?? {}).filter((id) => !(id in (before.declines ?? {})));
    if (fresh.length > 0) {
      by = fresh[0];
      status = "declined";
    }
  }
  if (!status || by === after.fromMemberId) return null;
  return {
    memberIds: [after.fromMemberId],
    data: { ...requestData("answer", familyId, requestId, after), status, by: by ?? "" },
  };
}

/**
 * A sick day written: a new one ("Ella är sjuk idag") or who stays home changed ("Johan stannar hemma med Ella"). To every
 * member but the one who wrote it; a removed one ("Ångra") and other changes (the reports ticked) say nothing.
 */
export function sickChanged(familyId: string, sickDayId: string, before: SickDoc | undefined, after: SickDoc, members: MemberRef[]): Push | null {
  if (after.deletedAt || !after.childId || !after.date) return null;
  const created = !before || !!before.deletedAt;
  const homeChanged = !created && !!after.homeWith && after.homeWith !== before?.homeWith;
  if (!created && !homeChanged) return null;
  const memberIds = members
    .filter((m) => m.uid !== after.updatedBy && !(created && m.id === after.reportedBy))
    .map((m) => m.id);
  if (memberIds.length === 0) return null;
  return {
    memberIds,
    data: {
      type: "sick",
      familyId,
      sickDayId,
      childId: after.childId,
      date: after.date,
      by: after.reportedBy ?? "",
      homeWith: after.homeWith ?? "",
    },
  };
}

/** An item added to a list: to whoever else is shopping from that list right now. */
export function itemAdded(familyId: string, itemId: string, item: ItemDoc, presences: PresenceDoc[], now: Date): Push | null {
  if (item.deletedAt || item.checked || !item.listId) return null;
  const memberIds = [
    ...new Set(
      presences
        .filter((p) => p.listId === item.listId && p.until > now && p.memberId !== item.addedBy)
        .map((p) => p.memberId),
    ),
  ];
  if (memberIds.length === 0) return null;
  return {
    memberIds,
    data: { type: "shopping", familyId, listId: item.listId, itemId, by: item.addedBy ?? "", item: (item.name ?? "").slice(0, 100) },
  };
}

const GONE = new Set(["messaging/registration-token-not-registered", "messaging/invalid-registration-token"]);

/** Sends [push] to every device of its members (in the emulator: writes it to `_pushOutbox`). */
export async function deliver(db: Firestore, familyId: string, push: Push): Promise<void> {
  const members = await Promise.all(push.memberIds.map((id) => db.doc(`families/${familyId}/members/${id}`).get()));
  const uids = members.map((m) => m.get("uid")).filter((uid): uid is string => typeof uid === "string" && uid.length > 0);
  const devices = (await Promise.all(uids.map((uid) => db.collection(`users/${uid}/devices`).get()))).flatMap((s) => s.docs);
  const targets = devices
    .map((d) => ({ ref: d.ref, token: d.get("fcmToken") }))
    .filter((t): t is { ref: typeof t.ref; token: string } => typeof t.token === "string" && t.token.length > 0);
  if (process.env.FUNCTIONS_EMULATOR === "true") {
    await db.collection("_pushOutbox").add({
      familyId,
      memberIds: push.memberIds,
      data: push.data,
      devices: targets.length,
      at: FieldValue.serverTimestamp(),
    });
    return;
  }
  if (targets.length === 0) return;
  const result = await getMessaging().sendEachForMulticast({
    tokens: targets.map((t) => t.token),
    data: push.data,
    android: { priority: "high" },
  });
  const gone = result.responses.flatMap((r, i) => (!r.success && GONE.has(r.error?.code ?? "") ? [targets[i].ref] : []));
  await Promise.all(gone.map((ref) => ref.delete()));
  logger.info("Push sent", { type: push.data.type, devices: targets.length, failed: result.failureCount, removed: gone.length });
}

async function memberIds(db: Firestore, familyId: string): Promise<string[]> {
  return (await memberRefs(db, familyId)).map((m) => m.id);
}

async function memberRefs(db: Firestore, familyId: string): Promise<MemberRef[]> {
  const members = await db.collection(`families/${familyId}/members`).get();
  return members.docs.filter((m) => !m.get("deletedAt")).map((m) => ({ id: m.id, uid: m.get("uid") ?? undefined }));
}

export const pushOnRequest = onDocumentCreated(
  { document: "families/{familyId}/handoffRequests/{requestId}", region: TRIGGER_REGION },
  async (event) => {
    const r = event.data?.data() as RequestDoc | undefined;
    if (!r) return;
    const db = getFirestore();
    const { familyId, requestId } = event.params;
    const push = requestCreated(familyId, requestId, r, await memberIds(db, familyId));
    if (push) await deliver(db, familyId, push);
  },
);

export const pushOnAnswer = onDocumentUpdated(
  { document: "families/{familyId}/handoffRequests/{requestId}", region: TRIGGER_REGION },
  async (event) => {
    const before = event.data?.before.data() as RequestDoc | undefined;
    const after = event.data?.after.data() as RequestDoc | undefined;
    if (!before || !after) return;
    const push = requestAnswered(event.params.familyId, event.params.requestId, before, after);
    if (push) await deliver(getFirestore(), event.params.familyId, push);
  },
);

export const pushOnSickDay = onDocumentWritten(
  { document: "families/{familyId}/sickDays/{sickDayId}", region: TRIGGER_REGION },
  async (event) => {
    const before = event.data?.before.data() as SickDoc | undefined;
    const after = event.data?.after.data() as SickDoc | undefined;
    if (!after) return;
    const db = getFirestore();
    const { familyId, sickDayId } = event.params;
    const push = sickChanged(familyId, sickDayId, before, after, await memberRefs(db, familyId));
    if (push) await deliver(db, familyId, push);
  },
);

// Shopping items live under their list (firestore.rules, the app's Collections.ShoppingItems).
export const pushOnShoppingItem = onDocumentCreated(
  { document: "families/{familyId}/shoppingLists/{listId}/items/{itemId}", region: TRIGGER_REGION },
  async (event) => {
    const data = event.data?.data() as ItemDoc | undefined;
    if (!data) return;
    const item: ItemDoc = { ...data, listId: data.listId ?? event.params.listId };
    const db = getFirestore();
    const { familyId, itemId } = event.params;
    const presence = await db.collection(`families/${familyId}/presence`).get();
    const presences: PresenceDoc[] = presence.docs.flatMap((p) => {
      const until = untilOf(p.get("until"));
      const memberId = p.get("memberId") ?? p.id;
      const listId = p.get("listId");
      return until && typeof listId === "string" ? [{ memberId, listId, until }] : [];
    });
    const push = itemAdded(familyId, itemId, item, presences, new Date());
    if (push) await deliver(db, familyId, push);
  },
);

/** The app stores instants as ISO strings (kotlinx); a Firestore timestamp is read too. */
function untilOf(value: unknown): Date | null {
  if (typeof value === "string") {
    const t = new Date(value);
    return Number.isNaN(t.getTime()) ? null : t;
  }
  if (value && typeof (value as { toDate?: () => Date }).toDate === "function") return (value as { toDate: () => Date }).toDate();
  return null;
}
