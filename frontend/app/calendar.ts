export function startOfMonth(date: Date, offset = 0) {
  return new Date(date.getFullYear(), date.getMonth() + offset, 1);
}

export function monthDays(month: Date) {
  const start = startOfMonth(month);
  const daysInMonth = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate();
  const leading = (start.getDay() + 6) % 7;
  return Array.from({ length: Math.ceil((leading + daysInMonth) / 7) * 7 }, (_, index) => {
    const day = index - leading + 1;
    return day >= 1 && day <= daysInMonth ? new Date(start.getFullYear(), start.getMonth(), day) : null;
  });
}

export function eventRange(month: Date | null, day: Date | null) {
  if (day) {
    const from = new Date(day.getFullYear(), day.getMonth(), day.getDate());
    const until = new Date(day.getFullYear(), day.getMonth(), day.getDate() + 1);
    return { from: from.toISOString(), until: until.toISOString() };
  }
  return { from: month?.toISOString() ?? null, until: month ? startOfMonth(month, 1).toISOString() : null };
}
