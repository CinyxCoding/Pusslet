// Deletes one person's account when they ask by e-mail, without the app (docs/DELETE_ACCOUNT.md, docs/SETUP.md 2.7). The same
// steps as "Radera mitt konto" and "Radera familjen för alla" in the app (src/privacy.ts). Check that the request came from the
// address first: nothing here asks twice.
//
//   npm --prefix functions run build
//   gcloud auth application-default login
//   node functions/scripts/delete-account.mjs <e-postadress> --project <projekt-id> [--delete-family <family-id>]
//
// An account that is the only parent of a family where others have accounts is refused, and the family's id is printed. Ask
// the person what should happen with the family; --delete-family deletes it for everyone first.
const USAGE = "Usage: node functions/scripts/delete-account.mjs <e-mail> --project <project-id> [--delete-family <family-id>]";

function parse(argv) {
  const args = { email: undefined, project: undefined, deleteFamily: undefined };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--project") args.project = argv[++i];
    else if (argv[i] === "--delete-family") args.deleteFamily = argv[++i];
    else args.email = argv[i];
  }
  return args;
}

const { email, project, deleteFamily } = parse(process.argv.slice(2));
if (!email || !project) {
  console.error(USAGE);
  process.exit(2);
}

// Before the imports: src/setup.ts starts the Admin SDK as soon as it is imported.
process.env.GCLOUD_PROJECT = project;
const { getAuth } = await import("firebase-admin/auth");
const { Reasons, deleteAccountOf, deleteFamilyFor } = await import("../lib/privacy.js");

const user = await getAuth()
  .getUserByEmail(email)
  .catch((e) => {
    if (e.code === "auth/user-not-found") return null;
    throw e;
  });
if (user === null) {
  console.log("No account with that address: nothing to delete.");
  process.exit(0);
}

try {
  if (deleteFamily) await deleteFamilyFor(user.uid, deleteFamily);
  const { familiesDeleted, familiesLeft } = await deleteAccountOf(user.uid);
  console.log(`Account deleted. Families deleted: ${familiesDeleted}. Families left: ${familiesLeft}.`);
} catch (e) {
  if (e.message !== Reasons.lastParent) throw e;
  console.error(`Refused: the only parent of family ${e.details?.familyId}, where others have accounts. Nothing was deleted.`);
  console.error("Ask the person what should happen with the family; --delete-family <family-id> deletes it for everyone.");
  process.exit(1);
}
