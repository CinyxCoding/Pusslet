/**
 * Builds the family's own ICS feed ("prenumerera på familjekalendern i jobbets Outlook/Google").
 * Pure functions, no Firebase: the input is the event documents exactly as the app syncs them.
 */

/** An event document from families/{fid}/events (kotlinx-serialization field names). */
export interface FeedEvent {
  id: string;
  title?: string;
  /** Local date-time in the event's own zone, e.g. "2026-10-08T16:00". */
  start?: string;
  end?: string;
  allDay?: boolean;
  timeZoneId?: string;
  location?: string;
  rrule?: string | null;
  /** ISO dates of cancelled occurrences. */
  exDates?: string[];
  /** Set on a changed single occurrence: the series it belongs to and the date it replaces. */
  seriesId?: string | null;
  originalDate?: string | null;
  participantIds?: string[];
  dropOff?: { personId?: string | null; time?: string | null } | null;
  pickUp?: { personId?: string | null; time?: string | null } | null;
  note?: string;
  deletedAt?: unknown;
}

export type FeedLanguage = "sv" | "en";

export interface FeedOptions {
  calendarName: string;
  /** Only events this person takes part in or drops off/picks up for; null = the whole family. */
  memberId?: string | null;
  /** person id -> first name, for "Vem", "Lämnar" and "Hämtar". */
  names: Map<string, string>;
  now: Date;
  language?: FeedLanguage;
}

const DEFAULT_ZONE = "Europe/Stockholm";

const WORDS: Record<FeedLanguage, { who: string; dropOff: string; pickUp: string }> = {
  sv: { who: "Vem", dropOff: "Lämnar", pickUp: "Hämtar" },
  en: { who: "Who", dropOff: "Drop-off", pickUp: "Pick-up" },
};

/** Standard RFC 5545 VTIMEZONE for Sweden (EU rules: last Sunday of March / October at 01:00 UTC). */
const STOCKHOLM_VTIMEZONE = [
  "BEGIN:VTIMEZONE",
  "TZID:Europe/Stockholm",
  "X-LIC-LOCATION:Europe/Stockholm",
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

/** RFC 5545 §3.3.11: backslash, semicolon, comma and newlines are escaped in TEXT values. */
export function escapeText(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");
}

/** RFC 5545 §3.1: lines longer than 75 octets are folded (CRLF + space), never inside a UTF-8 character. */
export function foldLine(line: string): string {
  const encoder = new TextEncoder();
  if (encoder.encode(line).length <= 75) return line;
  const parts: string[] = [];
  let current = "";
  let bytes = 0;
  for (const ch of line) {
    const size = encoder.encode(ch).length;
    const limit = parts.length === 0 ? 75 : 74; // continuation lines start with a space
    if (bytes + size > limit) {
      parts.push(current);
      current = "";
      bytes = 0;
    }
    current += ch;
    bytes += size;
  }
  parts.push(current);
  return parts.join("\r\n ");
}

/** "2026-10-08T16:00" -> "20261008T160000"; "2026-10-08" -> "20261008". */
function basic(value: string): string {
  const [date, time = ""] = value.split("T");
  const d = date.replace(/-/g, "");
  if (!time) return d;
  const [h = "00", m = "00", s = "00"] = time.split(":");
  return `${d}T${h.padStart(2, "0")}${m.padStart(2, "0")}${s.slice(0, 2).padStart(2, "0")}`;
}

function utcStamp(date: Date): string {
  return date.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

function involves(e: FeedEvent, memberId: string): boolean {
  return (e.participantIds ?? []).includes(memberId) || e.dropOff?.personId === memberId || e.pickUp?.personId === memberId;
}

/** Which events go in the feed: not deleted, and for a personal feed only what concerns that person. */
export function selectEvents(events: FeedEvent[], memberId?: string | null): FeedEvent[] {
  const live = events.filter((e) => e.deletedAt == null && e.start && e.end);
  if (!memberId) return live;
  const series = new Set(live.filter((e) => !e.seriesId && involves(e, memberId)).map((e) => e.id));
  return live.filter((e) => (e.seriesId ? series.has(e.seriesId) || involves(e, memberId) : series.has(e.id)));
}

function eventLines(e: FeedEvent, byId: Map<string, FeedEvent>, opts: FeedOptions): string[] {
  const zone = e.timeZoneId || DEFAULT_ZONE;
  const start = e.start ?? "";
  const end = e.end ?? "";
  const words = WORDS[opts.language ?? "sv"];
  const uidOf = (id: string) => `${id}@pusslet`;
  const lines = ["BEGIN:VEVENT", `UID:${uidOf(e.seriesId || e.id)}`, `DTSTAMP:${utcStamp(opts.now)}`];
  if (e.allDay) {
    lines.push(`DTSTART;VALUE=DATE:${basic(start.slice(0, 10))}`, `DTEND;VALUE=DATE:${basic(end.slice(0, 10))}`);
  } else {
    lines.push(`DTSTART;TZID=${zone}:${basic(start)}`, `DTEND;TZID=${zone}:${basic(end)}`);
  }
  if (e.seriesId) {
    // A moved or changed occurrence: identified by the series' original start on that date.
    const series = byId.get(e.seriesId);
    const time = series?.start?.split("T")[1] ?? start.split("T")[1] ?? "00:00";
    const date = e.originalDate ?? start.slice(0, 10);
    lines.push(e.allDay ? `RECURRENCE-ID;VALUE=DATE:${basic(date)}` : `RECURRENCE-ID;TZID=${zone}:${basic(`${date}T${time}`)}`);
  } else if (e.rrule) {
    lines.push(`RRULE:${e.rrule}`);
    const time = start.split("T")[1] ?? "00:00";
    for (const d of e.exDates ?? []) {
      lines.push(e.allDay ? `EXDATE;VALUE=DATE:${basic(d)}` : `EXDATE;TZID=${zone}:${basic(`${d}T${time}`)}`);
    }
  }
  lines.push(`SUMMARY:${escapeText(e.title ?? "")}`);
  if (e.location) lines.push(`LOCATION:${escapeText(e.location)}`);
  const description: string[] = [];
  const who = (e.participantIds ?? []).map((id) => opts.names.get(id)).filter((n): n is string => !!n);
  if (who.length) description.push(`${words.who}: ${who.join(", ")}`);
  const drop = e.dropOff?.personId ? opts.names.get(e.dropOff.personId) : undefined;
  const pick = e.pickUp?.personId ? opts.names.get(e.pickUp.personId) : undefined;
  if (drop) description.push(`${words.dropOff}: ${drop}${e.dropOff?.time ? ` ${e.dropOff.time}` : ""}`);
  if (pick) description.push(`${words.pickUp}: ${pick}${e.pickUp?.time ? ` ${e.pickUp.time}` : ""}`);
  if (e.note) description.push(e.note);
  if (description.length) lines.push(`DESCRIPTION:${escapeText(description.join("\n"))}`);
  lines.push("END:VEVENT");
  return lines;
}

/** The whole VCALENDAR, CRLF line endings and folded lines, ready to serve as text/calendar. */
export function buildFeed(events: FeedEvent[], opts: FeedOptions): string {
  const selected = selectEvents(events, opts.memberId);
  const byId = new Map(events.map((e) => [e.id, e]));
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Pusslet//Familjekalender//SV",
    "CALSCALE:GREGORIAN",
    "METHOD:PUBLISH",
    `X-WR-CALNAME:${escapeText(opts.calendarName)}`,
    `X-WR-TIMEZONE:${DEFAULT_ZONE}`,
    // Clients poll at their own pace; this asks for at least every hour.
    "REFRESH-INTERVAL;VALUE=DURATION:PT1H",
    "X-PUBLISHED-TTL:PT1H",
    ...STOCKHOLM_VTIMEZONE,
  ];
  // Series first, then their changed occurrences (some clients need the master before RECURRENCE-ID).
  const ordered = [...selected].sort((a, b) => Number(!!a.seriesId) - Number(!!b.seriesId) || (a.start ?? "").localeCompare(b.start ?? ""));
  for (const e of ordered) lines.push(...eventLines(e, byId, opts));
  lines.push("END:VCALENDAR");
  return lines.map(foldLine).join("\r\n") + "\r\n";
}

/** Secret tokens: URL-safe, at least 24 characters (the security rules enforce the same minimum). */
export function isValidToken(token: string): boolean {
  return /^[A-Za-z0-9_-]{24,128}$/.test(token);
}
