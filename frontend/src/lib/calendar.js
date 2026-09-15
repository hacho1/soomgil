export function monthCells(year, month) {
  const offset = new Date(year, month, 1).getDay();
  const days = new Date(year, month + 1, 0).getDate();
  const total = Math.ceil((offset + days) / 7) * 7;
  return Array.from({ length: total }, (_, index) => {
    const day = index - offset + 1;
    return day < 1 || day > days ? null : { day, date: `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}` };
  });
}
