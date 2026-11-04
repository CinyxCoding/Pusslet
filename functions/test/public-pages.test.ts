import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// @ts-expect-error – a plain ESM script without type declarations
import { formatCode, inviteCode } from "../../hosting/join/invite-code.js";
// @ts-expect-error – a plain ESM script without type declarations
import { PAGES, build, hasPlaceholders, render } from "../scripts/public-pages.mjs";

const repo = (path: string) => join(process.cwd(), "..", path);

// Shaped like docs/PRIVACY.md: a note to the owner, the two language sections and a section for the maintainers.
const POLICY = `# Integritetspolicy för Pusslet (utkast)

> **Utkast att granska innan publicering.** Fyll i det som står inom [hakparentes].

---

## Svenska
**Personuppgiftsansvarig:** Familjen Exempel, exempel@example.se

### Vilka uppgifter
| Uppgifter | Varför |
|---|---|
| Barn: förnamn | Planering <script> |

- **Google Firebase** är vårt personuppgiftsbiträde. Data lagras i EU
  (\`europe-north1\`).
- Öppna datakällor, se [SMHI](https://www.smhi.se).

Du har rätt att klaga hos Integritetsskyddsmyndigheten (imy.se).

## English
### Children
Children have no accounts.

## Att hålla policyn sann
Uppdatera policyn när [något] ändras.
`;

describe("privacy page", () => {
  it("renders both languages, without the note to the owner or the maintainers' section", () => {
    const html = render(POLICY);
    expect(html).toContain("<title>Integritetspolicy för Pusslet</title>");
    expect(html).not.toContain("Utkast att granska");
    expect(html).not.toContain("Att hålla policyn sann");
    expect(html).toContain('<section lang="sv"><h2>Svenska</h2>');
    expect(html).toContain('<section lang="en"><h2>English</h2>');
    expect(html).toContain("<th>Uppgifter</th>");
    expect(html).toContain("<td>Planering &lt;script&gt;</td>");
    expect(html).toContain(
      "<li><strong>Google Firebase</strong> är vårt personuppgiftsbiträde. Data lagras i EU (<code>europe-north1</code>).</li>",
    );
    expect(html).toContain('<a href="https://www.smhi.se">SMHI</a>');
    expect(html).toContain('(<a href="https://www.imy.se">imy.se</a>)');
  });

  it("never publishes a policy that still has placeholders", () => {
    const dir = mkdtempSync(join(tmpdir(), "privacy-"));
    const source = join(dir, "PRIVACY.md");
    const target = join(dir, "out", "index.html");
    writeFileSync(source, POLICY);
    expect(hasPlaceholders(POLICY)).toBe(false);
    expect(build(source, target)).toBe(target);
    expect(readFileSync(target, "utf8")).toContain("Familjen Exempel");

    writeFileSync(source, POLICY.replace("Familjen Exempel, exempel@example.se", "[namn på ägaren], [e-postadress]"));
    expect(hasPlaceholders(readFileSync(source, "utf8"))).toBe(true);
    expect(build(source, target)).toBeNull();
    expect(existsSync(target)).toBe(false);
  });

  it.each(PAGES.map((page: { source: string }) => page.source))("renders %s's two languages and nothing else", (source) => {
    const html = render(readFileSync(repo(source as string), "utf8"));
    expect(html).toContain('<section lang="sv"><h2>Svenska</h2>');
    expect(html).toContain('<section lang="en"><h2>English</h2>');
    expect(html).toContain('<link rel="stylesheet" href="/site.css">');
    expect(html).not.toContain("Att hålla");
    expect(html).not.toContain("Utkast att granska");
    expect(html).not.toContain("<script");
  });

  it("links to the site's own pages and to e-mail addresses", () => {
    const html = render("# Radera\n\n## Svenska\nLäs [policyn](/privacy/) eller mejla hej@example.se.\n");
    expect(html).toContain('<a href="/privacy/">policyn</a>');
    expect(html).toContain('<a href="mailto:hej@example.se">hej@example.se</a>');
  });

  it("does not take a link to our own page for a placeholder, but does a missing address", () => {
    expect(hasPlaceholders("# T\n\n## Svenska\n[sidan](/radera/)\n")).toBe(false);
    expect(hasPlaceholders("# T\n\n## Svenska\nMejla [e-postadress].\n")).toBe(true);
  });

  it("the page for deleting an account can be used without the app", () => {
    const html = render(readFileSync(repo("docs/DELETE_ACCOUNT.md"), "utf8"));
    expect(html).toContain("Utan appen");
    expect(html).toContain("Without the app");
    expect(html).toContain("Pusslet");
  });
});

describe("invite page", () => {
  it("reads the code like the app does", () => {
    expect(inviteCode("/join/K7QDM2XP9R")).toBe("K7QDM2XP9R");
    expect(inviteCode("/join/k7qd-m2xp-9r")).toBe("K7QDM2XP9R");
    expect(inviteCode("/join/K7QD%20M2XP%209R/")).toBe("K7QDM2XP9R");
  });

  it("shows nothing for what is not a code", () => {
    for (const path of ["/join/", "/join", "/join/K7QD", "/join/K7QDM2XP9R/mer", "/join/%E0%A4%A", "/privacy/"]) {
      expect(inviteCode(path)).toBeNull();
    }
    expect(inviteCode("/join/<script>alert(1)</script>")).toBeNull();
  });

  it("writes the code as the invite screen does", () => {
    expect(formatCode("K7QDM2XP9R")).toBe("K7QD-M2XP-9R");
  });

  it("has the same alphabet and length as the app", () => {
    const kotlin = readFileSync(repo("shared/core/src/commonMain/kotlin/se/pusslet/core/util/Ids.kt"), "utf8");
    const js = readFileSync(repo("hosting/join/invite-code.js"), "utf8");
    expect(/ALPHABET = ("[^"]+")/.exec(js)?.[1]).toBe(/ALPHABET = ("[^"]+")/.exec(kotlin)?.[1]);
    expect(/LENGTH = (\d+)/.exec(js)?.[1]).toBe(/LENGTH = (\d+)/.exec(kotlin)?.[1]);
  });

  it("opens for every invite link, and the code goes nowhere else", () => {
    const hosting = JSON.parse(readFileSync(repo("firebase.json"), "utf8")).hosting as {
      rewrites: { source: string; destination: string }[];
      headers: { source: string; headers: { key: string; value: string }[] }[];
    };
    expect(hosting.rewrites).toContainEqual({ source: "/join/**", destination: "/join/index.html" });
    const join_ = hosting.headers.filter((h) => h.source === "/join/**").flatMap((h) => h.headers);
    expect(join_).toContainEqual({ key: "Referrer-Policy", value: "no-referrer" });
    expect(join_).toContainEqual({ key: "X-Robots-Tag", value: "noindex" });

    const html = readFileSync(repo("hosting/join/index.html"), "utf8");
    expect(html.match(/<script[^>]*>/g)).toEqual(['<script type="module" src="/join/join.js">']);
    expect(html).toContain('<meta name="referrer" content="no-referrer">');
    expect(html).not.toMatch(/\bstyle=/);
    for (const file of ["hosting/site.css", "hosting/join/join.js"]) expect(existsSync(repo(file))).toBe(true);
  });
});
