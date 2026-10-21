/**
 * Shared set-up, imported first by index.ts so the Admin SDK and the global options exist before any function is defined.
 * All functions run in the EU (europe-north1, Finland) next to the Firestore EU location.
 */
import { setGlobalOptions } from "firebase-functions/v2";
import { initializeApp } from "firebase-admin/app";

initializeApp();

export const REGION = "europe-north1";

setGlobalOptions({
  region: REGION,
  maxInstances: 10,
  memory: "256MiB",
});
