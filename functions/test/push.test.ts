/** Push recipients and payloads against the Firestore emulator, with a recording sender instead of FCM. */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { onEventWritten, onHandoffAnswer, onHandoffRequest, onShoppingItem, setSender, type Push } from "../src/push.js";

if (getApps().length === 0) initializeApp({ projectId: "demo-pusslet-functions" });

const FID = "pushtest";
const db = () => getFirestore();
let sent: Push[] = [];

/** The one push a step should have produced. */
function only(pushes: Push[]): Push {
  expect(pushes.length).toBe(1);
  return pushes[0];
}

beforeAll(async () => {
  setSender(async (_db, push) => {
    sent.push(push);
  });
  const f = db().doc(`families/${FID}`);
  await f.set({ name: "Familjen Push" });
  for (const [uid, memberId, role, name] of [
    ["u-sara", "m-sara", "parent", "Sara"],
    ["u-johan", "m-johan", "parent", "Johan"],
    ["u-bengt", "m-bengt", "caregiver", "Bengt"],
  ]) {
    await f.collection("access").doc(uid).set({ memberId, role });
    await f.collection("members").doc(memberId).set({ displayName: name, uid });
  }
  await f.collection("children").doc("c-simon").set({ firstName: "Simon" });
  await f.collection("events").doc("fotboll").set({
    title: "Fotboll",
    start: "2026-10-08T17:00",
    end: "2026-10-08T18:00",
    participantIds: ["c-simon"],
    pickUp: { personId: "m-sara", time: "18:15" },
  });
});

beforeEach(() => {
  sent = [];
});

afterAll(async () => {
  await db().recursiveDelete(db().doc(`families/${FID}`));
});

const now = new Date("2026-10-08T05:10:00Z");

describe("hand-off requests", () => {
  it("go to every other adult when nobody in particular is asked", async () => {
    await onHandoffRequest(db(), FID, "r1", { eventId: "fotboll", occurrenceDate: "2026-10-08", kind: "pickup", fromMemberId: "m-sara", status: "pending" });
    const push = only(sent);
    expect(push.uids.sort()).toEqual(["u-bengt", "u-johan"]);
    expect(push.urgent).toBe(true);
    expect(push.data).toMatchObject({ type: "handoff_request", fromName: "Sara", eventTitle: "Fotboll", children: "Simon", time: "18:15", kind: "pickup" });
  });

  it("go only to the person asked", async () => {
    await onHandoffRequest(db(), FID, "r2", { eventId: "fotboll", occurrenceDate: "2026-10-08", kind: "dropoff", fromMemberId: "m-sara", toMemberId: "m-bengt" });
    expect(only(sent).uids).toEqual(["u-bengt"]);
    expect(only(sent).data.time).toBe("17:00");
  });

  it("answers go back to the asker", async () => {
    const before = { eventId: "fotboll", occurrenceDate: "2026-10-08", kind: "pickup", fromMemberId: "m-sara", status: "pending" };
    await onHandoffAnswer(db(), FID, "r1", before, { ...before, status: "accepted", respondedBy: "m-johan" });
    expect(only(sent).uids).toEqual(["u-sara"]);
    expect(only(sent).data).toMatchObject({ type: "handoff_answer", accepted: "true", byName: "Johan" });
    await onHandoffAnswer(db(), FID, "r1", { ...before, status: "accepted" }, { ...before, status: "accepted", message: "tack" });
    expect(sent.length).toBe(1);
  });
});

describe("shopping", () => {
  it("tells whoever is in the shop with that list, not the one who added it", async () => {
    const presence = db().collection(`families/${FID}/presence`);
    await presence.doc("m-sara").set({ memberId: "m-sara", listId: "l1", startedAt: "2026-10-08T05:00:00Z", until: "2026-10-08T05:45:00Z" });
    await presence.doc("m-bengt").set({ memberId: "m-bengt", listId: "l1", startedAt: "2026-10-08T04:00:00Z", until: "2026-10-08T05:00:00Z" });
    await onShoppingItem(db(), FID, "l1", { name: "Mjölk", addedBy: "m-johan" }, now);
    expect(only(sent).uids).toEqual(["u-sara"]);
    expect(only(sent).data).toMatchObject({ type: "shopping_added", name: "Mjölk", byName: "Johan" });
    await onShoppingItem(db(), FID, "l1", { name: "Bröd", addedBy: "m-sara" }, now);
    await onShoppingItem(db(), FID, "l2", { name: "Plåster", addedBy: "m-johan" }, now);
    expect(sent.length).toBe(1);
  });
});

describe("calendar changes", () => {
  const event = { title: "Tandläkare", start: "2026-10-09T09:00", end: "2026-10-09T10:00", updatedBy: "u-johan", sharedWithCaregivers: false };

  it("tell the other parents about a new event soon, but not helpers when it is private", async () => {
    await onEventWritten(db(), FID, "e1", undefined, event, now);
    expect(only(sent).uids).toEqual(["u-sara"]);
    expect(only(sent).data).toMatchObject({ type: "event_added", title: "Tandläkare", byName: "Johan", date: "2026-10-09" });
  });

  it("skip edits that change nothing visible, far-away events and deletions", async () => {
    await onEventWritten(db(), FID, "e1", event, { ...event, note: "ny anteckning" }, now);
    await onEventWritten(db(), FID, "e1", undefined, { ...event, start: "2027-03-01T09:00" }, now);
    await onEventWritten(db(), FID, "e1", event, { ...event, deletedAt: "x" }, now);
    expect(sent.length).toBe(0);
    await onEventWritten(db(), FID, "e1", event, { ...event, start: "2026-10-09T10:00", sharedWithCaregivers: true }, now);
    expect(only(sent).uids.sort()).toEqual(["u-bengt", "u-sara"]);
    expect(only(sent).data.type).toBe("event_changed");
  });
});
