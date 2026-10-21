// The iCalendar text of the family feed (src/ics.ts), without emulators.
import { describe, expect, it } from "vitest";
import { buildCalendar, escapeText, fold, type FeedEvent } from "../src/ics.js";

const now = new Date(Date.UTC(2026, 9, 21, 12, 0, 0));
const names = new Map([
  ["ella", "Ella"],
  ["simon", "Simon"],
  ["sara", "Sara"],
]);

function calendar(events: FeedEvent[]): string[] {
  const text = buildCalendar({ name: "Familjen Lind", events, names, now });
  expect(text.endsWith("\r\n")).toBe(true);
  // Unfolded lines, so the assertions read like the properties.
  return text.replace(/\r\n /g, "").split("\r\n").filter(Boolean);
}

describe("the family feed as iCalendar", () => {
  it("keeps local times in their zone, with Stockholm's summer time", () => {
    const lines = calendar([
      {
        id: "e-dans",
        title: "Dans",
        start: "2026-10-19T17:00",
        end: "2026-10-19T18:00",
        timeZoneId: "Europe/Stockholm",
        location: "Kulturskolan, sal 2",
        rrule: "FREQ=WEEKLY;BYDAY=MO",
        exDates: ["2026-11-02"],
        participantIds: ["ella", "okänd"],
      },
    ]);
    expect(lines).toContain("X-WR-CALNAME:Familjen Lind");
    expect(lines).toContain("BEGIN:VTIMEZONE");
    expect(lines).toContain("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU");
    expect(lines).toContain("UID:e-dans@pusslet.se");
    expect(lines).toContain("DTSTART;TZID=Europe/Stockholm:20261019T170000");
    expect(lines).toContain("DTEND;TZID=Europe/Stockholm:20261019T180000");
    expect(lines).toContain("RRULE:FREQ=WEEKLY;BYDAY=MO");
    expect(lines).toContain("EXDATE;TZID=Europe/Stockholm:20261102T170000");
    expect(lines).toContain("LOCATION:Kulturskolan\\, sal 2");
    expect(lines).toContain("DESCRIPTION:Ella");
    expect(lines).toContain("DTSTAMP:20261021T120000Z");
  });

  it("writes a moved occurrence with the series' UID and its original start", () => {
    const lines = calendar([
      { id: "e-dans", title: "Dans", start: "2026-10-19T17:00", end: "2026-10-19T18:00", rrule: "FREQ=WEEKLY" },
      {
        id: "e-dans-26",
        title: "Dans (flyttad)",
        start: "2026-10-27T18:00",
        end: "2026-10-27T19:00",
        seriesId: "e-dans",
        originalDate: "2026-10-26",
        rrule: "FREQ=WEEKLY",
      },
    ]);
    const moved = lines.slice(lines.lastIndexOf("BEGIN:VEVENT"));
    expect(moved).toContain("UID:e-dans@pusslet.se");
    expect(moved).toContain("RECURRENCE-ID;TZID=Europe/Stockholm:20261026T170000");
    expect(moved).toContain("DTSTART;TZID=Europe/Stockholm:20261027T180000");
    expect(moved.some((l) => l.startsWith("RRULE"))).toBe(false);
  });

  it("uses dates for all-day events and Z for UTC", () => {
    const lines = calendar([
      { id: "e-lov", title: "Höstlov", start: "2026-10-26T00:00", end: "2026-10-31T00:00", allDay: true },
      { id: "e-dag", title: "Studiedag", start: "2026-11-06T00:00", end: "2026-11-06T00:00", allDay: true },
      { id: "e-utc", title: "Möte", start: "2026-10-21T13:13", end: "2026-10-21T14:13", timeZoneId: "GMT" },
    ]);
    expect(lines).toContain("DTSTART;VALUE=DATE:20261026");
    expect(lines).toContain("DTEND;VALUE=DATE:20261031");
    expect(lines).toContain("DTEND;VALUE=DATE:20261107");
    expect(lines).toContain("DTSTART:20261021T131300Z");
    expect(lines).not.toContain("BEGIN:VTIMEZONE");
  });

  it("escapes text and folds long lines without splitting a character", () => {
    expect(escapeText("Kalas; tårta, ballonger\\nya\nrad")).toBe("Kalas\\; tårta\\, ballonger\\\\nya\\nrad");
    const long = "SUMMARY:" + "Förskolans höstfest med fika ".repeat(5);
    const folded = fold(long);
    for (const line of folded.split("\r\n")) expect(Buffer.byteLength(line, "utf8")).toBeLessThanOrEqual(75);
    expect(folded.replace(/\r\n /g, "")).toBe(long);
  });
});
