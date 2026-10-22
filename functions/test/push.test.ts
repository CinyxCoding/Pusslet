// Push between phones (src/push.ts): who gets which message, and the triggers end to end through the emulators. There is
// no FCM emulator, so in the emulator the functions write what they would send to `_pushOutbox`. Run with `npm test`.
import { type App, deleteApp, initializeApp } from "firebase-admin/app";
import { type Firestore, getFirestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { itemAdded, pickedUp, requestAnswered, requestCreated, sickChanged } from "../src/push.js";

const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-pusslet";
const RUN = Math.random().toString(36).slice(2, 8);
const now = new Date("2026-10-21T15:00:00Z");

describe("who gets a push", () => {
  const request = { eventId: "e-skola", occurrenceDate: "2026-10-22", kind: "pickup", fromMemberId: "sara", status: "pending" };

  it("a request goes to the one asked, or to everyone but the sender", () => {
    expect(requestCreated("f", "r1", { ...request, toMemberId: "bengt" }, ["sara", "johan", "bengt"])?.memberIds).toEqual(["bengt"]);
    const anyone = requestCreated("f", "r1", request, ["sara", "johan", "bengt"]);
    expect(anyone?.memberIds).toEqual(["johan", "bengt"]);
    expect(anyone?.data).toEqual({
      type: "request",
      familyId: "f",
      requestId: "r1",
      eventId: "e-skola",
      date: "2026-10-22",
      kind: "pickup",
      from: "sara",
    });
    expect(requestCreated("f", "r1", { ...request, status: "accepted" }, ["johan"])).toBeNull();
  });

  it("an answer goes back to the one who asked", () => {
    const yes = requestAnswered("f", "r1", request, { ...request, toMemberId: "bengt", status: "accepted", respondedBy: "bengt" });
    expect(yes?.memberIds).toEqual(["sara"]);
    expect(yes?.data.status).toBe("accepted");
    expect(yes?.data.by).toBe("bengt");
    const no = requestAnswered("f", "r1", { ...request, declines: { johan: "t" } }, { ...request, declines: { johan: "t", bengt: "t" } });
    expect(no?.data).toMatchObject({ type: "answer", status: "declined", by: "bengt" });
    expect(requestAnswered("f", "r1", request, { ...request, message: "edited" } as typeof request)).toBeNull();
  });

  it("an item goes to whoever else is shopping from that list right now", () => {
    const presences = [
      { memberId: "sara", listId: "l1", until: new Date("2026-10-21T16:00:00Z") },
      { memberId: "johan", listId: "l1", until: new Date("2026-10-21T14:00:00Z") },
      { memberId: "bengt", listId: "l2", until: new Date("2026-10-21T16:00:00Z") },
    ];
    const push = itemAdded("f", "i1", { listId: "l1", name: "Mjölk", addedBy: "johan" }, presences, now);
    expect(push?.memberIds).toEqual(["sara"]);
    expect(push?.data).toMatchObject({ type: "shopping", listId: "l1", itemId: "i1", by: "johan", item: "Mjölk" });
    expect(itemAdded("f", "i1", { listId: "l1", name: "Mjölk", addedBy: "sara" }, presences, now)).toBeNull();
  });
});

describe("who hears about a sick child", () => {
  const members = [{ id: "sara", uid: "u-sara" }, { id: "johan", uid: "u-johan" }, { id: "bengt", uid: "u-bengt" }];
  const day = { childId: "ella", date: "2026-10-22", reportedBy: "sara", updatedBy: "u-sara" };

  it("a new sick day goes to everyone but the one who reported it, caregivers too", () => {
    const push = sickChanged("f", "ella_2026-10-22", undefined, day, members);
    expect(push?.memberIds).toEqual(["johan", "bengt"]);
    expect(push?.data).toEqual({
      type: "sick", familyId: "f", sickDayId: "ella_2026-10-22", childId: "ella", date: "2026-10-22", by: "sara", homeWith: "",
    });
  });

  it("who stays home goes to everyone but the one who chose", () => {
    const chosen = { ...day, homeWith: "johan", updatedBy: "u-johan" };
    expect(sickChanged("f", "s", day, chosen, members)?.memberIds).toEqual(["sara", "bengt"]);
    expect(sickChanged("f", "s", day, chosen, members)?.data.homeWith).toBe("johan");
  });

  it("ticking a report, the same person again or Ångra says nothing", () => {
    const home = { ...day, homeWith: "johan" };
    expect(sickChanged("f", "s", home, { ...home, absenceReported: true } as typeof home, members)).toBeNull();
    expect(sickChanged("f", "s", home, { ...home, deletedAt: "now" }, members)).toBeNull();
  });
});

describe("who hears that a child is picked up", () => {
  const members = [{ id: "sara", uid: "u-sara" }, { id: "johan", uid: "u-johan" }, { id: "bengt", uid: "u-bengt" }];

  it("everyone but the one who ticked it off", () => {
    const receipt = { kind: "pickup", childIds: ["ella", "simon"], doneBy: "bengt", doneAt: "2026-10-22T13:52:00Z", updatedBy: "u-bengt" };
    const push = pickedUp("f", "e-school_2026-10-22_pickup", receipt, members);
    expect(push?.memberIds).toEqual(["sara", "johan"]);
    expect(push?.data).toEqual({
      type: "pickedUp", familyId: "f", receiptId: "e-school_2026-10-22_pickup", childIds: "ella,simon", by: "bengt", at: "2026-10-22T13:52:00Z",
    });
    expect(pickedUp("f", "r", { ...receipt, kind: "dropoff" }, members)).toBeNull();
    expect(pickedUp("f", "r", { ...receipt, deletedAt: "now" }, members)).toBeNull();
  });
});

describe("the push triggers", () => {
  let app: App;
  let db: Firestore;

  beforeAll(() => {
    if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("The push trigger tests need the emulators: run `npm test`.");
    app = initializeApp({ projectId: PROJECT }, `push-${RUN}`);
    db = getFirestore(app);
  });

  afterAll(async () => {
    if (app) await deleteApp(app);
  });

  async function outbox(familyId: string, type: string): Promise<FirebaseFirestore.DocumentData | undefined> {
    for (let i = 0; i < 40; i++) {
      const found = (await db.collection("_pushOutbox").where("familyId", "==", familyId).get()).docs
        .map((d) => d.data())
        .find((d) => d.data?.type === type);
      if (found) return found;
      await new Promise((r) => setTimeout(r, 250));
    }
    return undefined;
  }

  it("sends a request, its answer and a shopping item to the right devices", async () => {
    const fid = `push-${RUN}`;
    await db.doc(`families/${fid}/members/sara`).set({ displayName: "Sara", uid: `u-sara-${RUN}` });
    await db.doc(`families/${fid}/members/bengt`).set({ displayName: "Bengt", uid: `u-bengt-${RUN}`, role: "caregiver" });
    await db.doc(`users/u-bengt-${RUN}/devices/d1`).set({ fcmToken: "token-bengt" });
    await db.doc(`users/u-sara-${RUN}/devices/d1`).set({ fcmToken: "token-sara" });

    const ref = db.doc(`families/${fid}/handoffRequests/r1`);
    await ref.set({ eventId: "e1", occurrenceDate: "2026-10-22", kind: "pickup", fromMemberId: "sara", toMemberId: "bengt", status: "pending" });
    const sent = await outbox(fid, "request");
    expect(sent?.memberIds).toEqual(["bengt"]);
    expect(sent?.devices).toBe(1);

    await ref.update({ status: "accepted", respondedBy: "bengt" });
    const answer = await outbox(fid, "answer");
    expect(answer?.memberIds).toEqual(["sara"]);
    expect(answer?.data.status).toBe("accepted");

    await db.doc(`families/${fid}/presence/sara`).set({ memberId: "sara", listId: "l1", until: new Date(Date.now() + 3_600_000).toISOString() });
    // Where the app writes items: under their list (the trigger used to listen to a path nothing is written to).
    await db.doc(`families/${fid}/shoppingLists/l1/items/i1`).set({ listId: "l1", name: "Mjölk", addedBy: "bengt" });
    const item = await outbox(fid, "shopping");
    expect(item?.memberIds).toEqual(["sara"]);
    expect(item?.data.item).toBe("Mjölk");

    await db.doc(`families/${fid}/sickDays/ella_2026-10-22`).set({ childId: "ella", date: "2026-10-22", reportedBy: "sara", updatedBy: `u-sara-${RUN}` });
    const sick = await outbox(fid, "sick");
    expect(sick?.memberIds).toEqual(["bengt"]);
    expect(sick?.data.childId).toBe("ella");

    await db.doc(`families/${fid}/handoffReceipts/e1_2026-10-22_pickup`).set({
      eventId: "e1", occurrenceDate: "2026-10-22", kind: "pickup", childIds: ["ella"], doneBy: "bengt", updatedBy: `u-bengt-${RUN}`,
    });
    const picked = await outbox(fid, "pickedUp");
    expect(picked?.memberIds).toEqual(["sara"]);
  });
});
