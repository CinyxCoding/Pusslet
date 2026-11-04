// The invite code in https://<linkHost>/join/<code>, read the way the app reads it (InviteCodes in shared/core).
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const LENGTH = 10;

/** The code in [pathname], or null when there is none. Lowercase, spaces and dashes are fine, as in the app. */
export function inviteCode(pathname) {
  const raw = /^\/join\/([^/]+)\/?$/.exec(pathname)?.[1];
  if (raw === undefined) return null;
  let decoded;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return null;
  }
  const code = [...decoded.toUpperCase()].filter((c) => ALPHABET.includes(c)).join("");
  return code.length === LENGTH ? code : null;
}

/** "K7QDM2XP9R" -> "K7QD-M2XP-9R", as the invite screen shows it. */
export function formatCode(code) {
  return code.match(/.{1,4}/g).join("-");
}
