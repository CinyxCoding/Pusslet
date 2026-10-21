/**
 * Pusslet Cloud Functions. All functions run in the EU (europe-north1, Finland) next to the Firestore EU location.
 * No function calls any AI or LLM service (docs/DECISIONS.md, ADR-001).
 */
import "./setup.js";
import { onRequest } from "firebase-functions/v2/https";

export { REGION } from "./setup.js";
export { deleteAccount, deleteFamily } from "./privacy.js";
export { familyCalendarFeed } from "./calendarFeed.js";
export { pushOnAnswer, pushOnRequest, pushOnShoppingItem } from "./push.js";

/** Liveness probe used by the app's "Om data / status" view and by uptime checks. */
export const health = onRequest({ cors: false }, (_req, res) => {
  res.status(200).json({ ok: true, service: "pusslet-functions" });
});
