/**
 * Pusslet Cloud Functions. All functions run in the EU (europe-north1, Finland) next to the Firestore EU location.
 * No function calls any AI or LLM service (docs/DECISIONS.md, ADR-001).
 */
import { setGlobalOptions } from "firebase-functions/v2";
import { onRequest } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";

initializeApp();

export const REGION = "europe-north1";

setGlobalOptions({
  region: REGION,
  maxInstances: 10,
  memory: "256MiB",
});

/** Liveness probe used by the app's "Om data / status" view and by uptime checks. */
export const health = onRequest({ cors: false }, (_req, res) => {
  res.status(200).json({ ok: true, service: "pusslet-functions" });
});
