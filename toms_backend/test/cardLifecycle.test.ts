import { describe, expect, it } from 'vitest';
import { CARD_STATUSES, NEEDS_REASON, WARNS_OPEN_TRIPS, allowedMoves, checkMove, type CardStatus } from '../src/cardLifecycle';

describe('allowed status moves', () => {
  it('lets an active card be suspended, lost or retired, and nothing else', () => {
    expect(allowedMoves('active', true)).toEqual(['suspended', 'lost', 'retired']);
  });

  it('lets a suspended card be reactivated, lost or retired', () => {
    expect(allowedMoves('suspended', true)).toEqual(['active', 'lost', 'retired']);
  });

  it('lets a lost card be found again or retired', () => {
    expect(allowedMoves('lost', true)).toEqual(['active', 'retired']);
  });

  it('only lets an unpaired card be retired; pairing is how it becomes active', () => {
    expect(allowedMoves('unpaired', false)).toEqual(['retired']);
  });

  it('restores a retired card to active when it has its chip, to unpaired when it never had one', () => {
    expect(allowedMoves('retired', true)).toEqual(['active']);
    expect(allowedMoves('retired', false)).toEqual(['unpaired']);
  });

  it('never allows a move to the status the card is already in', () => {
    for (const s of CARD_STATUSES) {
      for (const chip of [true, false]) expect(allowedMoves(s, chip)).not.toContain(s);
    }
  });

  it('only offers statuses that exist', () => {
    for (const s of CARD_STATUSES) {
      for (const chip of [true, false]) {
        for (const to of allowedMoves(s, chip)) expect(CARD_STATUSES).toContain(to);
      }
    }
  });
});

describe('checkMove', () => {
  it('accepts a legal move', () => {
    expect(checkMove('active', 'lost', true)).toEqual({ ok: true });
  });

  it('tells the admin to pair first when asking an unpaired card to be active or suspended', () => {
    for (const to of ['active', 'suspended'] as CardStatus[]) {
      const r = checkMove('unpaired', to, false);
      expect(r.ok).toBe(false);
      if (!r.ok) expect(r.error).toMatch(/Pair it first/);
    }
  });

  it('tells the admin to restore a retired card first', () => {
    const r = checkMove('retired', 'lost', true);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/Restore it first/);
  });

  it('explains an illegal move in plain words', () => {
    const r = checkMove('lost', 'suspended', true);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toBe('A lost card cannot be changed to suspended.');
  });
});

describe('which changes need a reason or a warning', () => {
  it('needs a reason to mark a card lost or retired', () => {
    expect([...NEEDS_REASON].sort()).toEqual(['lost', 'retired']);
  });

  it('warns about open trips for suspend, lost and retire', () => {
    expect([...WARNS_OPEN_TRIPS].sort()).toEqual(['lost', 'retired', 'suspended']);
  });
});
