import { allowedMoves, type CardStatus } from './cardLifecycle';

/** The most cards one bulk change or reprint can take. The server enforces the same number. */
export const BULK_LIMIT = 500;

export function toggle(selected: ReadonlySet<string>, id: string): Set<string> {
  const next = new Set(selected);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

/** Selects every id, or clears them all if they are all already selected. */
export function toggleAll(selected: ReadonlySet<string>, ids: readonly string[]): Set<string> {
  const all = ids.length > 0 && ids.every((id) => selected.has(id));
  const next = new Set(selected);
  for (const id of ids) {
    if (all) next.delete(id);
    else next.add(id);
  }
  return next;
}

export type CheckState = 'none' | 'some' | 'all';

export function checkState(selected: ReadonlySet<string>, ids: readonly string[]): CheckState {
  if (ids.length === 0) return 'none';
  const n = ids.filter((id) => selected.has(id)).length;
  return n === 0 ? 'none' : n === ids.length ? 'all' : 'some';
}

/** Forgets selected ids that are no longer in the list (after a filter or a reload). */
export function prune(selected: ReadonlySet<string>, visible: readonly string[]): Set<string> {
  const keep = new Set(visible);
  return new Set([...selected].filter((id) => keep.has(id)));
}

export interface SelectedCard {
  card_uuid: string;
  status: CardStatus;
  nfc_uid: string | null;
}

/**
 * The status changes that every selected card can make. A change is only offered when it is legal for the
 * whole selection, so the all-or-nothing request has a good chance of going through.
 */
export function commonMoves(cards: readonly SelectedCard[]): CardStatus[] {
  if (cards.length === 0) return [];
  const lists = cards.map((c) => allowedMoves(c.status, c.nfc_uid !== null));
  return lists[0].filter((to) => lists.every((l) => l.includes(to)));
}

/** Counts by status, for "3 active, 1 lost". */
export function statusCounts(cards: readonly { status: string }[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const c of cards) out[c.status] = (out[c.status] ?? 0) + 1;
  return out;
}

export function describeCounts(counts: Record<string, number>): string {
  return Object.entries(counts)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([status, n]) => `${n} ${status}`)
    .join(', ');
}

export interface BulkProblem {
  card_uuid: string;
  serial: string | null;
  error: string;
  open_trips?: number;
}

/** A short list of what went wrong, capped so a big refusal does not fill the screen. */
export function summarizeProblems(problems: readonly BulkProblem[], max = 5): { lines: string[]; more: number } {
  const lines = problems.slice(0, max).map((p) => `${p.serial ?? p.card_uuid.slice(0, 8)}: ${p.error}`);
  return { lines, more: Math.max(0, problems.length - max) };
}
