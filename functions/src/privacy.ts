/**
 * GDPR: delete a whole family, or one's own account, on the server. The app removes the local copy afterwards.
 * Everything is deleted recursively (subcollections included) and nothing about the family is logged.
 */
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore, type Firestore } from "firebase-admin/firestore";

/** Removes the family, its feed and share links and invites, and the family from each user's list. */
export async function deleteFamilyData(db: Firestore, familyId: string): Promise<void> {
  const family = db.doc(`families/${familyId}`);
  const access = await family.collection("access").get();
  const uids = access.docs.map((d) => d.id);
  for (const name of ["calendarFeeds", "handoverShares", "invites"]) {
    const linked = await db.collection(name).where("familyId", "==", familyId).get();
    const batch = db.batch();
    linked.forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  await db.recursiveDelete(family);
  await Promise.all(
    uids.map((uid) => db.doc(`users/${uid}`).set({ familyIds: FieldValue.arrayRemove(familyId) }, { merge: true })),
  );
}

/** "Radera familjen": parents only. Every member loses access; the data is gone for everyone. */
export const deleteFamily = onCall(async (request) => {
  const uid = request.auth?.uid;
  const familyId = String(request.data?.familyId ?? "");
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");
  if (!familyId) throw new HttpsError("invalid-argument", "familyId missing");
  const db = getFirestore();
  const access = await db.doc(`families/${familyId}/access/${uid}`).get();
  if (access.get("role") !== "parent") throw new HttpsError("permission-denied", "Only parents can delete the family");
  await deleteFamilyData(db, familyId);
  return { deleted: true };
});

/**
 * "Radera mitt konto": removes the caller's membership, person record, devices and sign-in. A family where the caller
 * was the last parent is deleted too – nobody would be able to manage it otherwise.
 */
export const deleteAccount = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first");
  const db = getFirestore();
  const user = await db.doc(`users/${uid}`).get();
  const familyIds: string[] = (user.get("familyIds") as string[] | undefined) ?? [];
  let familiesDeleted = 0;
  for (const familyId of familyIds) {
    const family = db.doc(`families/${familyId}`);
    const access = await family.collection("access").get();
    const mine = access.docs.find((d) => d.id === uid);
    if (!mine) continue;
    const otherParents = access.docs.filter((d) => d.id !== uid && d.get("role") === "parent");
    if (mine.get("role") === "parent" && otherParents.length === 0) {
      await deleteFamilyData(db, familyId);
      familiesDeleted++;
      continue;
    }
    const memberId = mine.get("memberId") as string | undefined;
    const batch = db.batch();
    batch.delete(mine.ref);
    if (memberId) batch.delete(family.collection("members").doc(memberId));
    await batch.commit();
  }
  await db.recursiveDelete(db.doc(`users/${uid}`));
  await getAuth().deleteUser(uid).catch((e: { code?: string }) => {
    if (e.code !== "auth/user-not-found") throw e;
  });
  return { familiesDeleted };
});
