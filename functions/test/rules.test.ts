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
  FieldPath,
  Timestamp,
  collection,
  deleteDoc,
  deleteField,
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
/** What the app does when it joins with a code: it uses the code up in the same batch (ADR-059). */
const useInvite = (uid: string, code: string) => ({ ref: `invites/${code}`, data: { usedBy: uid, usedAt: serverTimestamp() } });

/** Joins [FID] (or [fid]) with [code]: the access doc and the used-up code in one batch, as the app writes them. */
function joinWith(uid: string, access: Record<string, unknown>, code: string, fid: string = FID) {
  const f = db(uid);
  const batch = writeBatch(f);
  batch.set(doc(f, `families/${fid}/access/${uid}`), { ...access, inviteCode: code });
  const used = useInvite(uid, code);
  batch.update(doc(f, used.ref), used.data);
  return { f, batch };
}

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
    await setDoc(doc(f, `families/${FID}/shoppingLists/l1`), { name: "Veckohandling" });
    await setDoc(doc(f, `families/${FID}/icsSubscriptions/s1`), { name: "Skolan", url: "https://example.com/a.ics" });
    await setDoc(doc(f, `families/${FID}/dietProfiles/ella`), { childId: "ella", allergies: ["nuts"] });
    const request = { eventId: "shared", occurrenceDate: "2026-10-08", kind: "pickup", fromMemberId: "m-sara" };
    await setDoc(doc(f, `families/${FID}/handoffRequests/to-bengt`), { ...request, toMemberId: "m-bengt", status: "pending" });
    await setDoc(doc(f, `families/${FID}/handoffRequests/to-anyone`), { ...request, status: "pending" });
    await setDoc(doc(f, `families/${FID}/handoffRequests/to-johan`), { ...request, toMemberId: "m-johan", status: "pending" });
    await setDoc(doc(f, `families/${FID}/handoffRequests/answered`), {
      ...request, status: "accepted", respondedBy: "m-johan", respondedAt: "2026-10-07T18:00:00Z",
    });
    await setDoc(doc(f, `families/${FID}/handoffRequests/bengt-said-yes`), {
      ...request, toMemberId: "m-bengt", status: "accepted", respondedBy: "m-bengt", respondedAt: "2026-10-07T18:00:00Z",
    });
    await setDoc(doc(f, `families/${FID}/handoffRequests/bengt-applied`), {
      ...request, toMemberId: "m-bengt", status: "accepted", respondedBy: "m-bengt", respondedAt: "2026-10-07T18:00:00Z",
      appliedAt: "2026-10-07T18:05:00Z",
    });
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

  it("does not let a creator who was removed give themselves their access back", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/access/johan`), { memberId: "m-johan", role: "parent" });
    });
    await assertSucceeds(deleteDoc(doc(db("johan"), `families/${FID}/access/sara`)));
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/access/sara`), { memberId: "m-sara", role: "parent" }));
    await assertFails(getDocs(collection(db("sara"), `families/${FID}/events`)));
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
    const { f, batch } = joinWith("johan", { memberId: "m-johan", role: "parent" }, "ABCDEFGHJK");
    batch.set(doc(f, `families/${FID}/members/m-johan`), { displayName: "Johan", uid: "johan", ...stamp("johan") });
    await assertSucceeds(batch.commit());
    await assertSucceeds(getDocs(collection(f, `families/${FID}/events`)));
  });

  it("lets a code be used once, and only by someone joining with it (ADR-059)", async () => {
    // Joining without using the code up, or using it up without joining, is refused.
    await assertFails(setDoc(doc(db("morfar"), `families/${FID}/access/morfar`), { memberId: "m-morfar", role: "caregiver", inviteCode: "CAREGIVER2" }));
    const used = useInvite("mallory", "CAREGIVER2");
    await assertFails(updateDoc(doc(db("mallory"), used.ref), used.data));
    await assertSucceeds(joinWith("morfar", { memberId: "m-morfar", role: "caregiver" }, "CAREGIVER2").batch.commit());
    // The same code a second time: refused, also for the one who first sent it on.
    await assertFails(joinWith("mormor", { memberId: "m-mormor", role: "caregiver" }, "CAREGIVER2").batch.commit());
    await assertFails(updateDoc(doc(db("mormor"), used.ref), { usedBy: "mormor", usedAt: serverTimestamp() }));
  });

  it("lets an invited user claim the member a parent already added", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      const f = ctx.firestore();
      await setDoc(doc(f, `families/${FID}/members/m-johan`), { displayName: "Johan", updatedBy: "sara" });
      await setDoc(doc(f, "invites/CLAIMJXHAN"), {
        familyId: FID, role: "parent", memberId: "m-johan", createdBy: "sara", expiresAt: future(7),
      });
    });
    const { f, batch } = joinWith("johan", { memberId: "m-johan", role: "parent" }, "CLAIMJXHAN");
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
    const { f, batch } = joinWith("eve", { memberId: "m-sara", role: "parent" }, "CLAIMSARAX");
    batch.set(doc(f, `families/${FID}/members/m-sara`), { uid: "eve", ...stamp("eve") }, { merge: true });
    await assertFails(batch.commit());
  });

  it("does not let an invite for anyone take over someone already in the family", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/members/m-johan`), { displayName: "Johan", updatedBy: "sara" });
    });
    // With Bengt's code Eve may join as herself, not as Sara (her pushes, her name on requests) ...
    await assertFails(joinWith("eve", { memberId: "m-sara", role: "caregiver" }, "CAREGIVER2").batch.commit());
    // ... nor as Johan, whom Sara added before he had the app: that takes the invite made for him.
    await assertFails(joinWith("eve", { memberId: "m-johan", role: "parent" }, "ABCDEFGHJK").batch.commit());
    await assertSucceeds(joinWith("eve", { memberId: "m-eve", role: "caregiver" }, "CAREGIVER2").batch.commit());
  });

  it("shows a joiner with the role the invite gives, not one of their own choosing", async () => {
    const join = (role: string) => {
      const { f, batch } = joinWith("eve", { memberId: "m-eve", role: "caregiver" }, "CAREGIVER2");
      batch.set(doc(f, `families/${FID}/members/m-eve`), { displayName: "Eve", uid: "eve", role, ...stamp("eve") });
      return batch.commit();
    };
    await assertFails(join("parent"));
    await assertSucceeds(join("caregiver"));
  });

  it("lets someone who left join again as the same person", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/members/m-johan`), { displayName: "Johan", uid: "johan", updatedBy: "johan" });
    });
    await assertSucceeds(joinWith("johan", { memberId: "m-johan", role: "parent" }, "ABCDEFGHJK").batch.commit());
  });

  it("rejects joining with a different role than invited", async () => {
    await assertFails(joinWith("eve", { memberId: "m-eve", role: "parent" }, "CAREGIVER2").batch.commit());
  });

  it("rejects expired invites", async () => {
    await assertFails(joinWith("eve", { memberId: "m-eve", role: "parent" }, "EXPIREDXXX").batch.commit());
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

  it("lets a parent report a sick child, with a child and a date", async () => {
    const f = db("sara");
    const day = { childId: "ella", date: "2026-10-23", reportedBy: "m-sara", vab: true, homeWith: "m-sara", ...stamp("sara") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/sickDays/ella_2026-10-23`), day));
    await assertFails(setDoc(doc(f, `families/${FID}/sickDays/x`), { childId: "ella", vab: "ja", ...stamp("sara") }));
  });

  it("lets a parent keep the medicine log and courses, with sane values", async () => {
    const f = db("sara");
    const dose = { childId: "ella", at: "2026-10-23T06:15:00Z", medicine: "Alvedon", dose: "5 ml", by: "m-sara", ...stamp("sara") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/medicineLog/d1`), dose));
    const temp = { childId: "ella", at: "2026-10-23T06:20:00Z", temperature: 38.4, by: "m-sara", ...stamp("sara") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/medicineLog/t1`), temp));
    await assertFails(setDoc(doc(f, `families/${FID}/medicineLog/t2`), { ...temp, temperature: 380 }));
    const course = { childId: "ella", medicine: "Kåvepenin", dose: "5 ml", times: ["08:00", "20:00"], start: "2026-10-23", days: 10, ...stamp("sara") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/medicineCourses/c1`), course));
    await assertFails(setDoc(doc(f, `families/${FID}/medicineCourses/c2`), { ...course, days: 400 }));
  });

  it("lets a parent plan a school break per day", async () => {
    const f = db("sara");
    const plan = { eventId: "hl", start: "2026-10-26", days: { "2026-10-26": { care: "home", memberId: "m-sara" } }, ...stamp("sara") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/breakPlans/hl_2026-10-26`), plan));
    await assertFails(setDoc(doc(f, `families/${FID}/breakPlans/x`), { eventId: "hl", days: "måndag", ...stamp("sara") }));
  });

  it("allows soft delete but never hard delete", async () => {
    await assertSucceeds(updateDoc(doc(db("sara"), `families/${FID}/events/shared`), { deletedAt: serverTimestamp(), ...stamp("sara") }));
    await assertFails(deleteDoc(doc(db("sara"), `families/${FID}/events/private`)));
  });

  // ADR-046: the nightly purge leaves a marker (deletedAt, purgedAt); a document made again under the same id clears it,
  // the way the app writes (mergeFields with the meta fields deleted), and an old edit cannot fill a marker back in.
  it("lets a new document clear a purged marker, but not an old edit fill an event back in", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      const marker = { deletedAt: Timestamp.fromMillis(0), purgedAt: Timestamp.fromMillis(1), updatedAt: Timestamp.fromMillis(0) };
      await setDoc(doc(ctx.firestore(), `families/${FID}/mealPlans/2026-W43`), marker);
      await setDoc(doc(ctx.firestore(), `families/${FID}/events/purged`), marker);
      await setDoc(doc(ctx.firestore(), `families/${FID}/packingChecks/2026-10-09_ella`), marker);
    });
    const again = { week: { weekYear: 2026, week: 43 }, deletedAt: deleteField(), purgedAt: deleteField(), ...stamp("sara") };
    await assertSucceeds(setDoc(doc(db("sara"), `families/${FID}/mealPlans/2026-W43`), again, { mergeFields: Object.keys(again) }));
    const check = { date: "2026-10-09", childId: "ella", deletedAt: deleteField(), purgedAt: deleteField(), ...stamp("bengt") };
    await assertSucceeds(setDoc(doc(db("bengt"), `families/${FID}/packingChecks/2026-10-09_ella`), check, { mergeFields: Object.keys(check) }));
    const stale = { location: "Folktandvården", ...stamp("sara") };
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/events/purged`), stale, { mergeFields: Object.keys(stale) }));
  });

  it("keeps shopping items inside their list", async () => {
    await assertSucceeds(
      setDoc(doc(db("sara"), `families/${FID}/shoppingLists/l1/items/i1`), { name: "Mjölk", listId: "l1", ...stamp("sara") }),
    );
    await assertFails(
      setDoc(doc(db("sara"), `families/${FID}/shoppingLists/l1/items/i2`), { name: "Mjölk", listId: "other", ...stamp("sara") }),
    );
  });

  it("lets members edit their own member, but not hand it to another account or give it another role", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/members/m-bengt`), { displayName: "Bengt", uid: "bengt", role: "caregiver" });
    });
    const bengt = doc(db("bengt"), `families/${FID}/members/m-bengt`);
    await assertSucceeds(updateDoc(bengt, { displayName: "Morfar Bengt", ...stamp("bengt") }));
    await assertFails(updateDoc(bengt, { uid: "sara", ...stamp("bengt") }));
    await assertFails(updateDoc(bengt, { role: "parent", ...stamp("bengt") }));
    await assertFails(updateDoc(bengt, { displayName: "x".repeat(201), ...stamp("bengt") }));
    await assertSucceeds(updateDoc(doc(db("sara"), `families/${FID}/members/m-bengt`), { role: "parent", ...stamp("sara") }));
  });

  it("does not let members tamper with the membership index", async () => {
    await assertFails(updateDoc(doc(db("bengt"), `families/${FID}/access/bengt`), { role: "parent" }));
    await assertFails(getDocs(collection(db("stranger"), `families/${FID}/access`)));
  });
});

describe("recipe collections", () => {
  const path = `families/${FID}/recipeCollections/c1`;

  it("lets a parent create, fill per recipe, rename and soft delete a collection", async () => {
    const sara = db("sara");
    await assertSucceeds(setDoc(doc(sara, path), { id: "c1", name: "Kalas", recipeIds: { "r-tacos": true }, ...stamp("sara") }));
    await assertSucceeds(
      updateDoc(doc(sara, path), new FieldPath("recipeIds", "r-pesto"), true, "updatedAt", serverTimestamp(), "updatedBy", "sara"),
    );
    await assertSucceeds(updateDoc(doc(sara, path), { name: "Barnkalas", ...stamp("sara") }));
    await assertSucceeds(updateDoc(doc(sara, path), { deletedAt: serverTimestamp(), ...stamp("sara") }));
  });

  it("needs a short name and a map of recipes", async () => {
    const sara = db("sara");
    await assertFails(setDoc(doc(sara, path), { id: "c1", recipeIds: {}, ...stamp("sara") }));
    await assertFails(setDoc(doc(sara, path), { id: "c1", name: "x".repeat(201), ...stamp("sara") }));
    await assertFails(setDoc(doc(sara, path), { id: "c1", name: "Kalas", recipeIds: ["r-tacos"], ...stamp("sara") }));
  });

  it("lets caregivers read collections like recipes, but not change them", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), path), { id: "c1", name: "Kalas", recipeIds: { "r-tacos": true } });
    });
    await assertSucceeds(getDoc(doc(db("bengt"), path)));
    await assertSucceeds(getDocs(collection(db("bengt"), `families/${FID}/recipeCollections`)));
    await assertFails(updateDoc(doc(db("bengt"), path), { name: "Morfars", ...stamp("bengt") }));
    await assertFails(getDoc(doc(db("stranger"), path)));
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

  it("can see that a child is sick, but not report or change it", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/sickDays/ella_2026-10-22`), { childId: "ella", date: "2026-10-22" });
    });
    await assertSucceeds(getDoc(doc(db("bengt"), `families/${FID}/sickDays/ella_2026-10-22`)));
    await assertFails(
      setDoc(doc(db("bengt"), `families/${FID}/sickDays/simon_2026-10-22`), { childId: "simon", date: "2026-10-22", ...stamp("bengt") }),
    );
    await assertFails(updateDoc(doc(db("bengt"), `families/${FID}/sickDays/ella_2026-10-22`), { homeWith: "m-bengt", ...stamp("bengt") }));
  });

  it("neither reads nor writes the medicine log", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/medicineLog/d1`), { childId: "ella", medicine: "Alvedon" });
    });
    await assertFails(getDoc(doc(db("bengt"), `families/${FID}/medicineLog/d1`)));
    await assertFails(
      setDoc(doc(db("bengt"), `families/${FID}/medicineLog/d2`), { childId: "ella", medicine: "Alvedon", ...stamp("bengt") }),
    );
  });

  it("can see the wishlists and mark a wish bought, but not change or add wishes", async () => {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `families/${FID}/wishes/w1`), { childId: "ella", text: "Lego Friends", taken: false });
    });
    const f = db("bengt");
    await assertSucceeds(getDoc(doc(f, `families/${FID}/wishes/w1`)));
    await assertSucceeds(updateDoc(doc(f, `families/${FID}/wishes/w1`), { taken: true, takenBy: "m-bengt", ...stamp("bengt") }));
    await assertFails(updateDoc(doc(f, `families/${FID}/wishes/w1`), { text: "Ponny", ...stamp("bengt") }));
    await assertFails(setDoc(doc(f, `families/${FID}/wishes/w2`), { childId: "ella", text: "Cykel", ...stamp("bengt") }));
    await assertSucceeds(setDoc(doc(db("sara"), `families/${FID}/wishes/w2`), { childId: "ella", text: "Cykel", ...stamp("sara") }));
    await assertFails(setDoc(doc(db("sara"), `families/${FID}/wishes/w3`), { childId: "ella", text: "x".repeat(301), ...stamp("sara") }));
  });

  it("can tick off his own pick-up, not one in someone else's name", async () => {
    const f = db("bengt");
    const receipt = { eventId: "shared", occurrenceDate: "2026-10-08", kind: "pickup", childIds: ["ella"], doneBy: "m-bengt", ...stamp("bengt") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/handoffReceipts/shared_2026-10-08_pickup`), receipt));
    await assertSucceeds(getDoc(doc(f, `families/${FID}/handoffReceipts/shared_2026-10-08_pickup`)));
    await assertFails(
      setDoc(doc(f, `families/${FID}/handoffReceipts/shared_2026-10-09_pickup`), { ...receipt, occurrenceDate: "2026-10-09", doneBy: "m-sara" }),
    );
  });

  it("cannot read allergies and diets", async () => {
    await assertFails(getDoc(doc(db("bengt"), `families/${FID}/dietProfiles/ella`)));
    await assertSucceeds(getDoc(doc(db("sara"), `families/${FID}/dietProfiles/ella`)));
  });

  const yes = (by: string) => ({ status: "accepted", respondedBy: by, respondedAt: "2026-10-08T07:00:00Z", ...stamp("bengt") });

  it("can answer hand-off requests to him or to anyone", async () => {
    const f = db("bengt");
    await assertSucceeds(updateDoc(doc(f, `families/${FID}/handoffRequests/to-bengt`), yes("m-bengt")));
    await assertSucceeds(updateDoc(doc(f, `families/${FID}/handoffRequests/to-anyone`), yes("m-bengt")));
  });

  it("can say no to a request to anyone with his own key only", async () => {
    const f = db("bengt");
    const ref = doc(f, `families/${FID}/handoffRequests/to-anyone`);
    await assertSucceeds(
      updateDoc(ref, new FieldPath("declines", "m-bengt"), "2026-10-08T07:00:00Z", "updatedAt", serverTimestamp(), "updatedBy", "bengt"),
    );
    await assertFails(
      updateDoc(ref, new FieldPath("declines", "m-johan"), "2026-10-08T07:00:00Z", "updatedAt", serverTimestamp(), "updatedBy", "bengt"),
    );
  });

  it("cannot answer for someone else, someone else's request or an answered one", async () => {
    const f = db("bengt");
    await assertFails(updateDoc(doc(f, `families/${FID}/handoffRequests/to-bengt`), yes("m-johan")));
    await assertFails(updateDoc(doc(f, `families/${FID}/handoffRequests/to-johan`), yes("m-bengt")));
    await assertFails(updateDoc(doc(f, `families/${FID}/handoffRequests/answered`), yes("m-bengt")));
  });

  it("cannot change what a request is about", async () => {
    const f = db("bengt");
    const ref = doc(f, `families/${FID}/handoffRequests/to-bengt`);
    await assertFails(updateDoc(ref, { ...yes("m-bengt"), kind: "dropoff" }));
    await assertFails(updateDoc(ref, { ...yes("m-bengt"), eventId: "private" }));
    await assertFails(updateDoc(ref, { ...yes("m-bengt"), toMemberId: null }));
    await assertFails(updateDoc(ref, { ...yes("m-bengt"), fromMemberId: "m-bengt" }));
  });

  it("can change his answer until a parent has written it in", async () => {
    const f = db("bengt");
    const reopen = { status: "pending", respondedBy: null, respondedAt: null, ...stamp("bengt") };
    await assertSucceeds(updateDoc(doc(f, `families/${FID}/handoffRequests/bengt-said-yes`), reopen));
    await assertFails(updateDoc(doc(f, `families/${FID}/handoffRequests/bengt-applied`), reopen));
  });

  it("can ask the family to take over, but only for himself", async () => {
    const f = db("bengt");
    const ask = { eventId: "shared", occurrenceDate: "2026-10-08", kind: "pickup", status: "pending", ...stamp("bengt") };
    await assertSucceeds(setDoc(doc(f, `families/${FID}/handoffRequests/cannot`), { ...ask, fromMemberId: "m-bengt" }));
    await assertFails(setDoc(doc(f, `families/${FID}/handoffRequests/cannot2`), { ...ask, fromMemberId: "m-sara" }));
    await assertFails(
      setDoc(doc(f, `families/${FID}/handoffRequests/cannot3`), { ...ask, fromMemberId: "m-bengt", toMemberId: "m-sara" }),
    );
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

describe("deleted families (tombstones from deleteFamily/deleteAccount)", () => {
  async function tombstone(fid: string) {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      await setDoc(doc(ctx.firestore(), `deletedFamilies/${fid}`), { deletedAt: Timestamp.now() });
    });
  }

  it("never lets anyone create a deleted family again, not even its creator", async () => {
    await tombstone("gone");
    const f = db("anna");
    const batch = writeBatch(f);
    batch.set(doc(f, "families/gone"), { name: "Familjen Berg", createdBy: "anna", ...stamp("anna") });
    batch.set(doc(f, "families/gone/access/anna"), { memberId: "m-anna", role: "parent" });
    await assertFails(batch.commit());
    await assertFails(setDoc(doc(f, "families/gone/access/anna"), { memberId: "m-anna", role: "parent" }));
    await assertFails(setDoc(doc(f, "families/gone/events/e1"), { title: "Tillbaka", start: "x", end: "y", ...stamp("anna") }));
  });

  it("lets a signed-in phone check its family's tombstone, but not list or write them", async () => {
    await tombstone("gone");
    await assertSucceeds(getDoc(doc(db("johan"), "deletedFamilies/gone")));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "deletedFamilies/gone")));
    await assertFails(getDocs(collection(db("johan"), "deletedFamilies")));
    await assertFails(setDoc(doc(db("sara"), `deletedFamilies/${FID}`), { deletedAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(db("johan"), "deletedFamilies/gone")));
  });
});

describe("sharing with the other home (ADR-056)", () => {
  const SPACE = "space-ella";

  /** Sara's home (lind) shares Ella with Lina's home (berg). Johan is Sara's partner, Bengt their helper. */
  async function seedSpace() {
    await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
      const f = ctx.firestore();
      await setDoc(doc(f, `families/${FID}/access/johan`), { memberId: "m-johan", role: "parent" });
      await setDoc(doc(f, `families/${SPACE}`), { name: "Ella", createdBy: "sara", shared: true, familyIds: [FID] });
      await setDoc(doc(f, `families/${SPACE}/access/sara`), { memberId: "m-sara", role: "parent", home: FID });
      await setDoc(doc(f, `families/${SPACE}/events/football`), { title: "Fotboll", start: "2026-10-13T17:30", end: "2026-10-13T18:30" });
      await setDoc(doc(f, "families/berg"), { name: "Hos mamma", createdBy: "lina" });
      await setDoc(doc(f, "families/berg/access/lina"), { memberId: "m-lina", role: "parent" });
      await setDoc(doc(f, "invites/OTHERHOME1"), { familyId: SPACE, role: "parent", createdBy: "sara", expiresAt: future(7), shared: true });
    });
  }

  async function linaJoins() {
    const { f, batch } = joinWith("lina", { memberId: "m-lina", role: "parent", home: "berg" }, "OTHERHOME1", SPACE);
    batch.set(doc(f, `families/${SPACE}/members/m-lina`), { displayName: "Lina", uid: "lina", ...stamp("lina") });
    await assertSucceeds(batch.commit());
  }

  it("lets the other home join only from a home where the joiner is a parent (ADR-059)", async () => {
    await seedSpace();
    await assertFails(joinWith("lina", { memberId: "m-lina", role: "parent" }, "OTHERHOME1", SPACE).batch.commit());
    await assertFails(joinWith("lina", { memberId: "m-lina", role: "parent", home: FID }, "OTHERHOME1", SPACE).batch.commit());
    await assertSucceeds(joinWith("lina", { memberId: "m-lina", role: "parent", home: "berg" }, "OTHERHOME1", SPACE).batch.commit());
  });

  it("lets each home manage only its own adults and its own place in the space (ADR-059)", async () => {
    await seedSpace();
    await linaJoins();
    const sara = db("sara");
    const lina = db("lina");
    await assertSucceeds(updateDoc(doc(lina, `families/${SPACE}`), { familyIds: [FID, "berg"], ...stamp("lina") }));
    // Sara's home can neither take Lina out, make her a helper nor take her home off the list ...
    await assertFails(deleteDoc(doc(sara, `families/${SPACE}/access/lina`)));
    await assertFails(updateDoc(doc(sara, `families/${SPACE}/access/lina`), { role: "caregiver" }));
    await assertFails(updateDoc(doc(sara, `families/${SPACE}`), { familyIds: [FID], ...stamp("sara") }));
    await assertFails(updateDoc(doc(lina, `families/${SPACE}`), { familyIds: ["berg"], ...stamp("lina") }));
    // ... but each home can leave itself.
    await assertSucceeds(updateDoc(doc(lina, `families/${SPACE}`), { familyIds: [FID], ...stamp("lina") }));
    await assertSucceeds(deleteDoc(doc(lina, `families/${SPACE}/access/lina`)));
  });

  it("lets only the home that added a shared cost change or remove it (ADR-059)", async () => {
    await seedSpace();
    await linaJoins();
    const sara = db("sara");
    const lina = db("lina");
    const cost = {
      title: "Vinterjacka", amountOre: 89900, date: "2026-10-20", paidByHome: FID, otherSharePercent: 50,
      addedByHome: FID, createdBy: "m-sara", ...stamp("sara"),
    };
    await assertSucceeds(setDoc(doc(sara, `families/${SPACE}/expenses/x1`), cost));
    // Lina cannot change or remove Sara's cost, nor add one in Sara's home's or Sara's name ...
    await assertFails(updateDoc(doc(lina, `families/${SPACE}/expenses/x1`), { amountOre: 100, ...stamp("lina") }));
    await assertFails(updateDoc(doc(lina, `families/${SPACE}/expenses/x1`), { deletedAt: serverTimestamp(), ...stamp("lina") }));
    await assertFails(setDoc(doc(lina, `families/${SPACE}/expenses/x2`), { ...cost, createdBy: "m-lina", ...stamp("lina") }));
    await assertFails(setDoc(doc(lina, `families/${SPACE}/expenses/x3`), { ...cost, addedByHome: "berg", ...stamp("lina") }));
    // ... while her own is hers to add, and Sara removes hers.
    await assertSucceeds(
      setDoc(doc(lina, `families/${SPACE}/expenses/x4`), { ...cost, addedByHome: "berg", createdBy: "m-lina", paidByHome: "berg", ...stamp("lina") }),
    );
    await assertSucceeds(updateDoc(doc(sara, `families/${SPACE}/expenses/x1`), { deletedAt: serverTimestamp(), ...stamp("sara") }));
  });

  it("lets the other home join with the invite and work on the children's things, but not see the rest", async () => {
    await seedSpace();
    await linaJoins();
    const f = db("lina");
    await assertSucceeds(getDoc(doc(f, `families/${SPACE}/events/football`)));
    await assertSucceeds(
      setDoc(doc(f, `families/${SPACE}/events/swim`), { title: "Simning", start: "2026-10-14T17:00", end: "2026-10-14T18:00", ...stamp("lina") }),
    );
    await assertSucceeds(
      updateDoc(doc(f, `families/${SPACE}`), { familyIds: [FID, "berg"], ...stamp("lina") }),
    );
    await assertFails(getDoc(doc(f, `families/${FID}/events/private`)));
    await assertFails(getDocs(collection(f, `families/${FID}/events`)));
  });

  it("lets the home's other parent join without an invite, as themselves, but not a helper or an outsider", async () => {
    await seedSpace();
    const join = (uid: string, memberId: string, via: string) => {
      const f = db(uid);
      const batch = writeBatch(f);
      batch.set(doc(f, `families/${SPACE}/access/${uid}`), { memberId, role: "parent", viaFamily: via });
      batch.set(doc(f, `families/${SPACE}/members/${memberId}`), { displayName: uid, uid, ...stamp(uid) });
      return batch.commit();
    };
    await assertFails(join("johan", "m-sara", FID));
    await assertFails(join("bengt", "m-bengt", FID));
    await assertFails(join("lina", "m-lina", "berg"));
    await assertSucceeds(join("johan", "m-johan", FID));
    await assertSucceeds(getDoc(doc(db("johan"), `families/${SPACE}/events/football`)));
  });

  it("keeps shared costs in the space, with a sane amount and split (ADR-057)", async () => {
    await seedSpace();
    await linaJoins();
    const cost = {
      title: "Vinterjacka", amountOre: 89900, date: "2026-10-20", paidByHome: FID, otherSharePercent: 50,
      addedByHome: FID, createdBy: "m-sara", ...stamp("sara"),
    };
    await assertSucceeds(setDoc(doc(db("sara"), `families/${SPACE}/expenses/x1`), cost));
    await assertSucceeds(getDoc(doc(db("lina"), `families/${SPACE}/expenses/x1`)));
    await assertFails(setDoc(doc(db("sara"), `families/${SPACE}/expenses/x2`), { ...cost, amountOre: -5 }));
    await assertFails(setDoc(doc(db("sara"), `families/${SPACE}/expenses/x3`), { ...cost, otherSharePercent: 150 }));
  });

  it("keeps a space to the children's collections and its flag fixed", async () => {
    await seedSpace();
    const f = db("sara");
    await assertFails(setDoc(doc(f, `families/${SPACE}/shoppingLists/l1`), { name: "Veckohandling", ...stamp("sara") }));
    await assertFails(setDoc(doc(f, `families/${SPACE}/recipes/r1`), { title: "Pannkakor", ...stamp("sara") }));
    await assertFails(setDoc(doc(f, `families/${SPACE}/shoppingLists/l1/items/i1`), { name: "Mjölk", listId: "l1", ...stamp("sara") }));
    await assertSucceeds(setDoc(doc(f, `families/${SPACE}/packingRules/gym`), { item: "Gympapåse", childId: "ella", ...stamp("sara") }));
    await assertFails(updateDoc(doc(f, `families/${SPACE}`), { shared: false, ...stamp("sara") }));
    await assertFails(updateDoc(doc(f, `families/${FID}`), { shared: true, ...stamp("sara") }));
  });

  it("lets a leaving home take itself out: the home, its adults and their access", async () => {
    await seedSpace();
    await linaJoins();
    const f = db("sara");
    await assertSucceeds(updateDoc(doc(f, `families/${SPACE}`), { familyIds: [], ...stamp("sara") }));
    await assertSucceeds(
      setDoc(doc(f, `families/${SPACE}/members/m-sara`), { displayName: "Sara", deletedAt: serverTimestamp(), ...stamp("sara") }, { merge: true }),
    );
    await assertSucceeds(deleteDoc(doc(f, `families/${SPACE}/access/sara`)));
    await assertFails(getDoc(doc(f, `families/${SPACE}/events/football`)));
    await assertSucceeds(getDoc(doc(db("lina"), `families/${SPACE}/events/football`)));
  });
});
