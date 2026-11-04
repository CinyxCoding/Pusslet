// Builds the public pages in hosting/ from docs/, so each text has one source: the privacy policy, the terms of use and the
// page for deleting an account (PAGES). Runs before every hosting deploy (firebase.json predeploy) and by hand:
//   node functions/scripts/public-pages.mjs
// Only the "Svenska" and "English" sections are the text: the note to the owner at the top and the maintainers' section at the
// end never go public. While a text still has [placeholders] its page is not written: a half-filled page must never go public.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

export const PAGES = [
  { source: "docs/PRIVACY.md", target: "hosting/privacy/index.html" },
  { source: "docs/TERMS.md", target: "hosting/villkor/index.html" },
  { source: "docs/DELETE_ACCOUNT.md", target: "hosting/radera/index.html" },
];

const escape = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/**
 * **bold**, `code`, [links](https://… or /path/), bare domains like imy.se and e-mail addresses (escaped first, so nothing
 * from the text becomes markup).
 */
function inline(text) {
  return escape(text)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\[([^\]]+)\]\(((?:https:\/)?\/[^)\s]+)\)/g, '<a href="$2">$1</a>')
    .replace(/(^|[\s(])(imy\.se)\b/g, '$1<a href="https://www.$2">$2</a>')
    .replace(/(^|[\s(])([\w.+-]+@[\w-]+(?:\.[\w-]+)+)/g, '$1<a href="mailto:$2">$2</a>');
}

/** The title and the language sections, without the note to the owner (quoted lines) or any other section. */
export function publicPart(markdown) {
  const out = [];
  let keep = true;
  for (const line of markdown.replace(/\r\n/g, "\n").split("\n")) {
    const h2 = /^## (.*)$/.exec(line);
    if (h2) keep = /^(svenska|english)$/i.test(h2[1].trim());
    if (keep && !line.startsWith(">")) out.push(line);
  }
  return out.join("\n");
}

/** True while the text still has a placeholder such as [e-postadress]: square brackets that are not a link. */
export function hasPlaceholders(markdown) {
  return /\[[^\]\n]+\](?!\()/.test(publicPart(markdown));
}

/** The subset of Markdown the texts use: headings, paragraphs, lists, tables and rules. */
export function render(markdown) {
  const lines = publicPart(markdown).split("\n");
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
  <link rel="stylesheet" href="/site.css">
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

/** Writes the page, or removes a stale one while the text has placeholders. Returns the page path or null. */
export function build(source, target) {
  const markdown = readFileSync(source, "utf8");
  if (hasPlaceholders(markdown)) {
    if (existsSync(target)) rmSync(target);
    console.warn(`public-pages: ${source} still has [placeholders] – its public page was not written.`);
    return null;
  }
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, render(markdown));
  console.log(`public-pages: wrote ${target}`);
  return target;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  for (const page of PAGES) build(resolve(root, page.source), resolve(root, page.target));
}
