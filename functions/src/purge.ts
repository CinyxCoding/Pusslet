/**
 * Removed things really go away (docs/PRIVACY.md, docs/DECISIONS.md ADR-046). The app only soft-deletes (`deletedAt`), so
 * the deletion reaches every phone through sync; until now the content stayed on the server until the family was deleted.
 *
 * Every night, for documents deleted more than [STRIP_AFTER_DAYS] days ago, everything but the deletion is removed: a
 * marker with `deletedAt`, `updatedAt`, `updatedBy` and `purgedAt` stays. `updatedAt` is kept as it was, so phones that
 * already know about the deletion are not sent the document again, while a phone that was offline the whole time still
 * learns that it is deleted when it syncs (a hard delete would leave the item on that phone forever, and its old edits
 * could recreate half a document). Markers are deleted for good after [REMOVE_AFTER_DAYS] days.
 *
 * Each run covers the time since the last run (`_maintenance/purge`, readable by no client), so a document is handled
 * once. Logs carry counts only.
 */
import * as logger from "firebase-functions/logger";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { type DocumentReference, FieldValue, type Firestore, type Query, Timestamp, getFirestore } from "firebase-admin/firestore";

export const STRIP_AFTER_DAYS = 30;
export const REMOVE_AFTER_DAYS = 365;
const DAY_MS = 24 * 60 * 60 * 1000;

/** Deletion times to handle: strip content for [stripFrom, stripTo), remove markers for [removeFrom, removeTo). */
export interface PurgeWindow {
  stripFrom: Timestamp;
  stripTo: Timestamp;
  removeFrom: Timestamp;
  removeTo: Timestamp;
}

export interface PurgeResult {
  families: number;
  stripped: number;
  removed: number;
}

/** The meta fields a marker keeps; everything else in a deleted document is content. */
const KEPT = ["deletedAt", "updatedAt", "updatedBy"];

/** Collections under a family that are not synced documents with soft delete. */
const SKIPPED = new Set(["access"]);

/** Collections whose documents hold a subcollection of synced documents (shopping items live under their list). */
const NESTED: Record<string, string> = { shoppingLists: "items" };

function deletedBetween(query: Query, from: Timestamp, to: Timestamp): Query {
  return query.where("deletedAt", ">=", from).where("deletedAt", "<", to);
}

/** Strips and removes in the given families. */
export async function purgeFamilies(db: Firestore, families: DocumentReference[], window: PurgeWindow): Promise<PurgeResult> {
  const writer = db.bulkWriter();
  let stripped = 0;
  let removed = 0;

  // What is old enough to be removed is only removed (a batch does not keep the order of two writes to one document).
  const stripFrom = window.stripFrom.toMillis() > window.removeTo.toMillis() ? window.stripFrom : window.removeTo;

  async function sweep(query: Query) {
    for (const doc of (await deletedBetween(query, stripFrom, window.stripTo).get()).docs) {
      const marker: Record<string, unknown> = Object.fromEntries(KEPT.filter((k) => doc.get(k) !== undefined).map((k) => [k, doc.get(k)]));
      marker.purgedAt = FieldValue.serverTimestamp();
      void writer.set(doc.ref, marker);
      stripped++;
    }
    for (const doc of (await deletedBetween(query, window.removeFrom, window.removeTo).get()).docs) {
      void writer.delete(doc.ref);
      removed++;
    }
  }

  for (const family of families) {
    for (const collection of await family.listCollections()) {
      if (SKIPPED.has(collection.id)) continue;
      await sweep(collection);
      const sub = NESTED[collection.id];
      if (sub) for (const parent of await collection.listDocuments()) await sweep(parent.collection(sub));
    }
  }
  await writer.close();
  return { families: families.length, stripped, removed };
}

/** The windows for a run at [now] that follows a run whose windows ended at [last] (nothing yet = from the start). */
export function windowsFor(now: Date, last?: { strippedBefore?: Timestamp; removedBefore?: Timestamp }): PurgeWindow {
  const stripTo = Timestamp.fromMillis(now.getTime() - STRIP_AFTER_DAYS * DAY_MS);
  const removeTo = Timestamp.fromMillis(now.getTime() - REMOVE_AFTER_DAYS * DAY_MS);
  return {
    stripFrom: last?.strippedBefore ?? Timestamp.fromMillis(0),
    stripTo,
    removeFrom: last?.removedBefore ?? Timestamp.fromMillis(0),
    removeTo,
  };
}

/** One nightly run over every family, continuing where the last run stopped. */
export async function purgeRun(db: Firestore, now: Date, state: DocumentReference = db.doc("_maintenance/purge")): Promise<PurgeResult> {
  const last = (await state.get()).data() as { strippedBefore?: Timestamp; removedBefore?: Timestamp } | undefined;
  const window = windowsFor(now, last);
  const families = await db.collection("families").listDocuments();
  const result = await purgeFamilies(db, families, window);
  await state.set({ strippedBefore: window.stripTo, removedBefore: window.removeTo, ranAt: FieldValue.serverTimestamp() });
  return result;
}

export const purgeDeleted = onSchedule({ schedule: "every day 03:30", timeZone: "Europe/Stockholm" }, async () => {
  const result = await purgeRun(getFirestore(), new Date());
  logger.info("Deleted documents purged", { ...result });
});
