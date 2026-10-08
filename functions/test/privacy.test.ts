/** deleteFamily / deleteAccount against the Firestore and Auth emulators (own project id, own family ids). */
import { beforeEach, describe, expect, it } from "vitest";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { deleteAccount, deleteFamily } from "../src/privacy.js";

if (getApps().length === 0) initializeApp({ projectId: "demo-pusslet-functions" });

const call = (fn: unknown, uid: string | null, data: Record<string, unknown> = {}) =>
  (fn as { run: (r: unknown) => Promise<Record<string, unknown>> }).run({ data, auth: uid ? { uid, token: {} } : undefined });

const db = () => getFirestore();

async function seedFamily(fid: string, people: { uid: string; memberId: string; role: string }[]) {
  const family = db().doc(`families/${fid}`);
  await family.set({ name: `Familjen ${fid}` });
  for (const p of people) {
    await family.collection("access").doc(p.uid).set({ memberId: p.memberId, role: p.role });
    await family.collection("members").doc(p.memberId).set({ displayName: p.memberId, uid: p.uid });
    await db().doc(`users/${p.uid}`).set({ familyIds: [fid] }, { merge: true });
    await db().doc(`users/${p.uid}/devices/d1`).set({ fcmToken: "fcm" });
  }
  await family.collection("events").doc("e1").set({ title: "Fotboll" });
  await family.collection("shoppingLists").doc("l1").collection("items").doc("i1").set({ name: "Mjölk" });
  await db().doc(`calendarFeeds/feed_${fid}_abcdefghijklmnopqrstuvwxyz`).set({ familyId: fid, createdBy: people[0].uid });
  await db().doc(`invites/INV${fid.slice(0, 7).toUpperCase()}`).set({ familyId: fid, role: "parent" });
}

const exists = async (path: string) => (await db().doc(path).get()).exists;

describe("deleteFamily", () => {
  beforeEach(() => seedFamily("delfam", [
    { uid: "del-sara", memberId: "m-sara", role: "parent" },
    { uid: "del-bengt", memberId: "m-bengt", role: "caregiver" },
  ]));

  it("is for parents only", async () => {
    await expect(call(deleteFamily, null, { familyId: "delfam" })).rejects.toThrow();
    await expect(call(deleteFamily, "del-bengt", { familyId: "delfam" })).rejects.toThrow();
    expect(await exists("families/delfam")).toBe(true);
  });

  it("removes everything, deep, and the links pointing at it", async () => {
    await call(deleteFamily, "del-sara", { familyId: "delfam" });
    expect(await exists("families/delfam")).toBe(false);
    expect(await exists("families/delfam/events/e1")).toBe(false);
    expect(await exists("families/delfam/shoppingLists/l1/items/i1")).toBe(false);
    expect(await exists("families/delfam/access/del-sara")).toBe(false);
    expect((await db().collection("calendarFeeds").where("familyId", "==", "delfam").get()).size).toBe(0);
    expect((await db().collection("invites").where("familyId", "==", "delfam").get()).size).toBe(0);
    expect((await db().doc("users/del-bengt").get()).get("familyIds")).toEqual([]);
  });
});

describe("deleteAccount", () => {
  it("leaves a family that still has another parent", async () => {
    await seedFamily("delacc", [
      { uid: "acc-sara", memberId: "m-sara", role: "parent" },
      { uid: "acc-johan", memberId: "m-johan", role: "parent" },
    ]);
    await getAuth().createUser({ uid: "acc-johan" }).catch(() => undefined);
    const result = await call(deleteAccount, "acc-johan");
    expect(result.familiesDeleted).toBe(0);
    expect(await exists("families/delacc")).toBe(true);
    expect(await exists("families/delacc/access/acc-johan")).toBe(false);
    expect(await exists("families/delacc/members/m-johan")).toBe(false);
    expect(await exists("families/delacc/access/acc-sara")).toBe(true);
    expect(await exists("users/acc-johan")).toBe(false);
    expect(await exists("users/acc-johan/devices/d1")).toBe(false);
    await expect(getAuth().getUser("acc-johan")).rejects.toThrow();
  });

  it("deletes the family when the last parent leaves", async () => {
    await seedFamily("delsolo", [{ uid: "solo-sara", memberId: "m-sara", role: "parent" }]);
    const result = await call(deleteAccount, "solo-sara");
    expect(result.familiesDeleted).toBe(1);
    expect(await exists("families/delsolo")).toBe(false);
  });
});
