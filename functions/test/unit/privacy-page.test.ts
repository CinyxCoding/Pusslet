import { mkdtempSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// @ts-expect-error – plain ESM script without type declarations
import { build, hasPlaceholders, render } from "../../scripts/privacy-page.mjs";

const POLICY = `# Integritetspolicy (utkast) / Privacy policy (draft)

> Utkast att granska innan publicering.

## Svenska

**Personuppgiftsansvarig:** Familjen Exempel, exempel@example.se

### Vilka uppgifter
| Uppgifter | Varför |
|---|---|
| Barn: förnamn | Planering <script> |

- **Google Firebase** är vårt personuppgiftsbiträde. Data lagras i EU
  (\`europe-north1\`).
- Öppna datakällor.

Du har rätt att klaga hos Integritetsskyddsmyndigheten (imy.se).

---

## English

### Children
Children have no accounts.
`;

describe("privacy page", () => {
  it("renders the policy without the draft note, in both languages", () => {
    const html = render(POLICY);
    expect(html).toContain("<title>Integritetspolicy / Privacy policy</title>");
    expect(html).not.toContain("Utkast att granska");
    expect(html).toContain('<section lang="sv"><h2>Svenska</h2>');
    expect(html).toContain('<section lang="en"><h2>English</h2>');
    expect(html).toContain("<th>Uppgifter</th>");
    expect(html).toContain("<td>Planering &lt;script&gt;</td>");
    expect(html).toContain("<li><strong>Google Firebase</strong> är vårt personuppgiftsbiträde. Data lagras i EU (<code>europe-north1</code>).</li>");
    expect(html).toContain('<a href="https://www.imy.se">imy.se</a>');
  });

  it("never publishes a policy that still has placeholders", () => {
    const dir = mkdtempSync(join(tmpdir(), "privacy-"));
    const source = join(dir, "PRIVACY.md");
    const target = join(dir, "out", "index.html");
    writeFileSync(source, POLICY);
    expect(build(source, target)).toBe(target);
    expect(readFileSync(target, "utf8")).toContain("Familjen Exempel");

    writeFileSync(source, POLICY.replace("Familjen Exempel, exempel@example.se", "[…namn…], […e-post…]"));
    expect(hasPlaceholders(readFileSync(source, "utf8"))).toBe(true);
    expect(build(source, target)).toBeNull();
    expect(existsSync(target)).toBe(false);
  });
});
