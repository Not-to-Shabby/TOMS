import { describe, expect, it } from 'vitest';
import { BULK_LIMIT, checkState, commonMoves, describeCounts, prune, statusCounts, summarizeProblems, toggle, toggleAll } from './cardSelection';

describe('selecting', () => {
  it('toggles one card on and off without changing the original set', () => {
    const empty = new Set<string>();
    const one = toggle(empty, 'a');
    expect([...one]).toEqual(['a']);
    expect(empty.size).toBe(0);
    expect(toggle(one, 'a').size).toBe(0);
  });

  it('selects all of a group, or clears the group when all are already selected', () => {
    const ids = ['a', 'b', 'c'];
    expect([...toggleAll(new Set(), ids)].sort()).toEqual(['a', 'b', 'c']);
    expect([...toggleAll(new Set(['a']), ids)].sort()).toEqual(['a', 'b', 'c']);
    expect(toggleAll(new Set(ids), ids).size).toBe(0);
  });

  it('only touches its own group, leaving other selected cards alone', () => {
    const next = toggleAll(new Set(['x', 'a', 'b']), ['a', 'b']);
    expect([...next]).toEqual(['x']);
  });

  it('does nothing for an empty group', () => {
    expect(toggleAll(new Set(['x']), []).size).toBe(1);
  });

  it('reports none, some or all for a group', () => {
    expect(checkState(new Set(), ['a', 'b'])).toBe('none');
    expect(checkState(new Set(['a']), ['a', 'b'])).toBe('some');
    expect(checkState(new Set(['a', 'b', 'z']), ['a', 'b'])).toBe('all');
    expect(checkState(new Set(['a']), [])).toBe('none');
  });

  it('forgets selected cards that left the list', () => {
    expect([...prune(new Set(['a', 'gone']), ['a', 'b'])]).toEqual(['a']);
  });

  it('uses the same limit as the server', () => {
    expect(BULK_LIMIT).toBe(500);
  });
});

describe('commonMoves', () => {
  const card = (status: string, chip = true) => ({ card_uuid: status, status: status as never, nfc_uid: chip ? '04AA' : null });

  it('offers what every selected card can do', () => {
    expect(commonMoves([card('active'), card('active')])).toEqual(['suspended', 'lost', 'retired']);
  });

  it('narrows to the moves all of them share', () => {
    // active: suspended, lost, retired. lost: active, retired.
    expect(commonMoves([card('active'), card('lost')])).toEqual(['retired']);
  });

  it('offers nothing when the cards have no move in common', () => {
    expect(commonMoves([card('active'), card('retired')])).toEqual([]);
  });

  it('is empty for no selection, and handles a chipless retired card', () => {
    expect(commonMoves([])).toEqual([]);
    expect(commonMoves([card('retired', false)])).toEqual(['unpaired']);
  });
});

describe('describing a selection', () => {
  it('counts by status and words it largest first', () => {
    const counts = statusCounts([{ status: 'active' }, { status: 'active' }, { status: 'lost' }, { status: 'active' }]);
    expect(counts).toEqual({ active: 3, lost: 1 });
    expect(describeCounts(counts)).toBe('3 active, 1 lost');
  });

  it('breaks a tie alphabetically so the text does not jump around', () => {
    expect(describeCounts({ lost: 1, active: 1 })).toBe('1 active, 1 lost');
  });

  it('lists problem cards and says how many more there are', () => {
    const problems = Array.from({ length: 7 }, (_, i) => ({ card_uuid: `id-${i}`, serial: i === 0 ? null : `TOMS-${i}`, error: 'cannot' }));
    const { lines, more } = summarizeProblems(problems);
    expect(lines).toHaveLength(5);
    expect(lines[0]).toBe('id-0: cannot');
    expect(lines[1]).toBe('TOMS-1: cannot');
    expect(more).toBe(2);
    expect(summarizeProblems(problems.slice(0, 2)).more).toBe(0);
  });
});
