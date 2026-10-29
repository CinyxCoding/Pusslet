/**
 * The family's own calendar feed (BRIEF §3, docs/DECISIONS.md ADR-041): `GET /familyCalendarFeed/<token>.ics` (or
 * `?t=<token>`) answers with the family's events as iCalendar, for Google Calendar, Apple Calendar and Outlook to subscribe
 * to. The token is a secret link a parent made in the app (`calendarFeeds/{token}`, never readable by clients, see
 * firestore.rules); an unknown or revoked token, a deleted family, or a link whose maker is no longer a parent there gets
 * a plain 404 that tells nothing more. A feed with a `memberId` only has that person's events. Logs carry counts only,
 * never names, titles or tokens.
 */
import * as logger from "firebase-functions/logger";
import { onRequest } from "firebase-functions/v2/https";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { buildCalendar, type FeedEvent } from "./ics.js";
import { REGION } from "./setup.js";

/** Tokens the app makes (32 random characters); anything else is refused before a path is built from it. */
const TOKEN = /^[A-Za-z0-9_-]{24,128}$/;
const FAMILY_ID = /^[A-Za-z0-9_-]{1,128}$/;
/** A Firebase uid as a document id. */
const UID = /^(?!\.\.?$)[^/\s]{1,128}$/;

/**
 * The spaces a home shares with the other home (ADR-056). A home lists them itself, so a space only counts when it also
 * lists the home – a parent can never pull a stranger's events into the feed.
 */
async function sharedSpaces(db: Firestore, familyId: string, listed: unknown): Promise<string[]> {
  const ids = Array.isArray(listed) ? listed.filter((id): id is string => typeof id === "string" && FAMILY_ID.test(id)) : [];
  const docs = await Promise.all(ids.map((id) => db.doc(`families/${id}`).get()));
  return docs
    .filter((d) => d.exists && d.get("shared") === true && ((d.get("familyIds") as unknown[] | undefined) ?? []).includes(familyId))
    .map((d) => d.id);
}

/** The token from "/<token>.ics", "/<token>" or "?t=<token>". */
export function tokenOf(path: string, query: unknown): string | null {
  const fromPath = path.split("/").filter(Boolean).pop()?.replace(/\.ics$/i, "");
  const candidate = fromPath || (typeof query === "string" ? query : "");
  return TOKEN.test(candidate) ? candidate : null;
}

export const familyCalendarFeed = onRequest(
  { region: REGION, cors: false, timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.status(405).set("Allow", "GET, HEAD").send("");
      return;
    }
    const token = tokenOf(req.path, req.query.t);
    if (!token) {
      res.status(404).send("Not found");
      return;
    }
    const db = getFirestore();
    const feed = await db.doc(`calendarFeeds/${token}`).get();
    const familyId = feed.get("familyId");
    const createdBy = feed.get("createdBy");
    if (!feed.exists || typeof familyId !== "string" || !FAMILY_ID.test(familyId) || typeof createdBy !== "string" || !UID.test(createdBy)) {
      res.status(404).send("Not found");
      return;
    }
    // The link lives as long as its maker is a parent here: the other parents cannot see the token to revoke it, so a
    // parent who was removed (or made a helper) must not keep reading the family's whole calendar through it.
    const [tombstone, family, maker] = await Promise.all([
      db.doc(`deletedFamilies/${familyId}`).get(),
      db.doc(`families/${familyId}`).get(),
      db.doc(`families/${familyId}/access/${createdBy}`).get(),
    ]);
    if (tombstone.exists || !family.exists || maker.get("role") !== "parent") {
      res.status(404).send("Not found");
      return;
    }
    const [events, members, children] = await Promise.all([
      db.collection(`families/${familyId}/events`).get(),
      db.collection(`families/${familyId}/members`).get(),
      db.collection(`families/${familyId}/children`).get(),
    ]);
    // The children's events in spaces shared with the other home, and the names there (ADR-056).
    const spaces = await sharedSpaces(db, familyId, family.get("sharedSpaces"));
    const shared = await Promise.all(
      spaces.map((id) => Promise.all(["members", "children", "events"].map((c) => db.collection(`families/${id}/${c}`).get()))),
    );
    const names = new Map<string, string>();
    const memberDocs = [members, ...shared.map(([m]) => m)].flatMap((s) => s.docs);
    const childDocs = [children, ...shared.map(([, c]) => c)].flatMap((s) => s.docs);
    for (const m of memberDocs) {
      if (!m.get("deletedAt") && !names.has(m.id)) names.set(m.id, m.get("nickname") || m.get("displayName") || "");
    }
    for (const c of childDocs) if (!c.get("deletedAt") && !names.has(c.id)) names.set(c.id, c.get("firstName") || "");
    const memberId = feed.get("memberId");
    const list = [events, ...shared.map(([, , e]) => e)]
      .flatMap((s) => s.docs)
      .filter((d) => !d.get("deletedAt"))
      .map((d) => ({ ...(d.data() as Omit<FeedEvent, "id">), id: d.id }))
      .filter((e) => typeof memberId !== "string" || (e.participantIds ?? []).includes(memberId));
    const body = buildCalendar({ name: family.get("name") ?? "", events: list, names, now: new Date() });
    res
      .status(200)
      .set("Content-Type", "text/calendar; charset=utf-8")
      .set("Cache-Control", "private, max-age=900")
      .set("Content-Disposition", 'inline; filename="pusslet.ics"')
      .send(req.method === "HEAD" ? "" : body);
    logger.info("Calendar feed served", { events: list.length });
  },
);
