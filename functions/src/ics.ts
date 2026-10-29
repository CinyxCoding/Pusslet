/**
 * RFC 5545 iCalendar for the family's own calendar feed (calendarFeed.ts, docs/DECISIONS.md ADR-041). Pure: the events in,
 * the text out, so it is tested without emulators. Local times keep their zone (TZID), so a weekly 17:00 stays at 17:00
 * across summer time; Europe/Stockholm gets its VTIMEZONE, other zones are named by their IANA id, which Google, Apple and
 * Outlook all know, and UTC/GMT is written with "Z". A series is one VEVENT with RRULE and EXDATE; a moved or changed
 * occurrence is a VEVENT with the series' UID and a RECURRENCE-ID, as calendars expect.
 */

/** One family event as the app stores it in Firestore (shared/core `Event`, serialized by kotlinx). */
export interface FeedEvent {
  id: string;
  title?: string;
  /** Local date-time, "2026-10-21T17:00" (seconds optional). */
  start: string;
  end: string;
  allDay?: boolean;
  timeZoneId?: string;
  location?: string;
  rrule?: string | null;
  /** "2026-10-28" */
  exDates?: string[];
  seriesId?: string | null;
  originalDate?: string | null;
  participantIds?: string[];
}

export interface CalendarInput {
  name: string;
  events: FeedEvent[];
  /** Member and child ids to the names shown in an event's description ("Ella, Simon"). */
  names: Map<string, string>;
  now: Date;
}

const STOCKHOLM = "Europe/Stockholm";
const UTC_ZONES = new Set(["UTC", "GMT", "Etc/UTC", "Etc/GMT", "Z"]);
const UID_DOMAIN = "pusslet.se";
const MAX_OCTETS = 75;

const STOCKHOLM_VTIMEZONE = [
  "BEGIN:VTIMEZONE",
  `TZID:${STOCKHOLM}`,
  "BEGIN:DAYLIGHT",
  "TZOFFSETFROM:+0100",
  "TZOFFSETTO:+0200",
  "TZNAME:CEST",
  "DTSTART:19700329T020000",
  "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
  "END:DAYLIGHT",
  "BEGIN:STANDARD",
  "TZOFFSETFROM:+0200",
  "TZOFFSETTO:+0100",
  "TZNAME:CET",
  "DTSTART:19701025T030000",
  "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU",
  "END:STANDARD",
  "END:VTIMEZONE",
];

interface Parts {
  y: number;
  m: number;
  d: number;
  h: number;
  mi: number;
  s: number;
}

const DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?/;

function parse(value: string | null | undefined): Parts | null {
  const m = value ? DATE_TIME.exec(value) : null;
  if (!m) return null;
  return { y: +m[1], m: +m[2], d: +m[3], h: +(m[4] ?? 0), mi: +(m[5] ?? 0), s: +(m[6] ?? 0) };
}

const pad = (n: number, width = 2) => String(n).padStart(width, "0");
const date = (p: Parts) => `${pad(p.y, 4)}${pad(p.m)}${pad(p.d)}`;
const dateTime = (p: Parts) => `${date(p)}T${pad(p.h)}${pad(p.mi)}${pad(p.s)}`;

function plusDays(p: Parts, days: number): Parts {
  const t = new Date(Date.UTC(p.y, p.m - 1, p.d + days));
  return { ...p, y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

function utcStamp(t: Date): string {
  return `${pad(t.getUTCFullYear(), 4)}${pad(t.getUTCMonth() + 1)}${pad(t.getUTCDate())}T${pad(t.getUTCHours())}${pad(
    t.getUTCMinutes(),
  )}${pad(t.getUTCSeconds())}Z`;
}

/**
 * TEXT values: backslash, semicolon, comma and line breaks escaped (RFC 5545 §3.3.11), other control characters dropped,
 * so nothing typed into an event can start a line of its own (a lone CR too: some calendars break lines on it).
 */
export function escapeText(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,")
    .replace(/\r\n|\r|\n/g, "\\n")
    .replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, "");
}

/** An IANA zone id ("Europe/Stockholm", "Etc/GMT+1"); anything else (or nothing) gives null. */
function zoneOf(value: string | null | undefined): string | null {
  const zone = value?.trim();
  return zone && zone.length <= 64 && /^[A-Za-z0-9_+-]+(?:\/[A-Za-z0-9_+-]+)*$/.test(zone) ? zone : null;
}

/** An RRULE value as the app writes it ("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T225959Z"); anything else gives null. */
function ruleOf(value: string | null | undefined): string | null {
  const rule = value?.trim().replace(/^RRULE:/i, "");
  return rule && rule.length <= 500 && /^[A-Za-z0-9=;,+-]+$/.test(rule) ? rule : null;
}

/** A UID's local part: the document id, with anything that could end the property replaced. */
const uidOf = (id: string) => id.replace(/[^A-Za-z0-9_.-]/g, "-");

/** Lines longer than 75 octets continue on the next line after a space (RFC 5545 §3.1), never inside a character. */
export function fold(line: string): string {
  const out: string[] = [];
  let current = "";
  let octets = 0;
  for (const ch of line) {
    const size = Buffer.byteLength(ch, "utf8");
    const limit = out.length === 0 ? MAX_OCTETS : MAX_OCTETS - 1;
    if (octets + size > limit) {
      out.push(current);
      current = "";
      octets = 0;
    }
    current += ch;
    octets += size;
  }
  out.push(current);
  return out.join("\r\n ");
}

/** A local date-time property in the event's zone: "DTSTART;TZID=Europe/Stockholm:20261021T170000" or "…Z" for UTC. */
function local(name: string, p: Parts, zone: string): string {
  return UTC_ZONES.has(zone) ? `${name}:${dateTime(p)}Z` : `${name};TZID=${zone}:${dateTime(p)}`;
}

function vevent(e: FeedEvent, series: FeedEvent | undefined, input: CalendarInput, stamp: string): string[] | null {
  const start = parse(e.start);
  if (!start) return null;
  const end = parse(e.end) ?? start;
  const zone = zoneOf(e.timeZoneId) ?? STOCKHOLM;
  const lines = ["BEGIN:VEVENT", `UID:${uidOf(e.seriesId ?? e.id)}@${UID_DOMAIN}`, `DTSTAMP:${stamp}`];
  if (e.allDay) {
    const last = date(end) > date(start) ? end : plusDays(start, 1);
    lines.push(`DTSTART;VALUE=DATE:${date(start)}`, `DTEND;VALUE=DATE:${date(last)}`);
  } else {
    lines.push(local("DTSTART", start, zone), local("DTEND", dateTime(end) > dateTime(start) ? end : start, zone));
  }
  if (e.seriesId) {
    const original = parse(e.originalDate);
    if (original) {
      const seriesStart = parse(series?.start) ?? start;
      const at: Parts = { ...original, h: seriesStart.h, mi: seriesStart.mi, s: seriesStart.s };
      const allDay = series?.allDay ?? e.allDay;
      lines.push(allDay ? `RECURRENCE-ID;VALUE=DATE:${date(original)}` : local("RECURRENCE-ID", at, zoneOf(series?.timeZoneId) ?? zone));
    }
  } else if (ruleOf(e.rrule)) {
    lines.push(`RRULE:${ruleOf(e.rrule)}`);
    for (const d of e.exDates ?? []) {
      const p = parse(d);
      if (!p) continue;
      lines.push(e.allDay ? `EXDATE;VALUE=DATE:${date(p)}` : local("EXDATE", { ...p, h: start.h, mi: start.mi, s: start.s }, zone));
    }
  }
  lines.push(`SUMMARY:${escapeText(e.title?.trim() || "–")}`);
  if (e.location?.trim()) lines.push(`LOCATION:${escapeText(e.location.trim())}`);
  const who = (e.participantIds ?? []).map((id) => input.names.get(id)).filter((n): n is string => !!n?.trim());
  if (who.length > 0) lines.push(`DESCRIPTION:${escapeText(who.join(", "))}`);
  lines.push("END:VEVENT");
  return lines;
}

/** The whole feed, CRLF line ends, folded. */
export function buildCalendar(input: CalendarInput): string {
  const stamp = utcStamp(input.now);
  const byId = new Map(input.events.map((e) => [e.id, e]));
  const events = input.events
    .map((e) => vevent(e, e.seriesId ? byId.get(e.seriesId) : undefined, input, stamp))
    .filter((v): v is string[] => v !== null);
  const usesStockholm = input.events.some((e) => !e.allDay && (zoneOf(e.timeZoneId) ?? STOCKHOLM) === STOCKHOLM);
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Pusslet//Familjens kalender//SV",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    `X-WR-CALNAME:${escapeText(input.name.trim() || "Pusslet")}`,
    `X-WR-TIMEZONE:${STOCKHOLM}`,
    "REFRESH-INTERVAL;VALUE=DURATION:PT1H",
    "X-PUBLISHED-TTL:PT1H",
    ...(usesStockholm ? STOCKHOLM_VTIMEZONE : []),
    ...events.flat(),
    "END:VCALENDAR",
  ];
  return lines.map(fold).join("\r\n") + "\r\n";
}
