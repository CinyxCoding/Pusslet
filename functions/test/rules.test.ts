import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestContext,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
} from "firebase/firestore";

let env: RulesTestEnvironment;
const FID = "lind";

const stamp = (uid: string) => ({ updatedAt: serverTimestamp(), updatedBy: uid });
const db = (uid: string) => env.authenticatedContext(uid).firestore();
const future = (days: number) => Timestamp.fromMillis(Date.now() + days * 86_400_000);

/** Seeds Familjen Lind: Sara (parent), Bengt (caregiver), a shared and a private event, a list and a task. */
async function seed() {
  await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
    const f = ctx.firestore();
    await setDoc(doc(f, `families/${FID}`), { name: "Familjen Lind", createdBy: "sara" });
    await setDoc(doc(f, `families/${FID}/access/sara`), { memberId: "m-sara", role: "parent" });
    await setDoc(doc(f, `families/${FID}/access/bengt`), { memberId: "m-bengt", role: "caregiver" });
    await setDoc(doc(f, `families/${FID}/members/m-sara`), { displayName: "Sara", uid: "sara" });
    await setDoc(doc(f, `families/${FID}/events/shared`), {
      title: "Hämtning", start: "2026-10-08T16:00", end: "2026-10-08T16:30", sharedWithCaregivers: true,
    });
    await setDoc(doc(f, `families/${FID}/events/private`), {
      title: "Saras läkartid", start: "2026-10-08T09:00", end: "2026-10-08T10:00", sharedWithCaregivers: false,
    });
    await setDoc(doc(f, `families/${FID}/tasks/t1`), { title: "Hämta paket", sharedWithCaregivers: true, done: false });
    await setDoc(doc(f, `families/${FID}/wishes/w1`), { childId: "ella", text: "Lego Friends", taken: false });
    await setDoc(doc(f, `families/${FID}/shoppingLists/l1`), { name: "Veckohandling" });
    await setDoc(doc(f, `families/${FID}/icsSubscriptions/s1`), { name: "Skolan", url: "https://example.com/a.ics" });
    await setDoc(doc(f, "invites/ABCDEFGHJK"), { familyId: FID, role: "parent", createdBy: "sara", expiresAt: future(7) });
    await setDoc(doc(f, "invites/CAREGIVER2"), { familyId: FID, role: "caregiver", createdBy: "sara", expiresAt: future(7) });
    await setDoc(doc(f, "invites/EXPIREDXXX"), { familyId: FID, role: "parent", createdBy: "sara", expiresAt: future(-1) });
    await setDoc(doc(f, "users/sara"), { familyIds: [FID] });
  });
}

beforeAll(async () => {
  env = await initializeTestEnvironment({
    // Own project id: clearFirestore() between tests must never wipe the demo app's data on a shared emulator.
    projectId: "demo-pusslet-rules",
    firestore: { rules: readFileSync(resolve(process.cwd(), "../firestore.rules"), "utf8") },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await seed();
});

afterAll(async () => {
  await env.cleanup();
});

describe("authentication and membership", () => {
  it("denies unauthenticated access", async () => {
    const anon = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(anon, `families/${FID}`)));
    await assertFails(getDoc(doc(anon, `families/${FID}/events/shared`)));
  });

  it("denies non-members", async () => {
    await assertFails(getDoc(doc(db("stranger"), `families/${FID}`)));
    await assertFails(getDocs(collection(db("stranger"), `families/${FID}/events`)));
  });

  it("lets members read the family", async () => {
    await assertSucceeds(getDoc(doc(db("sara"), `families/${FID}`)));
    await assertSucceeds(getDocs(collection(db("sara"), `families/${FID}/events`)));
  });

  it("keeps user documents private", async () => {
    await assertSucceeds(getDoc(doc(db("sara"), "users/sara")));
    await assertFails(getDoc(doc(db("johan"), "users/sara")));
  });
});

describe("creating a family", () => {
  it("lets a signed-in user create a family and bootstrap themselves as parent", async () => {
    const f = db("anna");
    const batch = writeBatch(f);
    batch.set(doc(f, "families/new"), { name: "Familjen Berg", createdBy: "anna", ...stamp("anna") });
    batch.set(doc(f, "families/new/access/anna"), { memberId: "m-anna", role: "parent" });
    await assertSucceeds(batch.commit());
    await assertSucceeds(
      setDoc(doc(f, "families/new/members/m-anna"), { displayName: "Anna", uid: "anna", ...stamp("anna") }),
    );
  });

  it("does not let someone claim another user's family", async () => {
    await assertFails(setDoc(doc(db("anna"), "families/x"), { name: "X", createdBy: "sara", ...stamp("anna") }));
    await assertFails(setDoc(doc(db("johan"), `families/${FID}/access/johan`), { memberId: "m-j", role: "parent" }));
  });
});

describe("invites and joining", () => {
  it("lets a parent create an invite but not a caregiver", async () => {
    await assertSucceeds(
      setDoc(doc(db("sara"), "invites/QRSTUVWXYZ"), { familyId: FID, role: "caregiver", createdBy: "sara", expiresAt: future(7) }),
    );
    await assertFails(
      setDoc(doc(db("bengt"), "invites/MNPQRSTUVW"), { familyId: FID, role: "parent", createdBy: "bengt", expiresAt: future(7) }),
    );
  });

  it("rejects invites that live too long", async () => {
    await assertFails(
      setDoc(doc(db("sara"), "invites/QRSTUVWXYZ"), { familyId: FID, role: "parent", createdBy: "sara", expiresAt: future(60) }),
    );
  });

  it("lets anyone signed in read an invite by code but not list invites", async () => {
    await assertSucceeds(getDoc(doc(db("johan"), "invites/ABCDEFGHJK")));
    await assertFails(getDocs(collection(db("johan"), "invites")));
  });

  it("lets an invited user join with the invited role", async () => {
    const f = db("johan");
    const batch = writeBatch(f);
    batch.set(doc(f, `families/${FID}/access/johan`), { memberId: "m-johan", role: "parent", inviteCode: "ABCDEFGHJK" });
    batch.set(doc(f, `families/${FID}/members/m-johan`), { displayName: "Johan", uid: "johan", ...stamp("johan") });
    await assertSucceeds(batch.commit());
    await assertSucceeds(getDocs(collection(f, `families/${FID}/events`)));
  });

  it("lets an invited user claim the member a parent already added", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      const f = ctx.firestore();
      await setDoc(doc(f, `families/${FID}/members/m-johan`), { displayName: "Johan", updatedBy: "sara" });
      await setDoc(doc(f, "invites/CLAIMJXHAN"), {
        familyId: FID, role: "parent", memberId: "m-johan", createdBy: "sara", expiresAt: future(7),
      });
    });
    const f = db("johan");
    const batch = writeBatch(f);
    batch.set(doc(f, `families/${FID}/access/johan`), { memberId: "m-johan", role: "parent", inviteCode: "CLAIMJXHAN" });
    batch.set(doc(f, `families/${FID}/members/m-johan`), { uid: "johan", ...stamp("johan") }, { merge: true });
    await assertSucceeds(batch.commit());
  });

  it("does not let an invite take over a member linked to another account", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      const f = ctx.firestore();
      await setDoc(doc(f, "invites/CLAIMSARAX"), {
        familyId: FID, role: "parent", memberId: "m-sara", createdBy: "sara", expiresAt: future(7),
      });
    });
    const f = db("eve");
    const batch = writeBatch(f);
    batch.set(doc(f, `families/${FID}/access/eve`), { memberId: "m-sara", role: "parent", inviteCode: "CLAIMSARAX" });
    batch.set(doc(f, `families/${FID}/members/m-sara`), { uid: "eve", ...stamp("eve") }, { merge: true });
    await assertFails(batch.commit());
  });

  it("rejects joining with a different role than invited", async () => {
    await assertFails(
      setDoc(doc(db("eve"), `families/${FID}/access/eve`), { memberId: "m-eve", role: "parent", inviteCode: "CAREGIVER2" }),
    );
  });

  it("rejects expired invites", async () => {
    await assertFails(
      setDoc(doc(db("eve"), `families/${FID}/access/eve`), { memberId: "m-eve", role: "parent", inviteCode: "EXPIREDXXX" }),
    );
  });
});

describe("writing family data", () => {
  it("requires every write to be stamped by the caller with server time", async () => {
    const ev = { title: "Fotboll", start: "2026-10-13T17:30", end: "2026-10-13T18:30" };
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/events/e1`), ev));
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/events/e1`), { ...ev, ...stamp("johan") }));
    await assertSucceeds(setDoc(doc(db("sara"), `families/${FID}/events/e1`), { ...ev, ...stamp("sara") }));
  });

  it("validates field types and sizes", async () => {
    const tooLong = "x".repeat(301);
    await assertFails(
      setDoc(doc(db("sara"), `families/${FID}/events/e2`), { title: tooLong, start: "s", end: "e", ...stamp("sara") }),
    );
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/events/e3`), { title: 42, start: "s", end: "e", ...stamp("sara") }));
  });

  it("allows soft delete but never hard delete", async () => {
    await assertSucceeds(updateDoc(doc(db("sara"), `families/${FID}/events/shared`), { deletedAt: serverTimestamp(), ...stamp("sara") }));
    await assertFails(deleteDoc(doc(db("sara"), `families/${FID}/events/private`)));
  });

  it("keeps shopping items inside their list", async () => {
    await assertSucceeds(
      setDoc(doc(db("sara"), `families/${FID}/shoppingLists/l1/items/i1`), { name: "Mjölk", listId: "l1", ...stamp("sara") }),
    );
    await assertFails(
      setDoc(doc(db("sara"), `families/${FID}/shoppingLists/l1/items/i2`), { name: "Mjölk", listId: "other", ...stamp("sara") }),
    );
  });

  it("does not let members tamper with the membership index", async () => {
    await assertFails(updateDoc(doc(db("bengt"), `families/${FID}/access/bengt`), { role: "parent" }));
    await assertFails(getDocs(collection(db("stranger"), `families/${FID}/access`)));
  });
});

describe("caregivers (morfar)", () => {
  it("can read shared events but not private ones", async () => {
    const f = db("bengt");
    await assertSucceeds(getDoc(doc(f, `families/${FID}/events/shared`)));
    await assertFails(getDoc(doc(f, `families/${FID}/events/private`)));
  });

  it("must filter queries to shared events", async () => {
    const f = db("bengt");
    await assertSucceeds(getDocs(query(collection(f, `families/${FID}/events`), where("sharedWithCaregivers", "==", true))));
    await assertFails(getDocs(collection(f, `families/${FID}/events`)));
  });

  it("cannot see calendar subscriptions", async () => {
    await assertFails(getDocs(collection(db("bengt"), `families/${FID}/icsSubscriptions`)));
  });

  it("can tick off packing and shopping, but not create events", async () => {
    const f = db("bengt");
    await assertSucceeds(
      setDoc(doc(f, `families/${FID}/packingChecks/2026-10-08_ella`), { date: "2026-10-08", childId: "ella", checked: { "rule:r1": true }, ...stamp("bengt") }),
    );
    await assertSucceeds(
      setDoc(doc(f, `families/${FID}/shoppingLists/l1/items/i9`), { name: "Bananer", listId: "l1", ...stamp("bengt") }),
    );
    await assertFails(
      setDoc(doc(f, `families/${FID}/events/e9`), { title: "x", start: "s", end: "e", ...stamp("bengt") }),
    );
  });

  it("can see the wishlists and mark a wish bought, but not change or add wishes", async () => {
    const f = db("bengt");
    await assertSucceeds(getDoc(doc(f, `families/${FID}/wishes/w1`)));
    await assertSucceeds(updateDoc(doc(f, `families/${FID}/wishes/w1`), { taken: true, takenBy: "m-bengt", ...stamp("bengt") }));
    await assertFails(updateDoc(doc(f, `families/${FID}/wishes/w1`), { text: "Ponny", ...stamp("bengt") }));
    await assertFails(setDoc(doc(f, `families/${FID}/wishes/w2`), { childId: "ella", text: "Cykel", ...stamp("bengt") }));
    await assertSucceeds(setDoc(doc(db("sara"), `families/${FID}/wishes/w2`), { childId: "ella", text: "Cykel", ...stamp("sara") }));
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/wishes/w3`), { childId: "ella", text: "x".repeat(301), ...stamp("sara") }));
  });

  it("can mark a shared task as done but not rename it", async () => {
    const f = db("bengt");
    await assertSucceeds(updateDoc(doc(f, `families/${FID}/tasks/t1`), { done: true, doneBy: "m-bengt", ...stamp("bengt") }));
    await assertFails(updateDoc(doc(f, `families/${FID}/tasks/t1`), { title: "Annat", ...stamp("bengt") }));
  });
});

describe("server-only collections", () => {
  it("never lets clients read calendar feed tokens or handover shares", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), "calendarFeeds/tok"), { familyId: FID });
    });
    await assertFails(getDoc(doc(db("sara"), "calendarFeeds/tok")));
    await assertSucceeds(
      setDoc(doc(db("sara"), "calendarFeeds/abcdefghijklmnopqrstuvwxyz"), { familyId: FID, createdBy: "sara" }),
    );
    await assertFails(
      setDoc(doc(db("bengt"), "calendarFeeds/abcdefghijklmnopqrstuvwxy2"), { familyId: FID, createdBy: "bengt" }),
    );
  });
});
