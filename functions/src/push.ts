/**
 * Push to the family's phones when the app is closed: hand-off requests and answers, things added to the list while
 * someone is in the shop, and calendar changes made by someone else. Data-only messages: the app builds the text in
 * the user's language and applies quiet hours. Nothing about the family is logged.
 */
import { getMessaging } from "firebase-admin/messaging";
import { getFirestore, type DocumentData, type Firestore } from "firebase-admin/firestore";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";

export interface Push {
  uids: string[];
  data: Record<string, string>;
  /** Hand-off requests are time sensitive; the rest can wait for the phone's next wake-up. */
  urgent?: boolean;
}

export type Sender = (db: Firestore, push: Push) => Promise<void>;

/** Sends to every registered device of the users and forgets tokens FCM says are gone. */
export const fcmSender: Sender = async (db, push) => {
  const devices = (await Promise.all(push.uids.map((uid) => db.collection(`users/${uid}/devices`).get()))).flatMap((s) => s.docs);
  const tokens = devices.map((d) => String(d.get("fcmToken") ?? "")).filter((t) => t.length > 0);
  if (tokens.length === 0) return;
  const result = await getMessaging().sendEachForMulticast({
    tokens,
    data: push.data,
    android: { priority: push.urgent ? "high" : "normal", ttl: 24 * 3600 * 1000 },
  });
  const gone = result.responses
    .map((r, i) => (r.error?.code === "messaging/registration-token-not-registered" ? devices[i] : null))
    .filter((d) => d !== null);
  await Promise.all(gone.map((d) => d.ref.delete()));
};

let sender: Sender = fcmSender;

/** Tests replace the sender; production always uses FCM. */
export function setSender(s: Sender): void {
  sender = s;
}

interface Access {
  uid: string;
  memberId: string;
  role: string;
}

async function accessOf(db: Firestore, fid: string): Promise<Access[]> {
  const snap = await db.collection(`families/${fid}/access`).get();
  return snap.docs.map((d) => ({ uid: d.id, memberId: String(d.get("memberId") ?? ""), role: String(d.get("role") ?? "") }));
}

async function nameOf(db: Firestore, fid: string, personId: string | undefined | null): Promise<string> {
  if (!personId) return "";
  const member = await db.doc(`families/${fid}/members/${personId}`).get();
  if (member.exists) return String(member.get("displayName") ?? "");
  const child = await db.doc(`families/${fid}/children/${personId}`).get();
  return child.exists ? String(child.get("firstName") ?? "") : "";
}

async function childNames(db: Firestore, fid: string, ids: string[]): Promise<string[]> {
  const docs = await Promise.all(ids.map((id) => db.doc(`families/${fid}/children/${id}`).get()));
  return docs.filter((d) => d.exists).map((d) => String(d.get("firstName") ?? ""));
}

/** "18:30" from a hand-off time or the event's own start/end ("2026-10-08T18:30"). */
function timeOf(event: DocumentData | undefined, kind: string): string {
  const handoff = kind === "pickup" ? event?.pickUp : event?.dropOff;
  if (handoff?.time) return String(handoff.time).slice(0, 5);
  const at = String((kind === "pickup" ? event?.end : event?.start) ?? "");
  return at.includes("T") ? at.split("T")[1].slice(0, 5) : "";
}

/** A new "Kan du ta hämtningen?": to the person asked, or to every other adult. */
export async function onHandoffRequest(db: Firestore, fid: string, requestId: string, request: DocumentData): Promise<void> {
  if ((request.status ?? "pending") !== "pending") return;
  const access = await accessOf(db, fid);
  const recipients = access.filter((a) => a.memberId !== request.fromMemberId && (!request.toMemberId || a.memberId === request.toMemberId));
  if (recipients.length === 0) return;
  const event = (await db.doc(`families/${fid}/events/${request.eventId}`).get()).data();
  const kind = String(request.kind ?? "pickup");
  await sender(db, {
    uids: recipients.map((a) => a.uid),
    urgent: true,
    data: {
      type: "handoff_request",
      familyId: fid,
      requestId,
      eventId: String(request.eventId ?? ""),
      date: String(request.occurrenceDate ?? ""),
      kind,
      fromName: await nameOf(db, fid, request.fromMemberId),
      eventTitle: String(event?.title ?? ""),
      children: (await childNames(db, fid, (event?.participantIds as string[] | undefined) ?? [])).join(", "),
      time: timeOf(event, kind),
    },
  });
}

/** "Johan tar det" back to whoever asked. */
export async function onHandoffAnswer(db: Firestore, fid: string, requestId: string, before: DocumentData, after: DocumentData): Promise<void> {
  if (before.status !== "pending" || (after.status !== "accepted" && after.status !== "declined")) return;
  const asker = (await accessOf(db, fid)).filter((a) => a.memberId === after.fromMemberId);
  if (asker.length === 0) return;
  const event = (await db.doc(`families/${fid}/events/${after.eventId}`).get()).data();
  await sender(db, {
    uids: asker.map((a) => a.uid),
    data: {
      type: "handoff_answer",
      familyId: fid,
      requestId,
      eventId: String(after.eventId ?? ""),
      date: String(after.occurrenceDate ?? ""),
      kind: String(after.kind ?? "pickup"),
      accepted: String(after.status === "accepted"),
      byName: await nameOf(db, fid, after.respondedBy),
      eventTitle: String(event?.title ?? ""),
    },
  });
}

/** Something added to a list while someone is in the shop with it ("Sara handlar nu"). */
export async function onShoppingItem(db: Firestore, fid: string, listId: string, item: DocumentData, now: Date = new Date()): Promise<void> {
  const presence = await db.collection(`families/${fid}/presence`).get();
  const shoppers = presence.docs
    .filter((d) => d.get("listId") === listId && d.get("memberId") !== item.addedBy && Date.parse(String(d.get("until") ?? "")) > now.getTime())
    .map((d) => String(d.get("memberId")));
  if (shoppers.length === 0) return;
  const access = await accessOf(db, fid);
  const uids = access.filter((a) => shoppers.includes(a.memberId)).map((a) => a.uid);
  if (uids.length === 0) return;
  await sender(db, {
    uids,
    data: { type: "shopping_added", familyId: fid, listId, name: String(item.name ?? ""), byName: await nameOf(db, fid, item.addedBy) },
  });
}

const WATCHED_FIELDS = ["title", "start", "end", "allDay", "location", "dropOff", "pickUp", "rrule", "exDates"];
const NOTIFY_DAYS = 14;

/** A family event added or changed by someone, starting within two weeks: tell the other adults who can see it. */
export async function onEventWritten(
  db: Firestore,
  fid: string,
  eventId: string,
  before: DocumentData | undefined,
  after: DocumentData | undefined,
  now: Date = new Date(),
): Promise<void> {
  if (!after || after.deletedAt) return;
  const changed = !before || WATCHED_FIELDS.some((f) => JSON.stringify(before[f] ?? null) !== JSON.stringify(after[f] ?? null));
  if (!changed) return;
  const start = Date.parse(String(after.start ?? ""));
  const soon = !Number.isNaN(start) && (after.rrule || (start > now.getTime() - 86_400_000 && start < now.getTime() + NOTIFY_DAYS * 86_400_000));
  if (!soon) return;
  const access = await accessOf(db, fid);
  const author = access.find((a) => a.uid === after.updatedBy);
  const uids = access
    .filter((a) => a.uid !== after.updatedBy && (a.role === "parent" || (a.role === "caregiver" && after.sharedWithCaregivers !== false)))
    .map((a) => a.uid);
  if (uids.length === 0 || !author) return;
  await sender(db, {
    uids,
    data: {
      type: before ? "event_changed" : "event_added",
      familyId: fid,
      eventId: String(after.seriesId ?? eventId),
      date: String(after.originalDate ?? String(after.start ?? "").slice(0, 10)),
      title: String(after.title ?? ""),
      byName: await nameOf(db, fid, author.memberId),
    },
  });
}

export const pushHandoffRequest = onDocumentCreated("families/{fid}/handoffRequests/{rid}", async (event) => {
  const data = event.data?.data();
  if (data) await onHandoffRequest(getFirestore(), event.params.fid, event.params.rid, data);
});

export const pushHandoffAnswer = onDocumentUpdated("families/{fid}/handoffRequests/{rid}", async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (before && after) await onHandoffAnswer(getFirestore(), event.params.fid, event.params.rid, before, after);
});

export const pushShoppingItem = onDocumentCreated("families/{fid}/shoppingLists/{lid}/items/{iid}", async (event) => {
  const data = event.data?.data();
  if (data) await onShoppingItem(getFirestore(), event.params.fid, event.params.lid, data);
});

export const pushEventChange = onDocumentWritten("families/{fid}/events/{eid}", async (event) => {
  await onEventWritten(getFirestore(), event.params.fid, event.params.eid, event.data?.before.data(), event.data?.after.data());
});
