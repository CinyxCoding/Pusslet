// "The other home" for manual end-to-end tests of sharing with the other home (ADR-030) against the Local Emulator Suite.
//
// Acts as Lina, Ella's mum in another home: has her own family, answers the code the demo app showed under
// Familjen → Andra hemmet (the security rules are enforced, exactly as for a device), then adds and lists Ella's events
// so you can watch them appear on the emulator. Usage (emulators running, demo app has started a share):
//
//   node scripts/other-home.mjs join <CODE>          # Lina's family + joining the shared space
//   node scripts/other-home.mjs add "Simning" 17:00 18:00
//   node scripts/other-home.mjs show                 # what Lina can and cannot see
import { readFileSync } from "node:fs";
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { arrayUnion, collection, doc, getDoc, getDocs, serverTimestamp, setDoc, updateDoc, writeBatch } from "firebase/firestore";

const PROJECT = "demo-pusslet";
const LINA_UID = "lina-phone";
const LINA_MEMBER = "m-lina-e2e";
const HOME = "berg-e2e";

const env = await initializeTestEnvironment({
  projectId: PROJECT,
  firestore: { host: "127.0.0.1", port: 8080, rules: readFileSync(new URL("../../firestore.rules", import.meta.url), "utf8") },
});

const stamp = () => ({ updatedBy: LINA_UID, updatedAt: serverTimestamp() });
const lina = env.authenticatedContext(LINA_UID).firestore();
const today = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Europe/Stockholm" });

/** The space Lina belongs to (from her own family's list). */
async function space() {
  const home = await getDoc(doc(lina, `families/${HOME}`));
  const id = (home.get("sharedSpaces") ?? [])[0];
  if (!id) throw new Error("Lina has not joined yet: run join <CODE> first.");
  return id;
}

const [command, ...args] = process.argv.slice(2);

switch (command) {
  case "join": {
    const code = String(args[0] ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
    // Lina's own family, as her phone's onboarding would create it.
    const own = writeBatch(lina);
    own.set(doc(lina, `families/${HOME}`), { name: "Familjen Berg", createdBy: LINA_UID, ...stamp() });
    own.set(doc(lina, `families/${HOME}/access/${LINA_UID}`), { memberId: LINA_MEMBER, role: "parent" });
    await own.commit();
    await setDoc(doc(lina, `families/${HOME}/members/${LINA_MEMBER}`), { id: LINA_MEMBER, displayName: "Lina", uid: LINA_UID, ...stamp() });

    const invite = await getDoc(doc(lina, `invites/${code}`));
    if (!invite.exists() || invite.get("shared") !== true) throw new Error(`No other-home invite with code ${code}`);
    const spaceId = invite.get("familyId");
    console.log(`${invite.get("fromName")} wants to share ${(invite.get("children") ?? []).map((c) => c.name).join(", ")}`);
    const join = writeBatch(lina);
    join.set(doc(lina, `families/${spaceId}/access/${LINA_UID}`), { memberId: LINA_MEMBER, role: "parent", inviteCode: code });
    join.set(doc(lina, `families/${spaceId}/members/${LINA_MEMBER}`), { id: LINA_MEMBER, displayName: "Lina", uid: LINA_UID, ...stamp() });
    join.set(doc(lina, `users/${LINA_UID}`), { familyIds: [HOME, spaceId] }, { merge: true });
    await join.commit();
    await updateDoc(doc(lina, `families/${spaceId}`), { familyIds: arrayUnion(HOME), ...stamp() });
    await updateDoc(doc(lina, `families/${HOME}`), { sharedSpaces: arrayUnion(spaceId), ...stamp() });
    console.log(`Lina joined space ${spaceId}`);
    break;
  }
  case "add": {
    const [title = "Simning", from = "17:00", to = "18:00"] = args;
    const spaceId = await space();
    const children = await getDocs(collection(lina, `families/${spaceId}/children`));
    const id = `e2e-${Date.now()}`;
    await setDoc(doc(lina, `families/${spaceId}/events/${id}`), {
      id,
      title,
      start: `${today()}T${from}`,
      end: `${today()}T${to}`,
      timeZoneId: "Europe/Stockholm",
      participantIds: children.docs.map((d) => d.id),
      pickUp: { personId: LINA_MEMBER },
      ...stamp(),
    });
    console.log(`Added ${title} (${id}) for ${children.docs.map((d) => d.get("firstName")).join(", ")}`);
    break;
  }
  case "show": {
    const spaceId = await space();
    const s = await getDoc(doc(lina, `families/${spaceId}`));
    console.log("space", { name: s.get("name"), shared: s.get("shared"), homes: s.get("familyIds") });
    for (const c of ["children", "members", "custodySchedules", "events", "packingRules"]) {
      const snap = await getDocs(collection(lina, `families/${spaceId}/${c}`));
      const live = snap.docs.filter((d) => !d.get("deletedAt"));
      console.log(c, live.map((d) => d.get("title") ?? d.get("firstName") ?? d.get("displayName") ?? d.get("item") ?? d.id));
    }
    const homes = (s.get("familyIds") ?? []).filter((id) => id !== HOME);
    for (const other of homes) {
      try {
        await getDocs(collection(lina, `families/${other}/events`));
        console.log(`PROBLEM: Lina can read ${other}'s own events`);
      } catch {
        console.log(`ok: Lina cannot read the other home's own events (${other})`);
      }
    }
    break;
  }
  default:
    console.log("Usage: other-home.mjs join <CODE> | add <title> <HH:MM> <HH:MM> | show");
}
await env.cleanup();
