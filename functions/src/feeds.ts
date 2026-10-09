/**
 * The family's own calendar feed: a secret, revocable link that work calendars (Outlook, Google) can subscribe to
 * without signing in. The token document is created by the app (security rules: parents only, ≥ 24 characters);
 * this function only reads. Nothing is logged about the family or its events.
 */
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { getFirestore, type Firestore, type QuerySnapshot } from "firebase-admin/firestore";
import { buildFeed, isValidToken, type FeedEvent } from "./ics.js";

interface FeedDoc {
  familyId: string;
  memberId?: string | null;
}

/**
 * The spaces a home shares with the other home (ADR-030). A home lists them itself, so a space only counts when it also
 * lists the home – a parent can never pull a stranger's events into the feed.
 */
async function sharedSpaces(db: Firestore, familyId: string, listed: unknown): Promise<string[]> {
  const ids = Array.isArray(listed) ? listed.filter((id): id is string => typeof id === "string" && id.length > 0) : [];
  const docs = await Promise.all(ids.map((id) => db.doc(`families/${id}`).get()));
  return docs
    .filter((d) => d.exists && d.get("shared") === true && ((d.get("familyIds") as unknown[] | undefined) ?? []).includes(familyId))
    .map((d) => d.id);
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
  const spaces = await sharedSpaces(db, familyId, familyDoc.get("sharedSpaces"));
  const shared = await Promise.all(
    spaces.map((id) => Promise.all(["members", "children", "events"].map((c) => db.collection(`families/${id}/${c}`).get()))),
  );
  const names = new Map<string, string>();
  const addNames = (snap: QuerySnapshot, field: string) =>
    snap.forEach((d) => {
      if (!names.has(d.id)) names.set(d.id, String(d.get(field) ?? ""));
    });
  addNames(members, "displayName");
  addNames(children, "firstName");
  shared.forEach(([m, c]) => {
    addNames(m, "displayName");
    addNames(c, "firstName");
  });
  const allEvents = [events, ...shared.map(([, , e]) => e)].flatMap((s) => s.docs);
  const familyName = String(familyDoc.get("name") ?? "Pusslet");
  const calendarName = memberId && names.get(memberId) ? `${familyName} – ${names.get(memberId)}` : familyName;
  const ics = buildFeed(
    allEvents.map((d) => ({ ...(d.data() as Omit<FeedEvent, "id">), id: d.id })),
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
