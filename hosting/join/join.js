// The page behind https://<linkHost>/join/<code> when the app does not open the link itself, usually because it is not
// installed yet (ADR-064). Everything happens in the browser: the code is read from the address and shown, nothing more.
import { formatCode, inviteCode } from "/join/invite-code.js";

// One language at a time. Without scripts both stay.
const english = !(navigator.language ?? "sv").toLowerCase().startsWith("sv");
document.querySelector(english ? 'section[lang="sv"]' : 'section[lang="en"]').hidden = true;
if (english) {
  document.documentElement.lang = "en";
  document.title = "Invitation to Pusslet";
}

const code = inviteCode(location.pathname);
if (code !== null) {
  const shown = formatCode(code);
  document.querySelectorAll("[data-code]").forEach((element) => {
    element.textContent = shown;
  });
  document.querySelectorAll("[data-with-code]").forEach((element) => {
    element.hidden = false;
  });
  document.querySelectorAll("[data-without-code]").forEach((element) => {
    element.hidden = true;
  });
  document.querySelectorAll("[data-open-app]").forEach((link) => {
    link.href = `pusslet://join/${code}`;
  });
  document.querySelectorAll("[data-copy]").forEach((button) => {
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(shown);
        button.textContent = button.dataset.copied;
      } catch {
        // Browsers inside other apps can say no. Then the code is marked, ready to copy by hand.
        getSelection()?.selectAllChildren(button.parentElement.querySelector("[data-code]"));
      }
    });
  });
}
