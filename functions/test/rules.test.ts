import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, describe, it } from "vitest";
import { assertFails, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-pusslet",
    firestore: { rules: readFileSync(resolve(process.cwd(), "../firestore.rules"), "utf8") },
  });
});

afterAll(async () => {
  await env.cleanup();
});

describe("baseline", () => {
  it("denies unauthenticated reads and writes", async () => {
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, "families/f1")));
    await assertFails(setDoc(doc(db, "families/f1"), { name: "x" }));
  });
});
