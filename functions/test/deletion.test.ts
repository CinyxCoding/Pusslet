// End-to-end tests of the GDPR callables (src/privacy.ts) through the Functions emulator, with real ID tokens from the Auth
// emulator and the data seeded with the Admin SDK. Run with `npm test` (functions, firestore and auth emulators).
// Every test seeds its own ids, so a shared emulator with the demo app's data is never touched.
import { type App, deleteApp, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { type Firestore, Timestamp, getFirestore } from "firebase-admin/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const PROJECT = process.env.GCLOUD_PROJECT ?? "demo-pusslet";
const REGION = "europe-north1";
const RUN = Math.random().toString(36).slice(2, 8);

let app: App;
let db: Firestore;
let functionsOrigin: string;
let counter = 0;

interface CallResult {
  result?: { status: string; familiesDeleted?: number; familiesLeft?: number };
  error?: { status: string; message: string; details?: { reason?: string; familyId?: string } };
}

beforeAll(async () => {
  const hub = process.env.FIREBASE_EMULATOR_HUB;
  if (!hub || !process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_AUTH_EMULATOR_HOST) {
    throw new Error("The deletion tests need the functions, firestore and auth emulators: run `npm test`.");
  }
  const running = (await (await fetch(`http://${hub}/emulators`)).json()) as Record<string, { host: string; port: number }>;
  if (!running.functions) throw new Error("The functions emulator is not running: run `npm test`.");
  functionsOrigin = `http://${running.functions.host}:${running.functions.port}`;
  app = initializeApp({ projectId: PROJECT }, `deletion-${RUN}`);
  db = getFirestore(app);
});

afterAll(async () => {
  if (app) await deleteApp(app);
});

async function idToken(uid: string): Promise<string> {
  await getAuth(app)
    .createUser({ uid })
    .catch((e: { code?: string }) => {
      if (e.code !== "auth/uid-already-exists") throw e;
    });
  const custom = await getAuth(app).createCustomToken(uid);
  const res = await fetch(
    `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=demo-key`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token: custom, returnSecureToken: true }) },
  );
  const body = (await res.json()) as { idToken?: string };
  if (!body.idToken) throw new Error(`No ID token for ${uid}: ${JSON.stringify(body)}`);
  return body.idToken;
}

/** The callable protocol, as the Android SDK speaks it. */
async function call(name: string, data: unknown, token?: string): Promise<CallResult> {
  const res = await fetch(`${functionsOrigin}/${PROJECT}/${REGION}/${name}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ data }),
  });
  return (await res.json()) as CallResult;
}

async function exists(path: string): Promise<boolean> {
  return (await db.doc(path).get()).exists;
}

async function authUserExists(uid: string): Promise<boolean> {
  return getAuth(app)
    .getUser(uid)
    .then(() => true)
    .catch((e: { code?: string }) => {
      if (e.code === "auth/user-not-found") return false;
      throw e;
    });
}

/**
 * Familjen Lind: Sara (parent, also alone in a second family), morfar Bengt (caregiver), Johan (a person without an
 * account), data with a nested list, and the top-level documents that point at the family.
 */
async function seedLind() {
  const n = `${RUN}-${++counter}`;
  const w = {
    fid: `lind-${n}`,
    other: `berg-${n}`,
    sara: `sara-${n}`,
    bengt: `bengt-${n}`,
    johan: `johan-${n}`,
    invite: `INV${n}`,
    otherInvite: `INVB${n}`,
    saraFeed: `feed-sara-${n}-xxxxxxxxxxxx`,
    bengtFeed: `feed-bengt-${n}-xxxxxxxxxxx`,
    share: `share-${n}-xxxxxxxxxxxxxxxxx`,
  };
  const stamp = { updatedAt: Timestamp.now(), updatedBy: w.sara };
  const batch = db.batch();
  const f = `families/${w.fid}`;
  batch.set(db.doc(f), { name: "Familjen Lind", createdBy: w.sara, ...stamp });
  batch.set(db.doc(`${f}/access/${w.sara}`), { memberId: "m-sara", role: "parent" });
  batch.set(db.doc(`${f}/access/${w.bengt}`), { memberId: "m-bengt", role: "caregiver", inviteCode: w.invite });
  batch.set(db.doc(`${f}/members/m-sara`), { displayName: "Sara", uid: w.sara, role: "parent", ...stamp });
  batch.set(db.doc(`${f}/members/m-bengt`), { displayName: "Morfar Bengt", uid: w.bengt, role: "caregiver", ...stamp });
  batch.set(db.doc(`${f}/members/m-johan`), { displayName: "Johan", role: "parent", ...stamp });
  batch.set(db.doc(`${f}/events/e1`), { title: "Hämtning", start: "2026-10-20T16:00", end: "2026-10-20T16:30", ...stamp });
  batch.set(db.doc(`${f}/shoppingLists/l1`), { name: "Veckohandling", ...stamp });
  batch.set(db.doc(`${f}/shoppingLists/l1/items/i1`), { name: "Mjölk", listId: "l1", ...stamp });
  batch.set(db.doc(`${f}/dietProfiles/ella`), { personId: "ella", allergies: ["nuts"], ...stamp });
  batch.set(db.doc(`invites/${w.invite}`), { familyId: w.fid, role: "caregiver", createdBy: w.sara, expiresAt: Timestamp.now() });
  batch.set(db.doc(`invites/${w.otherInvite}`), { familyId: w.other, role: "parent", createdBy: w.sara, expiresAt: Timestamp.now() });
  batch.set(db.doc(`calendarFeeds/${w.saraFeed}`), { familyId: w.fid, createdBy: w.sara });
  batch.set(db.doc(`calendarFeeds/${w.bengtFeed}`), { familyId: w.fid, createdBy: w.bengt });
  batch.set(db.doc(`handoverShares/${w.share}`), { familyId: w.fid, createdBy: w.sara, expiresAt: Timestamp.now() });
  batch.set(db.doc(`families/${w.other}`), { name: "Familjen Berg", createdBy: w.sara, ...stamp });
  batch.set(db.doc(`families/${w.other}/access/${w.sara}`), { memberId: "m-sara-b", role: "parent" });
  batch.set(db.doc(`families/${w.other}/events/e1`), { title: "Simning", start: "2026-10-21T17:00", end: "2026-10-21T18:00", ...stamp });
  batch.set(db.doc(`users/${w.sara}`), { familyIds: [w.fid, w.other] });
  batch.set(db.doc(`users/${w.sara}/devices/d1`), { fcmToken: "token-sara", platform: "android" });
  batch.set(db.doc(`users/${w.bengt}`), { familyIds: [w.fid] });
  batch.set(db.doc(`users/${w.bengt}/devices/d2`), { fcmToken: "token-bengt", platform: "android" });
  await batch.commit();
  return { ...w, saraToken: await idToken(w.sara), bengtToken: await idToken(w.bengt) };
}

/** Johan gets an account and joins as a second parent. */
async function johanJoins(w: Awaited<ReturnType<typeof seedLind>>): Promise<string> {
  const batch = db.batch();
  batch.set(db.doc(`families/${w.fid}/access/${w.johan}`), { memberId: "m-johan", role: "parent" });
  batch.set(db.doc(`families/${w.fid}/members/m-johan`), { uid: w.johan }, { merge: true });
  batch.set(db.doc(`users/${w.johan}`), { familyIds: [w.fid] });
  await batch.commit();
  return idToken(w.johan);
}

describe("deleteFamily", () => {
  it("needs a signed-in caller and a valid family id", async () => {
    const w = await seedLind();
    expect((await call("deleteFamily", { familyId: w.fid })).error).toMatchObject({ status: "UNAUTHENTICATED", message: "not-signed-in" });
    expect((await call("deleteFamily", {}, w.saraToken)).error).toMatchObject({ status: "INVALID_ARGUMENT" });
    expect((await call("deleteFamily", { familyId: "../users/x" }, w.saraToken)).error).toMatchObject({ status: "INVALID_ARGUMENT" });
    expect(await exists(`families/${w.fid}`)).toBe(true);
  });

  it("is only for the family's parents", async () => {
    const w = await seedLind();
    expect((await call("deleteFamily", { familyId: w.fid }, w.bengtToken)).error).toMatchObject({
      status: "PERMISSION_DENIED",
      message: "parents-only",
    });
    const stranger = await idToken(`eve-${RUN}-${counter}`);
    expect((await call("deleteFamily", { familyId: w.fid }, stranger)).error).toMatchObject({
      status: "PERMISSION_DENIED",
      message: "not-a-member",
    });
    expect(await exists(`families/${w.fid}/events/e1`)).toBe(true);
    expect(await exists(`deletedFamilies/${w.fid}`)).toBe(false);
  });

  it("deletes the family, everything that points at it and every link to it, and nothing else", async () => {
    const w = await seedLind();

    const res = await call("deleteFamily", { familyId: w.fid }, w.saraToken);

    expect(res.result).toEqual({ status: "deleted" });
    expect(await exists(`families/${w.fid}`)).toBe(false);
    expect(await db.doc(`families/${w.fid}`).listCollections()).toEqual([]);
    expect(await exists(`families/${w.fid}/shoppingLists/l1/items/i1`)).toBe(false);
    for (const path of [`invites/${w.invite}`, `calendarFeeds/${w.saraFeed}`, `calendarFeeds/${w.bengtFeed}`, `handoverShares/${w.share}`]) {
      expect(await exists(path), path).toBe(false);
    }
    // Every account loses the link; the profiles, the devices and the accounts themselves stay.
    expect((await db.doc(`users/${w.sara}`).get()).get("familyIds")).toEqual([w.other]);
    expect((await db.doc(`users/${w.bengt}`).get()).get("familyIds")).toEqual([]);
    expect(await exists(`users/${w.bengt}/devices/d2`)).toBe(true);
    expect(await authUserExists(w.sara)).toBe(true);
    expect(await authUserExists(w.bengt)).toBe(true);
    // Sara's other family is untouched.
    expect(await exists(`families/${w.other}/events/e1`)).toBe(true);
    expect(await exists(`invites/${w.otherInvite}`)).toBe(true);
    // The tombstone holds nothing but the time.
    expect(Object.keys((await db.doc(`deletedFamilies/${w.fid}`).get()).data() ?? {})).toEqual(["deletedAt"]);
  });

  it("can be called again, also by someone who has already lost access", async () => {
    const w = await seedLind();
    await call("deleteFamily", { familyId: w.fid }, w.saraToken);

    expect((await call("deleteFamily", { familyId: w.fid }, w.saraToken)).result).toEqual({ status: "already-deleted" });
    expect((await call("deleteFamily", { familyId: w.fid }, w.bengtToken)).result).toEqual({ status: "already-deleted" });
  });

  it("finishes a deletion that was interrupted", async () => {
    const w = await seedLind();
    await db.doc(`deletedFamilies/${w.fid}`).set({ deletedAt: Timestamp.now() });
    await db.doc(`families/${w.fid}/access/${w.sara}`).delete();

    expect((await call("deleteFamily", { familyId: w.fid }, w.saraToken)).result).toEqual({ status: "already-deleted" });
    expect(await exists(`families/${w.fid}/events/e1`)).toBe(false);
    expect(await exists(`invites/${w.invite}`)).toBe(false);
    expect((await db.doc(`users/${w.bengt}`).get()).get("familyIds")).toEqual([]);
  });
});

describe("deleting a home that shares a space with the other home (ADR-030)", () => {
  it("takes the home out of the space and leaves the other home's data, but never touches an unconfirmed space", async () => {
    const w = await seedLind();
    const space = `space-${w.fid}`;
    const stranger = `stranger-${w.fid}`;
    const stamp = { updatedAt: Timestamp.now(), updatedBy: w.sara };
    const batch = db.batch();
    batch.set(db.doc(`families/${w.fid}`), { sharedSpaces: [space, stranger] }, { merge: true });
    batch.set(db.doc(`families/${space}`), { name: "Ella", shared: true, familyIds: [w.fid, "berg-home"], createdBy: w.sara, ...stamp });
    batch.set(db.doc(`families/${space}/access/${w.sara}`), { memberId: "m-sara", role: "parent" });
    batch.set(db.doc(`families/${space}/access/lina-${w.fid}`), { memberId: "m-lina", role: "parent" });
    batch.set(db.doc(`families/${space}/members/m-sara`), { displayName: "Sara", ...stamp });
    batch.set(db.doc(`families/${space}/events/e1`), { title: "Fotboll", start: "2026-10-20T17:00", end: "2026-10-20T18:00", ...stamp });
    batch.set(db.doc(`families/${stranger}`), { name: "Annan", shared: true, familyIds: ["someone"], createdBy: "x", ...stamp });
    batch.set(db.doc(`families/${stranger}/access/${w.sara}`), { memberId: "m-sara", role: "parent" });
    await batch.commit();

    expect((await call("deleteFamily", { familyId: w.fid }, w.saraToken)).result).toEqual({ status: "deleted" });
    expect(await exists(`families/${w.fid}`)).toBe(false);
    expect(await exists(`families/${space}/events/e1`)).toBe(true);
    expect(await exists(`families/${space}/access/${w.sara}`)).toBe(false);
    expect(await exists(`families/${space}/members/m-sara`)).toBe(false);
    expect(await exists(`families/${space}/access/lina-${w.fid}`)).toBe(true);
    expect((await db.doc(`families/${space}`).get()).get("familyIds")).toEqual(["berg-home"]);
    expect(await exists(`families/${stranger}/access/${w.sara}`)).toBe(true);
  });

  it("deletes a space when the leaving home was the only one with parents there", async () => {
    const w = await seedLind();
    const space = `space2-${w.fid}`;
    const stamp = { updatedAt: Timestamp.now(), updatedBy: w.sara };
    const batch = db.batch();
    batch.set(db.doc(`families/${w.fid}`), { sharedSpaces: [space] }, { merge: true });
    batch.set(db.doc(`families/${space}`), { name: "Ella", shared: true, familyIds: [w.fid], createdBy: w.sara, ...stamp });
    batch.set(db.doc(`families/${space}/access/${w.sara}`), { memberId: "m-sara", role: "parent" });
    batch.set(db.doc(`families/${space}/events/e1`), { title: "Fotboll", start: "2026-10-20T17:00", end: "2026-10-20T18:00", ...stamp });
    await batch.commit();

    await call("deleteFamily", { familyId: w.fid }, w.saraToken);
    expect(await exists(`families/${space}`)).toBe(false);
    expect(await exists(`families/${space}/events/e1`)).toBe(false);
  });
});

describe("deleteAccount", () => {
  it("needs a signed-in caller", async () => {
    expect((await call("deleteAccount", {})).error).toMatchObject({ status: "UNAUTHENTICATED", message: "not-signed-in" });
  });

  it("lets a caregiver leave and keeps him in the family as a person without an account", async () => {
    const w = await seedLind();

    const res = await call("deleteAccount", { familyId: w.fid }, w.bengtToken);

    expect(res.result).toEqual({ status: "deleted", familiesDeleted: 0, familiesLeft: 1 });
    expect(await exists(`families/${w.fid}/access/${w.bengt}`)).toBe(false);
    const bengt = await db.doc(`families/${w.fid}/members/m-bengt`).get();
    expect(bengt.get("displayName")).toBe("Morfar Bengt");
    expect(bengt.get("uid")).toBeUndefined();
    expect(bengt.get("updatedBy")).toBe(w.bengt);
    expect(await exists(`users/${w.bengt}`)).toBe(false);
    expect(await exists(`users/${w.bengt}/devices/d2`)).toBe(false);
    expect(await authUserExists(w.bengt)).toBe(false);
    // His calendar feed link stops working, the family's own things stay.
    expect(await exists(`calendarFeeds/${w.bengtFeed}`)).toBe(false);
    expect(await exists(`calendarFeeds/${w.saraFeed}`)).toBe(true);
    expect(await exists(`invites/${w.invite}`)).toBe(true);
    expect(await exists(`families/${w.fid}/events/e1`)).toBe(true);
    expect(await exists(`families/${w.fid}/access/${w.sara}`)).toBe(true);
  });

  it("refuses the last parent while others have accounts, and changes nothing anywhere", async () => {
    const w = await seedLind();

    const res = await call("deleteAccount", {}, w.saraToken);

    expect(res.error).toMatchObject({
      status: "FAILED_PRECONDITION",
      message: "last-parent",
      details: { reason: "last-parent", familyId: w.fid },
    });
    expect(await exists(`users/${w.sara}`)).toBe(true);
    expect(await exists(`families/${w.fid}/access/${w.sara}`)).toBe(true);
    // Sara is alone in her other family, which would be deleted, but the refusal comes first.
    expect(await exists(`families/${w.other}/events/e1`)).toBe(true);
    expect(await authUserExists(w.sara)).toBe(true);
  });

  it("lets one of two parents leave, and deletes a family where nobody else has an account", async () => {
    const w = await seedLind();
    const johan = await johanJoins(w);
    await db.doc(`families/${w.other}/access/${w.johan}`).set({ memberId: "m-johan-b", role: "parent" });
    await db.doc(`users/${w.johan}`).set({ familyIds: [w.fid, w.other] });
    // Johan leaves Lind (Sara stays parent there); the other family still has Sara, so he only leaves that one too.
    expect((await call("deleteAccount", {}, johan)).result).toEqual({ status: "deleted", familiesDeleted: 0, familiesLeft: 2 });
    expect((await db.doc(`families/${w.fid}/members/m-johan`).get()).get("uid")).toBeUndefined();

    // Bengt leaves Lind; now Sara is alone in both families, so deleting her account deletes both.
    await call("deleteAccount", {}, w.bengtToken);
    expect((await call("deleteAccount", {}, w.saraToken)).result).toEqual({ status: "deleted", familiesDeleted: 2, familiesLeft: 0 });
    expect(await exists(`families/${w.fid}`)).toBe(false);
    expect(await exists(`families/${w.other}/events/e1`)).toBe(false);
    expect(await exists(`deletedFamilies/${w.fid}`)).toBe(true);
    expect(await exists(`deletedFamilies/${w.other}`)).toBe(true);
    expect(await exists(`invites/${w.otherInvite}`)).toBe(false);
    expect(await authUserExists(w.sara)).toBe(false);
  });

  it("finds the family on the phone when the profile missed it", async () => {
    const w = await seedLind();
    await db.doc(`users/${w.bengt}`).set({ familyIds: [] });

    expect((await call("deleteAccount", { familyId: w.fid }, w.bengtToken)).result).toMatchObject({ familiesLeft: 1 });
    expect(await exists(`families/${w.fid}/access/${w.bengt}`)).toBe(false);
  });

  it("can be called again after the account is gone", async () => {
    const w = await seedLind();
    await call("deleteAccount", {}, w.bengtToken);

    expect((await call("deleteAccount", {}, w.bengtToken)).result).toEqual({ status: "deleted", familiesDeleted: 0, familiesLeft: 0 });
    expect(await exists(`families/${w.fid}/access/${w.sara}`)).toBe(true);
  });
});
