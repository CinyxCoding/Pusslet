/**
 * The family's own calendar feed (BRIEF §3, docs/DECISIONS.md ADR-041): `GET /familyCalendarFeed/<token>.ics` (or
 * `?t=<token>`) answers with the family's events as iCalendar, for Google Calendar, Apple Calendar and Outlook to subscribe
 * to. The token is a secret link a parent made in the app (`calendarFeeds/{token}`, never readable by clients, see
 * firestore.rules); an unknown or revoked token, or a deleted family, gets a plain 404 that tells nothing more. A feed with
 * a `memberId` only has that person's events. Logs carry counts only, never names, titles or tokens.
 */
import * as logger from "firebase-functions/logger";
import { onRequest } from "firebase-functions/v2/https";
import { getFirestore } from "firebase-admin/firestore";
import { buildCalendar, type FeedEvent } from "./ics.js";
import { REGION } from "./setup.js";

/** Tokens the app makes (32 random characters); anything else is refused before a path is built from it. */
const TOKEN = /^[A-Za-z0-9_-]{24,128}$/;
const FAMILY_ID = /^[A-Za-z0-9_-]{1,128}$/;

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
    if (!feed.exists || typeof familyId !== "string" || !FAMILY_ID.test(familyId)) {
      res.status(404).send("Not found");
      return;
    }
    const [tombstone, family, events, members, children] = await Promise.all([
      db.doc(`deletedFamilies/${familyId}`).get(),
      db.doc(`families/${familyId}`).get(),
      db.collection(`families/${familyId}/events`).get(),
      db.collection(`families/${familyId}/members`).get(),
      db.collection(`families/${familyId}/children`).get(),
    ]);
    if (tombstone.exists || !family.exists) {
      res.status(404).send("Not found");
      return;
    }
    const names = new Map<string, string>();
    for (const m of members.docs) if (!m.get("deletedAt")) names.set(m.id, m.get("nickname") || m.get("displayName") || "");
    for (const c of children.docs) if (!c.get("deletedAt")) names.set(c.id, c.get("firstName") || "");
    const memberId = feed.get("memberId");
    const list = events.docs
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
