/** Centavos to a peso string. Null stays a dash so "no fare" is not shown as P0.00. */
export function pesos(centavos: number | null | undefined, zero = '-'): string {
  if (centavos === null || centavos === undefined) return '-';
  if (centavos === 0) return zero;
  return `₱${(centavos / 100).toFixed(2)}`;
}

export function stopRange(boarding?: string | null, declared?: string | null, actual?: string | null): string {
  if (!boarding && !declared) return '-';
  const dest = actual && actual !== declared ? `${actual} (declared ${declared ?? '?'})` : (declared ?? '?');
  return `${boarding ?? '?'} ➔ ${dest}`;
}

export function shortId(id: string | null | undefined, length = 8): string {
  return id ? id.slice(0, length) : '-';
}

const LABELS: Record<string, string> = {
  trip_created: 'trip',
  card_state_changed: 'card state',
};

export function eventLabel(type: string): string {
  return LABELS[type] ?? type.replace(/_/g, ' ');
}

/** "3 min ago" style age for a GPS fix. Returns null when there is no fix. */
export function fixAge(fixAt: string | null | undefined, now = Date.now()): string | null {
  if (!fixAt) return null;
  const seconds = Math.max(0, Math.round((now - new Date(fixAt).getTime()) / 1000));
  if (seconds < 90) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 90) return `${minutes} min ago`;
  return `${Math.round(minutes / 60)} h ago`;
}

export interface CategoryShare {
  category: string;
  revenue: number;
  percent: number;
}

/** Sums revenue per passenger category across days, largest first. Percent is of the grand total. */
export function categoryShares(days: { by_category: Record<string, number> }[]): CategoryShare[] {
  const totals = new Map<string, number>();
  for (const day of days) {
    for (const [category, revenue] of Object.entries(day.by_category)) {
      totals.set(category, (totals.get(category) ?? 0) + revenue);
    }
  }
  const grand = [...totals.values()].reduce((a, b) => a + b, 0);
  return [...totals.entries()]
    .map(([category, revenue]) => ({ category, revenue, percent: grand > 0 ? (revenue / grand) * 100 : 0 }))
    .sort((a, b) => b.revenue - a.revenue || a.category.localeCompare(b.category));
}

export function categoryLabel(category: string): string {
  return category === 'regular' ? 'Regular fare' : category.charAt(0).toUpperCase() + category.slice(1);
}

interface PartyLine {
  categoryId: string | null;
  count: number;
}

/** "2 regular, 1 student" for a group. An old trip with no lines is "1 regular" (or the count it had). */
export function partyText(lines: PartyLine[] | null | undefined, count: number): string {
  if (!lines || lines.length === 0) return count === 1 ? '1 passenger' : `${count} passengers`;
  return lines.map((l) => `${l.count} ${(l.categoryId ?? 'regular').toLowerCase()}`).join(', ');
}

/** People per paid-or-unpaid trip over the period. 0 when there were no trips. */
export function averagePartySize(days: { total_boardings: number; total_trips: number }[]): number {
  const trips = days.reduce((n, d) => n + d.total_trips, 0);
  return trips > 0 ? days.reduce((n, d) => n + d.total_boardings, 0) / trips : 0;
}

/** Percent of trips that have been paid. Trips, not people: one card is paid once for the whole group. */
export function paidShare(days: { total_payments: number; total_trips: number }[]): number {
  const trips = days.reduce((n, d) => n + d.total_trips, 0);
  return trips > 0 ? (days.reduce((n, d) => n + d.total_payments, 0) / trips) * 100 : 0;
}
