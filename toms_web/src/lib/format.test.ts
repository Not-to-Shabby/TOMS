import { describe, expect, it } from 'vitest';
import { categoryLabel, categoryShares, eventLabel, fixAge, pesos, shortId, stopRange } from './format';

describe('pesos', () => {
  it('formats centavos as pesos', () => {
    expect(pesos(1250)).toBe('₱12.50');
    expect(pesos(5)).toBe('₱0.05');
  });
  it('shows a dash for null, undefined and zero so "no fare" is not P0.00', () => {
    expect(pesos(null)).toBe('-');
    expect(pesos(undefined)).toBe('-');
    expect(pesos(0)).toBe('-');
    expect(pesos(0, 'free')).toBe('free');
  });
});

describe('stopRange', () => {
  it('shows the declared stop when nothing differs', () => {
    expect(stopRange('s1', 's2', null)).toBe('s1 ➔ s2');
    expect(stopRange('s1', 's2', 's2')).toBe('s1 ➔ s2');
  });
  it('shows the actual stop and the declared one when the conductor overrode it', () => {
    expect(stopRange('s1', 's2', 's4')).toBe('s1 ➔ s4 (declared s2)');
  });
  it('shows a dash when there are no stops and a ? for a missing end', () => {
    expect(stopRange(null, null, null)).toBe('-');
    expect(stopRange('s1', null, null)).toBe('s1 ➔ ?');
  });
});

describe('labels and ids', () => {
  it('names known events and readable-izes unknown ones', () => {
    expect(eventLabel('trip_created')).toBe('trip');
    expect(eventLabel('card_state_changed')).toBe('card state');
    expect(eventLabel('future_thing')).toBe('future thing');
  });
  it('shortens ids and tolerates missing ones', () => {
    expect(shortId('0123456789abcdef')).toBe('01234567');
    expect(shortId(null)).toBe('-');
  });
  it('capitalizes categories, with regular spelled out', () => {
    expect(categoryLabel('regular')).toBe('Regular fare');
    expect(categoryLabel('pwd')).toBe('Pwd');
    expect(categoryLabel('student')).toBe('Student');
  });
});

describe('fixAge', () => {
  const now = Date.UTC(2026, 9, 8, 12, 0, 0);
  const ago = (s: number) => new Date(now - s * 1000).toISOString();
  it('is null with no fix', () => {
    expect(fixAge(null)).toBeNull();
    expect(fixAge(undefined)).toBeNull();
  });
  it('picks seconds, minutes or hours', () => {
    expect(fixAge(ago(20), now)).toBe('20s ago');
    expect(fixAge(ago(600), now)).toBe('10 min ago');
    expect(fixAge(ago(3 * 3600), now)).toBe('3 h ago');
  });
  it('never goes negative when the phone clock is ahead', () => {
    expect(fixAge(ago(-30), now)).toBe('0s ago');
  });
});

describe('categoryShares', () => {
  it('sums each category over the days, biggest first, as a share of the grand total', () => {
    const shares = categoryShares([
      { by_category: { regular: 10, student: 5 } },
      { by_category: { regular: 20, pwd: 15 } },
    ]);
    expect(shares.map((s) => [s.category, s.revenue])).toEqual([['regular', 30], ['pwd', 15], ['student', 5]]);
    expect(shares.map((s) => Math.round(s.percent))).toEqual([60, 30, 10]);
    expect(shares.reduce((a, s) => a + s.percent, 0)).toBeCloseTo(100);
  });
  it('is empty with no data and never divides by zero', () => {
    expect(categoryShares([])).toEqual([]);
    expect(categoryShares([{ by_category: {} }])).toEqual([]);
    expect(categoryShares([{ by_category: { regular: 0 } }])[0].percent).toBe(0);
  });
  it('is stable for equal revenue', () => {
    const shares = categoryShares([{ by_category: { b: 5, a: 5 } }]);
    expect(shares.map((s) => s.category)).toEqual(['a', 'b']);
  });
});

import { averagePartySize, paidShare, partyText } from './format';

describe('partyText', () => {
  it('lists each type with its count', () => {
    expect(partyText([{ categoryId: null, count: 2 }, { categoryId: 'student', count: 1 }], 3)).toBe('2 regular, 1 student');
  });
  it('falls back to the count for a trip saved before groups', () => {
    expect(partyText([], 1)).toBe('1 passenger');
    expect(partyText(null, 4)).toBe('4 passengers');
    expect(partyText(undefined, 1)).toBe('1 passenger');
  });
});

describe('group figures', () => {
  const days = [
    { total_boardings: 7, total_trips: 3, total_payments: 2 },
    { total_boardings: 2, total_trips: 1, total_payments: 1 },
  ];
  it('averages people per trip, not per day', () => {
    expect(averagePartySize(days)).toBeCloseTo(9 / 4);
  });
  it('measures paid trips against all trips', () => {
    expect(paidShare(days)).toBeCloseTo(75);
  });
  it('is zero rather than NaN with no trips', () => {
    expect(averagePartySize([])).toBe(0);
    expect(paidShare([{ total_payments: 0, total_trips: 0 }])).toBe(0);
  });
});

import { feedMessage } from './format';

describe('feedMessage', () => {
  const base = { vehicle_id: 'BUS-1', card_state: null, passenger_count: 1, fare_centavos: null, destination_stop_id: null };

  it('says how many boarded, where to and for how much', () => {
    expect(feedMessage({ ...base, event_type: 'trip_created', passenger_count: 3, fare_centavos: 8400, destination_stop_id: 's4' }))
      .toBe('🚌 BUS-1: 3 passengers boarded for s4 (₱84.00)');
    expect(feedMessage({ ...base, event_type: 'trip_created' })).toBe('🚌 BUS-1: 1 passenger boarded');
  });

  it('says what a paid group paid and who left when the card comes back', () => {
    expect(feedMessage({ ...base, event_type: 'card_state_changed', card_state: 'ASSIGNED_PAID', passenger_count: 2, fare_centavos: 5400 }))
      .toBe('💰 BUS-1: paid ₱54.00 for 2 passengers');
    expect(feedMessage({ ...base, event_type: 'card_state_changed', card_state: 'RETURNED', passenger_count: 4 }))
      .toBe('🚶 BUS-1: card returned, 4 passengers off');
  });

  it('flags an unpaid return and a lost card so they stand out', () => {
    expect(feedMessage({ ...base, event_type: 'card_state_changed', card_state: 'RETURNED_UNPAID', fare_centavos: 2700 })).toContain('UNPAID');
    expect(feedMessage({ ...base, event_type: 'card_state_changed', card_state: 'LOST' })).toContain('lost');
  });

  it('still produces a line for unknown events and a phone with no vehicle', () => {
    expect(feedMessage({ ...base, event_type: 'future_thing', vehicle_id: null })).toBe('⚡ Unassigned phone: future thing');
    expect(feedMessage({ ...base, event_type: 'card_state_changed', card_state: 'WEIRD_STATE' })).toBe('⚡ BUS-1: card weird state');
  });
});
