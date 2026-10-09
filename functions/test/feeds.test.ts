/**
 * calendarFeed / revokeCalendarFeeds against the Firestore emulator (started by `npm test`).
 * Uses its own family id and tokens and removes only those, so a running demo app is never touched.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { calendarFeed, revokeCalendarFeeds } from "../src/feeds.js";

// Own project id on the shared emulator, next to the rules tests' "demo-pusslet-rules".
if (getApps().length === 0) initializeApp({ projectId: "demo-pusslet-functions" });

const FID = "feedtest-family";
const SPACE = "feedtest-space";
const TOKEN = "feedtestTOKEN_abcdefghijklmnop";
const SARA_TOKEN = "feedtestTOKEN_sara_abcdefghijk";

interface FakeResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}

/** Calls an onRequest handler with a minimal request/response pair. */
async function get(path: string, query: Record<string, string> = {}): Promise<FakeResponse> {
  const out: FakeResponse = { status: 0, headers: {}, body: "" };
  const res = {
    status(code: number) {
      out.status = code;
      return res;
    },
    set(name: string, value: string) {
      out.headers[name.toLowerCase()] = value;
      return res;
    },
    send(body: string) {
      out.body = body;
      return res;
    },
    // What the firebase-functions wrapper touches on a real response.
    on() {
      return res;
    },
    once() {
      return res;
    },
    setHeader(name: string, value: string) {
      out.headers[name.toLowerCase()] = value;
    },
    getHeader(name: string) {
      return out.headers[name.toLowerCase()];
    },
    end() {
      return res;
    },
    headersSent: false,
  };
  const handler = calendarFeed as unknown as (req: unknown, res: unknown) => Promise<void>;
  await handler({ method: "GET", path, query, headers: {} }, res);
  return out;
}

const callRevoke = (uid: string | null, familyId: string) =>
  (revokeCalendarFeeds as unknown as { run: (r: unknown) => Promise<{ revoked: number }> }).run({
    data: { familyId },
    auth: uid ? { uid, token: {} } : undefined,
  });

beforeAll(async () => {
  const db = getFirestore();
  const family = db.doc(`families/${FID}`);
  await family.set({ name: "Familjen Test" });
  await family.collection("access").doc("sara").set({ memberId: "m-sara", role: "parent" });
  await family.collection("access").doc("bengt").set({ memberId: "m-bengt", role: "caregiver" });
  await family.collection("members").doc("m-sara").set({ displayName: "Sara" });
  await family.collection("children").doc("c-simon").set({ firstName: "Simon" });
  await family.collection("events").doc("e1").set({
    title: "Fotboll",
    start: "2026-09-01T17:30",
    end: "2026-09-01T18:30",
    rrule: "FREQ=WEEKLY;BYDAY=TU",
    participantIds: ["c-simon"],
    pickUp: { personId: "m-sara", time: null },
  });
  await family.collection("events").doc("e2").set({ title: "Tandläkare", start: "2026-10-12T09:00", end: "2026-10-12T10:00", participantIds: ["c-simon"] });
  // Ella is shared with her mum's home: her things live in the space (ADR-030).
  await family.set({ sharedSpaces: [SPACE, "feedtest-stranger"] }, { merge: true });
  const space = db.doc(`families/${SPACE}`);
  await space.set({ name: "Ella", shared: true, familyIds: [FID] });
  await space.collection("children").doc("c-ella").set({ firstName: "Ella" });
  await space.collection("members").doc("m-lina").set({ displayName: "Lina" });
  await space.collection("events").doc("e3").set({
    title: "Simning", start: "2026-10-14T17:00", end: "2026-10-14T18:00", participantIds: ["c-ella"], pickUp: { personId: "m-lina", time: null },
  });
  // Listed by the home but not listing it back: never read.
  await db.doc("families/feedtest-stranger").set({ name: "Annan", shared: true, familyIds: ["someone"] });
  await db.doc("families/feedtest-stranger/events/x").set({ title: "Hemligt", start: "2026-10-15T10:00", end: "2026-10-15T11:00" });
  await db.doc(`calendarFeeds/${TOKEN}`).set({ familyId: FID, memberId: null, createdBy: "sara" });
  await db.doc(`calendarFeeds/${SARA_TOKEN}`).set({ familyId: FID, memberId: "m-sara", createdBy: "sara" });
});

afterAll(async () => {
  const db = getFirestore();
  await db.recursiveDelete(db.doc(`families/${FID}`));
  await db.recursiveDelete(db.doc(`families/${SPACE}`));
  await db.recursiveDelete(db.doc("families/feedtest-stranger"));
  await db.doc(`calendarFeeds/${TOKEN}`).delete();
  await db.doc(`calendarFeeds/${SARA_TOKEN}`).delete();
});

describe("calendarFeed", () => {
  it("serves the family's events as text/calendar", async () => {
    const r = await get(`/feed/${TOKEN}.ics`);
    expect(r.status).toBe(200);
    expect(r.headers["content-type"]).toBe("text/calendar; charset=utf-8");
    expect(r.headers["cache-control"]).toContain("private");
    expect(r.body).toContain("X-WR-CALNAME:Familjen Test");
    expect(r.body).toContain("SUMMARY:Fotboll");
    expect(r.body).toContain("SUMMARY:Tandläkare");
    expect(r.body).toContain("Hämtar: Sara");
  });

  it("includes what the family shares with the other home, and nothing a space does not confirm", async () => {
    const r = await get(`/feed/${TOKEN}.ics`);
    expect(r.body).toContain("SUMMARY:Simning");
    expect(r.body).toContain("Hämtar: Lina");
    expect(r.body).not.toContain("Hemligt");
  });

  it("a personal feed only has that person's events", async () => {
    const r = await get(`/feed/${SARA_TOKEN}.ics`);
    expect(r.status).toBe(200);
    expect(r.body).toContain("X-WR-CALNAME:Familjen Test – Sara");
    expect(r.body).toContain("SUMMARY:Fotboll");
    expect(r.body).not.toContain("Tandläkare");
  });

  it("unknown or malformed tokens get a plain 404", async () => {
    expect((await get("/feed/doesnotexist_abcdefghijklmnop.ics")).status).toBe(404);
    expect((await get("/feed/short.ics")).status).toBe(404);
  });
});

describe("revokeCalendarFeeds", () => {
  it("only a parent of the family can revoke, and it removes every link", async () => {
    await expect(callRevoke(null, FID)).rejects.toThrow();
    await expect(callRevoke("bengt", FID)).rejects.toThrow();
    const result = await callRevoke("sara", FID);
    expect(result.revoked).toBe(2);
    expect((await get(`/feed/${TOKEN}.ics`)).status).toBe(404);
  });
});
