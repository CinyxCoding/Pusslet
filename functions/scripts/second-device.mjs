// "Second phone" for manual end-to-end tests against the Local Emulator Suite.
//
// Acts as Johan in the demo family that the Android demo build created: joins through a real invite (the
// security rules are enforced, exactly as for a device), then writes and edits events so you can watch them
// appear on the emulator. Usage (emulators running, demo app started once):
//
//   node scripts/second-device.mjs join            # invite + join as Johan's phone
//   node scripts/second-device.mjs add "Tandläkare" 14:00 15:00
//   node scripts/second-device.mjs rename <eventId> "Ny titel"
//   node scripts/second-device.mjs show <eventId>
import { readFileSync } from "node:fs";
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, serverTimestamp, setDoc, Timestamp, writeBatch } from "firebase/firestore";

const PROJECT = "demo-pusslet";
const JOHAN_UID = "johan-phone";
const JOHAN_MEMBER = "m-johan";
const CODE = "E2EJXHANKQ";

const env = await initializeTestEnvironment({
  projectId: PROJECT,
  firestore: { host: "127.0.0.1", port: 8080, rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
});

async function demoFamily() {
  let found;
  await env.withSecurityRulesDisabled(async (ctx) => {
    const families = await ctx.firestore().collection("families").get();
    const family = families.docs.find((d) => d.get("name") === "Familjen Lind");
    if (family) found = { id: family.id, owner: family.get("createdBy") };
  });
  if (!found) throw new Error("No demo family in the emulator yet: start the demo app once.");
  return found;
}

const stamp = (uid) => ({ updatedBy: uid, updatedAt: serverTimestamp() });
const db = (uid) => env.authenticatedContext(uid).firestore();
const today = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Europe/Stockholm" });

const [command, ...args] = process.argv.slice(2);
const family = await demoFamily();

switch (command) {
  case "join": {
    // The owner's device would create this invite from the Invite screen (invites cannot be updated).
    const invite = doc(db(family.owner), `invites/${CODE}`);
    if (!(await getDoc(invite)).exists()) await setDoc(invite, {
      familyId: family.id,
      familyName: "Familjen Lind",
      role: "parent",
      memberId: JOHAN_MEMBER,
      createdBy: family.owner,
      expiresAt: Timestamp.fromMillis(Date.now() + 7 * 24 * 3600 * 1000),
    });
    const johan = db(JOHAN_UID);
    const batch = writeBatch(johan);
    batch.set(doc(johan, `families/${family.id}/access/${JOHAN_UID}`), { memberId: JOHAN_MEMBER, role: "parent", inviteCode: CODE });
    batch.set(doc(johan, `families/${family.id}/members/${JOHAN_MEMBER}`), { uid: JOHAN_UID, ...stamp(JOHAN_UID) }, { merge: true });
    batch.set(doc(johan, `users/${JOHAN_UID}`), { familyIds: [family.id] }, { merge: true });
    await batch.commit();
    console.log(`Joined ${family.id} as ${JOHAN_MEMBER}`);
    break;
  }
  case "add": {
    const [title = "Tandläkare Simon", start = "14:00", end = "15:00"] = args;
    const id = `e2e-${Date.now()}`;
    await setDoc(doc(db(JOHAN_UID), `families/${family.id}/events/${id}`), {
      id,
      title,
      start: `${today()}T${start}`,
      end: `${today()}T${end}`,
      allDay: false,
      timeZoneId: "Europe/Stockholm",
      participantIds: ["c-simon"],
      sharedWithCaregivers: true,
      createdBy: JOHAN_MEMBER,
      ...stamp(JOHAN_UID),
    });
    console.log(`Added ${id}`);
    break;
  }
  case "rename": {
    const [id, title] = args;
    await setDoc(doc(db(JOHAN_UID), `families/${family.id}/events/${id}`), { title, ...stamp(JOHAN_UID) }, { merge: true });
    console.log(`Renamed ${id}`);
    break;
  }
  case "show": {
    const snap = await getDoc(doc(db(JOHAN_UID), `families/${family.id}/events/${args[0]}`));
    console.log(JSON.stringify(snap.data(), null, 2));
    break;
  }
  default:
    console.log("Commands: join | add <title> <HH:MM> <HH:MM> | rename <eventId> <title> | show <eventId>");
}
await env.cleanup();
