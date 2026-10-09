// Builds the public privacy policy page (hosting/privacy/index.html) from docs/PRIVACY.md, so the policy has one source.
// Runs before every hosting deploy (firebase.json predeploy) and by hand:  node functions/scripts/privacy-page.mjs
// While the policy still has […] placeholders no page is written: a half-filled policy must never go public.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

const escape = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** **bold**, `code` and bare domains like imy.se (escaped first, so nothing from the text becomes markup). */
function inline(text) {
  return escape(text)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\b(imy\.se)\b/g, '<a href="https://www.$1">$1</a>');
}

/** True while the draft still has placeholders such as […e-post…]. */
export function hasPlaceholders(markdown) {
  return /\[…[^\]]*\]|\[\.\.\.[^\]]*\]/.test(markdown);
}

/** The subset of Markdown PRIVACY.md uses: headings, paragraphs, lists, tables and rules. */
export function render(markdown) {
  const lines = markdown.replace(/\r\n/g, "\n").split("\n");
  const out = [];
  let title = "Pusslet";
  let paragraph = [];
  let list = null;
  let open = null; // the language section we are in
  const flush = () => {
    if (paragraph.length) out.push(`<p>${inline(paragraph.join(" "))}</p>`);
    paragraph = [];
    if (list) out.push(`<ul>${list.map((item) => `<li>${inline(item)}</li>`).join("")}</ul>`);
    list = null;
  };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith("> ")) continue; // the note to the owner at the top
    const h = /^(#{1,3}) (.*)$/.exec(line);
    if (h) {
      flush();
      const text = h[2].replace(/\s*\((utkast|draft)\)/gi, "");
      if (h[1] === "#") {
        title = text;
      } else if (h[1] === "##") {
        if (open) out.push("</section>");
        open = /english/i.test(text) ? "en" : "sv";
        out.push(`<section lang="${open}"><h2>${inline(text)}</h2>`);
      } else {
        out.push(`<h3>${inline(text)}</h3>`);
      }
      continue;
    }
    if (line.startsWith("|")) {
      flush();
      const rows = [];
      while (i < lines.length && lines[i].startsWith("|")) rows.push(lines[i++]);
      i--;
      const cells = (row) => row.split("|").slice(1, -1).map((c) => c.trim());
      const [head, , ...body] = rows;
      out.push(
        `<table><thead><tr>${cells(head).map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead><tbody>` +
          body.map((r) => `<tr>${cells(r).map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`).join("") +
          "</tbody></table>",
      );
      continue;
    }
    if (line.trim() === "---") {
      flush();
      continue;
    }
    if (line.startsWith("- ")) {
      if (paragraph.length) flush();
      list = list ?? [];
      list.push(line.slice(2).trim());
      continue;
    }
    if (list && /^\s+\S/.test(line)) {
      list[list.length - 1] += " " + line.trim();
      continue;
    }
    if (line.trim() === "") {
      flush();
      continue;
    }
    // "**Senast uppdaterad:** …" and "**Personuppgiftsansvarig:** …" are lines of their own.
    if (list || (paragraph.length && line.startsWith("**"))) flush();
    paragraph.push(line.trim());
  }
  flush();
  if (open) out.push("</section>");
  return `<!doctype html>
<html lang="sv">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escape(title)}</title>
  <style>
    body { font-family: system-ui, sans-serif; background: #f8f5ef; color: #2b2a26; margin: 0; padding: 2rem 1rem; line-height: 1.5; }
    main { max-width: 46rem; margin: 0 auto; }
    h1, h2, h3 { font-weight: 600; line-height: 1.25; }
    section + section { margin-top: 3rem; border-top: 1px solid #d9d3c7; padding-top: 1rem; }
    table { border-collapse: collapse; width: 100%; font-size: 0.95rem; }
    th, td { text-align: left; vertical-align: top; padding: 0.4rem; border-bottom: 1px solid #d9d3c7; }
    code { font-size: 0.9em; }
    a { color: #3e6b4f; }
    @media (prefers-color-scheme: dark) {
      body { background: #1c1b19; color: #e9e4da; }
      th, td, section + section { border-color: #45413a; }
      a { color: #9fc7a9; }
    }
  </style>
</head>
<body>
  <main>
    <h1>${escape(title)}</h1>
    ${out.join("\n    ")}
  </main>
</body>
</html>
`;
}

/** Writes the page, or removes a stale one while the policy has placeholders. Returns the page path or null. */
export function build(source = resolve(root, "docs/PRIVACY.md"), target = resolve(root, "hosting/privacy/index.html")) {
  const markdown = readFileSync(source, "utf8");
  if (hasPlaceholders(markdown)) {
    if (existsSync(target)) rmSync(target);
    console.warn("privacy-page: docs/PRIVACY.md still has […] placeholders – no public policy page was written.");
    return null;
  }
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, render(markdown));
  console.log(`privacy-page: wrote ${target}`);
  return target;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) build();
