// End-to-end test of the family calendar feed (src/calendarFeed.ts) through the Functions emulator, with the data seeded
// with the Admin SDK. Run with `npm test`. Every test seeds its own ids, so a shared emulator is never touched.
import { type App, deleteApp, initializeApp } from "firebase-admin/app";
import { type Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-pusslet";
const REGION = "europe-north1";
const RUN = Math.random().toString(36).slice(2, 8);

let app: App;
let db: Firestore;
let feedUrl: string;

beforeAll(async () => {
  const hub = process.env.FIREBASE_EMULATOR_HUB;
  if (!hub || !process.env.FIRESTORE_EMULATOR_HOST) throw new Error("The feed tests need the emulators: run `npm test`.");
  const running = (await (await fetch(`http://${hub}/emulators`)).json()) as Record<string, { host: string; port: number }>;
  if (!running.functions) throw new Error("The functions emulator is not running: run `npm test`.");
  feedUrl = `http://${running.functions.host}:${running.functions.port}/${PROJECT}/${REGION}/familyCalendarFeed`;
  app = initializeApp({ projectId: PROJECT }, `feed-${RUN}`);
  db = getFirestore(app);
});

afterAll(async () => {
  if (app) await deleteApp(app);
});

async function seed(fid: string, token: string, memberId?: string) {
  await db.doc(`families/${fid}`).set({ name: "Familjen Lind", timeZoneId: "Europe/Stockholm" });
  await db.doc(`families/${fid}/members/sara`).set({ displayName: "Sara", role: "parent" });
  await db.doc(`families/${fid}/children/ella`).set({ firstName: "Ella" });
  await db.doc(`families/${fid}/events/e-dans`).set({
    id: "e-dans",
    title: "Dans",
    start: "2026-10-19T17:00",
    end: "2026-10-19T18:00",
    timeZoneId: "Europe/Stockholm",
    rrule: "FREQ=WEEKLY;BYDAY=MO",
    participantIds: ["ella"],
  });
  await db.doc(`families/${fid}/events/e-tand`).set({
    id: "e-tand",
    title: "Tandläkare",
    start: "2026-10-22T14:00",
    end: "2026-10-22T14:30",
    timeZoneId: "Europe/Stockholm",
    participantIds: ["sara"],
  });
  await db.doc(`families/${fid}/events/e-borta`).set({
    id: "e-borta",
    title: "Borttagen",
    start: "2026-10-23T09:00",
    end: "2026-10-23T10:00",
    deletedAt: Timestamp.now(),
  });
  await db.doc(`calendarFeeds/${token}`).set({ familyId: fid, createdBy: "sara-uid", ...(memberId ? { memberId } : {}) });
}

describe("familyCalendarFeed", () => {
  it("serves the family's events for a valid token, without deleted ones", async () => {
    const fid = `feed-${RUN}-a`;
    const token = `tok${RUN}aaaaaaaaaaaaaaaaaaaaaaaaaaa`;
    await seed(fid, token);
    const res = await fetch(`${feedUrl}/${token}.ics`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/calendar");
    const body = await res.text();
    expect(body).toContain("X-WR-CALNAME:Familjen Lind");
    expect(body).toContain("SUMMARY:Dans");
    expect(body).toContain("DESCRIPTION:Ella");
    expect(body).toContain("SUMMARY:Tandläkare");
    expect(body).not.toContain("Borttagen");
    expect((await fetch(`${feedUrl}?t=${token}`)).status).toBe(200);
  });

  it("only has that person's events in a personal feed", async () => {
    const fid = `feed-${RUN}-b`;
    const token = `tok${RUN}bbbbbbbbbbbbbbbbbbbbbbbbbbb`;
    await seed(fid, token, "sara");
    const body = await (await fetch(`${feedUrl}/${token}.ics`)).text();
    expect(body).toContain("SUMMARY:Tandläkare");
    expect(body).not.toContain("SUMMARY:Dans");
  });

  it("includes what the family shares with the other home, and nothing a space does not confirm (ADR-056)", async () => {
    const fid = `feed-${RUN}-s`;
    const token = `tok${RUN}sssssssssssssssssssssssssss`;
    const space = `feed-${RUN}-space`;
    const stranger = `feed-${RUN}-stranger`;
    await seed(fid, token);
    await db.doc(`families/${fid}`).set({ sharedSpaces: [space, stranger] }, { merge: true });
    await db.doc(`families/${space}`).set({ name: "Ella", shared: true, familyIds: [fid] });
    await db.doc(`families/${space}/members/m-lina`).set({ displayName: "Lina" });
    await db.doc(`families/${space}/events/e-sim`).set({
      title: "Simning",
      start: "2026-10-21T17:00",
      end: "2026-10-21T18:00",
      timeZoneId: "Europe/Stockholm",
      participantIds: ["ella"],
    });
    // Listed by the home but not listing it back: never read.
    await db.doc(`families/${stranger}`).set({ name: "Annan", shared: true, familyIds: ["someone"] });
    await db.doc(`families/${stranger}/events/x`).set({ title: "Hemligt", start: "2026-10-22T10:00", end: "2026-10-22T11:00" });
    const body = await (await fetch(`${feedUrl}/${token}.ics`)).text();
    expect(body).toContain("SUMMARY:Simning");
    expect(body).toContain("SUMMARY:Dans");
    expect(body).not.toContain("Hemligt");
  });

  it("says nothing for an unknown, revoked or malformed token, or a deleted family", async () => {
    const fid = `feed-${RUN}-c`;
    const token = `tok${RUN}ccccccccccccccccccccccccccc`;
    await seed(fid, token);
    expect((await fetch(`${feedUrl}/${token}.ics`)).status).toBe(200);
    await db.doc(`deletedFamilies/${fid}`).set({ deletedAt: Timestamp.now() });
    expect((await fetch(`${feedUrl}/${token}.ics`)).status).toBe(404);
    await db.doc(`calendarFeeds/${token}`).delete();
    expect((await fetch(`${feedUrl}/${token}.ics`)).status).toBe(404);
    expect((await fetch(`${feedUrl}/unknown${RUN}xxxxxxxxxxxxxxxxxxxxxx.ics`)).status).toBe(404);
    expect((await fetch(`${feedUrl}/short.ics`)).status).toBe(404);
    expect((await fetch(`${feedUrl}/${token}.ics`, { method: "POST" })).status).toBe(405);
  });
});
