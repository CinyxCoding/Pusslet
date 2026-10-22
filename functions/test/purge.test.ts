// The nightly purge (src/purge.ts) against the Firestore emulator: what is stripped to a marker, what is removed, what is
// left alone, and that a run continues where the last one stopped. Run with `npm test`; every test uses its own ids.
import { type App, deleteApp, initializeApp } from "firebase-admin/app";
import { type Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { purgeFamilies, purgeRun, windowsFor } from "../src/purge.js";

const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-pusslet";
const RUN = Math.random().toString(36).slice(2, 8);
const DAY = 24 * 60 * 60 * 1000;
const now = new Date("2026-10-22T02:00:00Z");
const daysAgo = (d: number) => Timestamp.fromMillis(now.getTime() - d * DAY);

let app: App;
let db: Firestore;

beforeAll(() => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("The purge tests need the emulators: run `npm test`.");
  app = initializeApp({ projectId: PROJECT }, `purge-${RUN}`);
  db = getFirestore(app);
});

afterAll(async () => {
  if (app) await deleteApp(app);
});

describe("the windows", () => {
  it("start from the beginning, then continue where the last run stopped", () => {
    const first = windowsFor(now);
    expect(first.stripFrom.toMillis()).toBe(0);
    expect(first.stripTo.toMillis()).toBe(daysAgo(30).toMillis());
    expect(first.removeTo.toMillis()).toBe(daysAgo(365).toMillis());
    const next = windowsFor(new Date(now.getTime() + DAY), { strippedBefore: first.stripTo, removedBefore: first.removeTo });
    expect(next.stripFrom.toMillis()).toBe(first.stripTo.toMillis());
    expect(next.stripTo.toMillis()).toBe(daysAgo(29).toMillis());
  });
});

describe("purging a family", () => {
  it("strips old deletions to a marker, removes year-old markers and leaves the rest", async () => {
    const fid = `purge-${RUN}`;
    const family = db.doc(`families/${fid}`);
    await family.set({ name: "Familjen Lind" });
    await db.doc(`families/${fid}/access/u1`).set({ memberId: "sara", role: "parent", deletedAt: daysAgo(400) });
    const stamp = { updatedAt: daysAgo(40), updatedBy: "u1" };
    await db.doc(`families/${fid}/events/old`).set({ title: "Tandläkare Ella", start: "2026-09-01T08:00", deletedAt: daysAgo(40), ...stamp });
    await db.doc(`families/${fid}/events/recent`).set({ title: "Dans", deletedAt: daysAgo(5), updatedAt: daysAgo(5), updatedBy: "u1" });
    await db.doc(`families/${fid}/events/alive`).set({ title: "Simning", updatedAt: daysAgo(100), updatedBy: "u1" });
    await db.doc(`families/${fid}/events/ancient`).set({ deletedAt: daysAgo(400), updatedAt: daysAgo(400), purgedAt: daysAgo(370) });
    await db.doc(`families/${fid}/shoppingLists/l1`).set({ name: "Veckohandling", updatedAt: daysAgo(100) });
    await db.doc(`families/${fid}/shoppingLists/l1/items/i1`).set({ name: "Mjölk", listId: "l1", deletedAt: daysAgo(31), ...stamp });
    await db.doc(`families/${fid}/shoppingLists/l1/items/i2`).set({ name: "Bröd", listId: "l1", updatedAt: daysAgo(31) });

    const result = await purgeFamilies(db, [family], windowsFor(now));
    expect(result).toEqual({ families: 1, stripped: 2, removed: 1 });

    const old = (await db.doc(`families/${fid}/events/old`).get()).data();
    expect(Object.keys(old ?? {}).sort()).toEqual(["deletedAt", "purgedAt", "updatedAt", "updatedBy"]);
    expect(old?.updatedAt.toMillis()).toBe(daysAgo(40).toMillis()); // unchanged, so phones are not sent it again
    expect((await db.doc(`families/${fid}/shoppingLists/l1/items/i1`).get()).get("name")).toBeUndefined();
    expect((await db.doc(`families/${fid}/events/ancient`).get()).exists).toBe(false);
    expect((await db.doc(`families/${fid}/events/recent`).get()).get("title")).toBe("Dans");
    expect((await db.doc(`families/${fid}/events/alive`).get()).get("title")).toBe("Simning");
    expect((await db.doc(`families/${fid}/shoppingLists/l1/items/i2`).get()).get("name")).toBe("Bröd");
    expect((await db.doc(`families/${fid}/access/u1`).get()).exists).toBe(true); // not synced documents
  });

  it("a nightly run continues where the last one stopped and handles a document once", async () => {
    const fid = `purge-run-${RUN}`;
    await db.doc(`families/${fid}`).set({ name: "Familjen Berg" });
    await db.doc(`families/${fid}/tasks/t1`).set({ title: "Ring skolan", deletedAt: daysAgo(31), updatedAt: daysAgo(31) });
    const state = db.doc(`_maintenance/purge-test-${RUN}`);

    await purgeRun(db, now, state);
    expect((await db.doc(`families/${fid}/tasks/t1`).get()).get("title")).toBeUndefined();
    const saved = (await state.get()).data();
    expect(saved?.strippedBefore.toMillis()).toBe(daysAgo(30).toMillis());

    // A stale phone writes into the marker; the next night does not look at it again.
    await db.doc(`families/${fid}/tasks/t1`).set({ title: "Ring skolan" }, { merge: true });
    await purgeRun(db, new Date(now.getTime() + DAY), state);
    expect((await db.doc(`families/${fid}/tasks/t1`).get()).get("deletedAt")).toBeDefined();
  });
});
