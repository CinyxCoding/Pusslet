/**
 * The family's own calendar feed: a secret, revocable link that work calendars (Outlook, Google) can subscribe to
 * without signing in. The token document is created by the app (security rules: parents only, ≥ 24 characters);
 * this function only reads. Nothing is logged about the family or its events.
 */
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { buildFeed, isValidToken, type FeedEvent } from "./ics.js";

interface FeedDoc {
  familyId: string;
  memberId?: string | null;
}

/** GET /feed/<token>.ics (Hosting rewrite) or /calendarFeed/<token>.ics. Optional ?lang=en. */
export const calendarFeed = onRequest({ cors: false }, async (req, res) => {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.status(405).send("");
    return;
  }
  const last = req.path.split("/").filter(Boolean).pop() ?? "";
  const token = (last || String(req.query.t ?? "")).replace(/\.ics$/i, "");
  // Unknown and malformed tokens look the same from outside.
  if (!isValidToken(token)) {
    res.status(404).send("Not found");
    return;
  }
  const db = getFirestore();
  const feed = await db.doc(`calendarFeeds/${token}`).get();
  if (!feed.exists) {
    res.status(404).send("Not found");
    return;
  }
  const { familyId, memberId } = feed.data() as FeedDoc;
  const family = db.doc(`families/${familyId}`);
  const [familyDoc, members, children, events] = await Promise.all([
    family.get(),
    family.collection("members").get(),
    family.collection("children").get(),
    family.collection("events").get(),
  ]);
  const names = new Map<string, string>();
  members.forEach((d) => names.set(d.id, String(d.get("displayName") ?? "")));
  children.forEach((d) => names.set(d.id, String(d.get("firstName") ?? "")));
  const familyName = String(familyDoc.get("name") ?? "Pusslet");
  const calendarName = memberId && names.get(memberId) ? `${familyName} – ${names.get(memberId)}` : familyName;
  const ics = buildFeed(
    events.docs.map((d) => ({ ...(d.data() as Omit<FeedEvent, "id">), id: d.id })),
    { calendarName, memberId, names, now: new Date(), language: req.query.lang === "en" ? "en" : "sv" },
  );
  res.set("Content-Type", "text/calendar; charset=utf-8");
  res.set("Cache-Control", "private, max-age=900");
  res.status(200).send(ics);
});

/** "Återkalla alla länkar": a parent revokes every feed of the family, from any device. */
export const revokeCalendarFeeds = onCall(async (request) => {
  const uid = request.auth?.uid;
  const familyId = String(request.data?.familyId ?? "");
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");
  if (!familyId) throw new HttpsError("invalid-argument", "familyId missing");
  const db = getFirestore();
  const access = await db.doc(`families/${familyId}/access/${uid}`).get();
  if (access.get("role") !== "parent") throw new HttpsError("permission-denied", "Only parents can revoke feeds");
  const feeds = await db.collection("calendarFeeds").where("familyId", "==", familyId).get();
  const batch = db.batch();
  feeds.forEach((d) => batch.delete(d.ref));
  await batch.commit();
  return { revoked: feeds.size };
});
