import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// @ts-expect-error – a plain ESM script without type declarations
import { build, hasPlaceholders, render } from "../scripts/privacy-page.mjs";

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

  it("renders the real policy's two languages and nothing else", () => {
    const html = render(readFileSync(join(process.cwd(), "../docs/PRIVACY.md"), "utf8"));
    expect(html).toContain('<section lang="sv"><h2>Svenska</h2>');
    expect(html).toContain('<section lang="en"><h2>English</h2>');
    expect(html).not.toContain("Att hålla policyn sann");
    expect(html).not.toContain("<script");
  });
});
