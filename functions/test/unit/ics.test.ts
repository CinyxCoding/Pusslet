import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { buildFeed, escapeText, foldLine, isValidToken, selectEvents, type FeedEvent } from "../../src/ics.js";

/** Shared with the app's Kotlin tests, which parse it back with the app's own ICS parser. */
const GOLDEN = resolve(dirname(fileURLToPath(import.meta.url)), "../../../shared/data/src/commonTest/resources/fixtures/own_family_feed.ics");

const names = new Map([
  ["m-sara", "Sara"],
  ["m-johan", "Johan"],
  ["c-ella", "Ella"],
  ["c-simon", "Simon"],
]);

const events: FeedEvent[] = [
  {
    id: "e-fotboll",
    title: "Fotboll",
    start: "2026-09-01T17:30",
    end: "2026-09-01T18:30",
    timeZoneId: "Europe/Stockholm",
    rrule: "FREQ=WEEKLY;BYDAY=TU",
    exDates: ["2026-10-13"],
    participantIds: ["c-simon"],
    dropOff: { personId: "m-johan", time: null },
    location: "Vallen, plan 2",
  },
  {
    id: "e-fotboll-1020",
    title: "Fotboll (flyttad)",
    start: "2026-10-20T18:00",
    end: "2026-10-20T19:00",
    seriesId: "e-fotboll",
    originalDate: "2026-10-20",
    participantIds: ["c-simon"],
  },
  { id: "e-studiedag", title: "Studiedag", start: "2026-10-23T00:00", end: "2026-10-24T00:00", allDay: true, participantIds: ["c-ella"] },
  {
    id: "e-mote",
    title: "Föräldramöte; klass 3B",
    start: "2026-10-14T18:00",
    end: "2026-10-14T19:30",
    participantIds: ["m-sara"],
    note: "Ta med: kaffe, kaka\nRum 12",
  },
  { id: "e-borta", title: "Raderad", start: "2026-10-15T10:00", end: "2026-10-15T11:00", deletedAt: { seconds: 1 } },
  {
    id: "e-lang",
    title: "Utvecklingssamtal för Ella med mentorn på Lindskolan – ta med läsloggen och ämnesomdömena",
    start: "2026-10-21T08:15",
    end: "2026-10-21T08:45",
    participantIds: ["c-ella", "m-sara"],
    pickUp: { personId: "m-sara", time: "08:45" },
  },
];

const now = new Date("2026-10-08T05:10:00Z");

describe("ics text", () => {
  it("escapes RFC 5545 text", () => {
    expect(escapeText("a,b;c\\d\ne")).toBe("a\\,b\\;c\\\\d\\ne");
  });

  it("folds long lines at 75 octets without splitting åäö", () => {
    const line = "SUMMARY:" + "å".repeat(60);
    const folded = foldLine(line);
    const parts = folded.split("\r\n");
    expect(parts.length).toBeGreaterThan(1);
    for (const p of parts) expect(new TextEncoder().encode(p).length).toBeLessThanOrEqual(75);
    expect(parts.map((p, i) => (i === 0 ? p : p.slice(1))).join("")).toBe(line);
  });

  it("accepts only long URL-safe tokens", () => {
    expect(isValidToken("abcdefghijklmnopqrstuvwx")).toBe(true);
    expect(isValidToken("short")).toBe(false);
    expect(isValidToken("../../../etc/passwd-aaaaaaaaaaaaaa")).toBe(false);
  });
});

describe("family feed", () => {
  it("leaves out deleted events and keeps a personal feed to that person", () => {
    expect(selectEvents(events).map((e) => e.id)).not.toContain("e-borta");
    expect(selectEvents(events, "m-johan").map((e) => e.id)).toEqual(["e-fotboll", "e-fotboll-1020"]);
    expect(selectEvents(events, "m-sara").map((e) => e.id).sort()).toEqual(["e-lang", "e-mote"]);
  });

  it("writes series, exceptions, moved occurrences and all-day events", () => {
    const ics = buildFeed(events, { calendarName: "Familjen Lind", names, now });
    expect(ics).toContain("UID:e-fotboll@pusslet\r\nDTSTAMP:20261008T051000Z\r\nDTSTART;TZID=Europe/Stockholm:20260901T173000");
    expect(ics).toContain("RRULE:FREQ=WEEKLY;BYDAY=TU");
    expect(ics).toContain("EXDATE;TZID=Europe/Stockholm:20261013T173000");
    expect(ics).toContain("RECURRENCE-ID;TZID=Europe/Stockholm:20261020T173000");
    expect(ics).toContain("DTSTART;VALUE=DATE:20261023\r\nDTEND;VALUE=DATE:20261024");
    expect(ics).toContain("SUMMARY:Föräldramöte\\; klass 3B");
    expect(ics).toContain("DESCRIPTION:Vem: Sara\\nTa med: kaffe\\, kaka\\nRum 12");
    expect(ics).toContain("DESCRIPTION:Vem: Simon\\nLämnar: Johan");
    expect(ics).not.toContain("Raderad");
    expect(ics.endsWith("END:VCALENDAR\r\n")).toBe(true);
    // The series comes before its moved occurrence.
    expect(ics.indexOf("RRULE:FREQ=WEEKLY")).toBeLessThan(ics.indexOf("RECURRENCE-ID"));
  });

  it("speaks English when asked", () => {
    const ics = buildFeed(events, { calendarName: "Lind family", names, now, language: "en" });
    expect(ics).toContain("Who: Simon\\nDrop-off: Johan");
  });

  it("matches the golden feed the app's parser is tested against", () => {
    const ics = buildFeed(events, { calendarName: "Familjen Lind", names, now });
    if (process.env.UPDATE_GOLDEN) writeFileSync(GOLDEN, ics);
    expect(ics).toBe(readFileSync(GOLDEN, "utf8"));
  });
});
