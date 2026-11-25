import { describe, expect, it } from "vitest";
import { eventRange, monthDays, startOfMonth } from "./calendar";

describe("Soleil events calendar", () => {
  it("starts on Monday and includes February 29", () => {
    const cells = monthDays(new Date(2024, 1, 20));
    expect(cells.slice(0, 3)).toEqual([null, null, null]);
    expect(cells[3]?.getDate()).toBe(1);
    expect(cells.filter(Boolean)).toHaveLength(29);
    expect(cells[31]?.getDate()).toBe(29);
  });

  it("moves between years and keeps an exclusive end boundary", () => {
    const december = startOfMonth(new Date(2026, 11, 22));
    const january = startOfMonth(december, 1);
    expect(january).toEqual(new Date(2027, 0, 1));
    expect(eventRange(december, null)).toEqual({ from: december.toISOString(), until: january.toISOString() });
  });

  it("uses local midnight at both ends of the selected day", () => {
    const day = new Date(2026, 2, 29, 18);
    expect(eventRange(startOfMonth(day), day)).toEqual({
      from: new Date(2026, 2, 29).toISOString(),
      until: new Date(2026, 2, 30).toISOString(),
    });
    expect(eventRange(null, null)).toEqual({ from: null, until: null });
  });
});
