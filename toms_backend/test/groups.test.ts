import { randomUUID } from 'crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let device: string;
let admin: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE admins, devices, vehicles, events RESTART IDENTITY CASCADE');
  await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111')");
  device = await enrollDevice(pool, 'dev-1', 'BUS-1');
  admin = await adminToken(pool, app);
});

type Line = { categoryId: string | null; count: number; perPersonCentavos: number; fareCentavos: number; discountCentavos: number };
const line = (categoryId: string | null, count: number, per: number, discountEach = 0): Line => ({
  categoryId,
  count,
  perPersonCentavos: per,
  fareCentavos: per * count,
  discountCentavos: discountEach * count,
});

let seq = 0;
type Trip = ReturnType<typeof groupTrip>;

function groupTrip(lines: Line[] | null, over: Record<string, unknown> = {}) {
  const total = lines ? lines.reduce((n, l) => n + l.fareCentavos, 0) : 1500;
  const discount = lines ? lines.reduce((n, l) => n + l.discountCentavos, 0) : 0;
  const count = lines ? lines.reduce((n, l) => n + l.count, 0) : 1;
  const categories = lines ? [...new Set(lines.map((l) => l.categoryId))] : [null];
  seq += 1;
  return {
    event_id: randomUUID(),
    device_id: 'dev-1',
    local_seq: seq,
    type: 'trip_created',
    created_at_millis: Date.now(),
    payload: {
      tripId: randomUUID(),
      cardUuid: randomUUID(),
      nfcUid: `AABBCC${String(seq).padStart(4, '0')}`,
      boardingStopId: 's1',
      declaredDestinationStopId: 's2',
      discountCategoryId: categories.length === 1 ? categories[0] : null,
      computedFareCentavos: total,
      fareCentavos: total,
      discountCentavos: discount,
      fareVersion: 0,
      ...(lines ? { passengerCount: count, passengers: lines } : {}),
      ...over,
    },
  };
}

function cardState(trip: Trip, state: string, offsetMs = 1000) {
  seq += 1;
  return {
    event_id: randomUUID(),
    device_id: 'dev-1',
    local_seq: seq,
    type: 'card_state_changed',
    created_at_millis: Date.now() + offsetMs,
    payload: { nfcUid: trip.payload.nfcUid, cardUuid: trip.payload.cardUuid, tripId: trip.payload.tripId, state },
  };
}

const pay = (trip: Trip) => cardState(trip, 'ASSIGNED_PAID', 1000);
const giveBack = (trip: Trip) => cardState(trip, 'RETURNED', 2000);
const send = (events: unknown[]) =>
  request(app).post('/api/events').set('Authorization', `Bearer ${device}`).send({ events });
const get = (path: string) => request(app).get(path).set('Authorization', `Bearer ${admin}`);

describe('group trips: ingest', () => {
  it('stores the passenger count and every line', async () => {
    const family = groupTrip([line(null, 2, 2800), line('student', 1, 2200, 600)]);
    expect((await send([family])).body.accepted).toHaveLength(1);
    const row = (await pool.query('SELECT passenger_count, passengers FROM events')).rows[0];
    expect(row.passenger_count).toBe(3);
    expect(row.passengers).toEqual([line(null, 2, 2800), line('student', 1, 2200, 600)]);
  });

  it('treats a trip from a phone that predates groups as one passenger', async () => {
    expect((await send([groupTrip(null)])).body.accepted).toHaveLength(1);
    const row = (await pool.query('SELECT passenger_count, passengers FROM events')).rows[0];
    expect(row).toEqual({ passenger_count: 1, passengers: [] });
  });

  it('rejects lines that do not add up to the count, the fare or the discount', async () => {
    const base = [line(null, 2, 2800)];
    const wrongCount = groupTrip(base, { passengerCount: 5 });
    const wrongFare = groupTrip(base, { computedFareCentavos: 5000, fareCentavos: 5000 });
    const wrongDiscount = groupTrip(base, { discountCentavos: 100 });
    const res = await send([wrongCount, wrongFare, wrongDiscount]);
    expect(res.body.accepted).toEqual([]);
    expect(res.body.rejected).toHaveLength(3);
    const reasons = res.body.rejected.map((r: { reason: string }) => r.reason);
    expect(reasons[0]).toMatch(/passengerCount/);
    expect(reasons[1]).toMatch(/computedFareCentavos/);
    expect(reasons[2]).toMatch(/discountCentavos/);
  });

  it('rejects an oversized group, a zero count, a repeated type, a bad count and a negative fare', async () => {
    const cases = [
      groupTrip([line(null, 31, 100)]),
      groupTrip([{ ...line(null, 1, 100), count: 0 }], { passengerCount: 0 }),
      groupTrip([line(null, 1, 100), line(null, 1, 100)]),
      groupTrip([line(null, 1, 100)], { passengerCount: 31 }),
      groupTrip([line(null, 1, -5)]),
    ];
    const res = await send(cases);
    expect(res.body.accepted).toEqual([]);
    expect(res.body.rejected).toHaveLength(cases.length);
  });

  it('says why a group is too large, rather than leaving that to the database', async () => {
    const res = await send([groupTrip([line(null, 31, 100)])]);
    expect(res.body.rejected).toHaveLength(1);
    expect(res.body.rejected[0].reason).not.toMatch(/database rejected/);
    expect(res.body.rejected[0].reason).toMatch(/count|passengers/);
  });

  it('accepts a full group of 30 and keeps the good events when one group in the batch is bad', async () => {
    const full = groupTrip([line(null, 20, 100), line('student', 10, 80, 20)]);
    const bad = groupTrip([line(null, 2, 100)], { passengerCount: 9 });
    const res = await send([bad, full]);
    expect(res.body.accepted).toEqual([full.event_id]);
    expect(res.body.rejected).toHaveLength(1);
    expect((await pool.query('SELECT passenger_count FROM events')).rows[0].passenger_count).toBe(30);
  });

  it('is idempotent: re-sending a group is a duplicate, and changing the group is refused', async () => {
    const family = groupTrip([line(null, 2, 2800)]);
    await send([family]);
    expect((await send([family])).body.duplicates).toEqual([family.event_id]);

    const changed = {
      ...family,
      payload: {
        ...family.payload,
        passengerCount: 3,
        passengers: [line(null, 3, 2800)],
        computedFareCentavos: 8400,
        fareCentavos: 8400,
      },
    };
    const res = await send([changed]);
    expect(res.body.rejected).toHaveLength(1);
    expect(res.body.rejected[0].reason).toMatch(/different content/);
    expect((await pool.query('SELECT passenger_count FROM events')).rows[0].passenger_count).toBe(2);
  });
});

describe('group trips: database', () => {
  const insert = (count: number, passengers: string) =>
    pool.query(
      `INSERT INTO events (event_id, device_id, local_seq, event_type, occurred_at, passenger_count, passengers, payload)
       VALUES ($1, 'd', 1, 'x', now(), $2, $3::jsonb, '{}')`,
      [randomUUID(), count, passengers],
    );

  it('refuses lines that disagree with the count even if the application layer is bypassed', async () => {
    await expect(insert(4, JSON.stringify([line(null, 2, 100)]))).rejects.toThrow(/events_passenger_lines_match/);
    await insert(2, JSON.stringify([line(null, 2, 100)]));
  });

  it('refuses a passenger count outside 1 to 30 and a passengers value that is not an array', async () => {
    await expect(insert(0, '[]')).rejects.toThrow();
    await expect(insert(31, '[]')).rejects.toThrow();
    await expect(insert(1, '{}')).rejects.toThrow();
    await insert(30, '[]');
  });
});

describe('group trips: dashboard', () => {
  it('counts people, not cards, as occupancy', async () => {
    await send([groupTrip([line(null, 2, 2800), line('student', 1, 2200, 600)]), groupTrip(null)]);
    expect((await get('/api/fleet/status')).body[0].occupancy_now).toBe(4);
  });

  it('stops counting a group once its card is returned', async () => {
    const family = groupTrip([line(null, 3, 1000)]);
    const other = groupTrip([line(null, 2, 1000)]);
    await send([family, other, pay(family), giveBack(family)]);
    expect((await get('/api/fleet/status')).body[0].occupancy_now).toBe(2);
  });

  it("collects the whole group's fare once, when the card is paid", async () => {
    const family = groupTrip([line(null, 2, 2800), line('student', 1, 2200, 600)]);
    await send([family]);
    expect((await get('/api/fleet/status')).body[0].daily_revenue).toBe(0);
    await send([pay(family)]);
    expect((await get('/api/fleet/status')).body[0].daily_revenue).toBe(7800);
  });

  it('reports revenue and passengers per type from the lines', async () => {
    const family = groupTrip([line(null, 2, 2800), line('student', 1, 2200, 600)]);
    const pair = groupTrip([line('pwd', 2, 2200, 600)]);
    const unpaid = groupTrip([line(null, 4, 2800)]);
    await send([family, pay(family), pair, pay(pair), unpaid]);
    const today = (await get('/api/analytics/revenue?days=7')).body.at(-1);
    expect(today.by_category).toEqual({ regular: 56, student: 22, pwd: 44 });
    expect(today.passengers_by_category).toEqual({ regular: 2, student: 1, pwd: 2 });
    expect(today).toMatchObject({ total_revenue: 122, regular_revenue: 56, discounted_revenue: 66, discount_given: 18, total_payments: 2 });
    expect(today.total_trips).toBe(3);
    expect(today.total_boardings).toBe(9); // 3 + 2 + 4 people, including the unpaid group
  });

  it('keeps the charged total when the conductor overrode the fare, and the lines for the split', async () => {
    const waived = groupTrip([line(null, 2, 2800)], { fareCentavos: 0 });
    await send([waived, pay(waived)]);
    const today = (await get('/api/analytics/revenue?days=7')).body.at(-1);
    expect(today.total_revenue).toBe(0);
    expect(today.by_category.regular).toBe(56);
  });

  it('shows a trip from before groups as one regular passenger in the per-type split', async () => {
    const old = groupTrip(null);
    await send([old, pay(old)]);
    const today = (await get('/api/analytics/revenue?days=7')).body.at(-1);
    expect(today.by_category).toEqual({ regular: 15 });
    expect(today.passengers_by_category).toEqual({ regular: 1 });
    expect(today.total_boardings).toBe(1);
  });

  it('puts the passenger count in the audit log and the CSV', async () => {
    await send([groupTrip([line(null, 3, 1000)])]);
    const log = (await get('/api/audit/logs')).body.logs[0];
    expect(log.passenger_count).toBe(3);
    expect(log.passengers).toHaveLength(1);
    const csv = (await get('/api/export/audit')).text;
    const rows = csv.trimEnd().split('\r\n');
    expect(rows[0].split(',').at(-1)).toBe('Passengers');
    expect(rows[1].split(',').at(-1)).toBe('3');
  });
});

describe('live feed events', () => {
  it('sends one new_event per trip and card change, with the trip fare and party size on a payment', async () => {
    const emitted: { event: string; payload: any }[] = [];
    const feedApp = createApp({ pool, jwtSecret: TEST_JWT_SECRET, emit: (event, payload) => emitted.push({ event, payload }) });
    const family = groupTrip([line(null, 2, 2800), line('student', 1, 2200, 600)]);
    const post = (events: unknown[]) =>
      request(feedApp).post('/api/events').set('Authorization', `Bearer ${device}`).send({ events });

    await post([family, pay(family)]);

    const feed = emitted.filter((e) => e.event === 'new_event').map((e) => e.payload);
    expect(feed).toHaveLength(2);
    expect(feed[0]).toMatchObject({ event_type: 'trip_created', passenger_count: 3, fare_centavos: 7800 });
    expect(feed[1]).toMatchObject({ event_type: 'card_state_changed', card_state: 'ASSIGNED_PAID', passenger_count: 3, fare_centavos: 7800 });
  });

  it('emits events oldest first, so the feed reads in the order things happened', async () => {
    const emitted: any[] = [];
    const feedApp = createApp({ pool, jwtSecret: TEST_JWT_SECRET, emit: (e, p) => e === 'new_event' && emitted.push(p) });
    const trip = groupTrip([line(null, 1, 1500)]);
    await request(feedApp).post('/api/events').set('Authorization', `Bearer ${device}`).send({ events: [trip, pay(trip), giveBack(trip)] });
    expect(emitted.map((p) => p.card_state ?? p.event_type)).toEqual(['trip_created', 'ASSIGNED_PAID', 'RETURNED']);
  });

  it('limits a catch-up batch to 20 lines and sends nothing for events it ignores or already has', async () => {
    const emitted: any[] = [];
    const feedApp = createApp({ pool, jwtSecret: TEST_JWT_SECRET, emit: (e, p) => e === 'new_event' && emitted.push(p) });
    const post = (events: unknown[]) => request(feedApp).post('/api/events').set('Authorization', `Bearer ${device}`).send({ events });

    const many = Array.from({ length: 30 }, () => groupTrip(null));
    await post(many);
    expect(emitted).toHaveLength(20);

    emitted.length = 0;
    await post(many); // all duplicates now
    expect(emitted).toHaveLength(0);

    await post([{ event_id: randomUUID(), device_id: 'dev-1', local_seq: ++seq, type: 'future_thing', created_at_millis: Date.now(), payload: {} }]);
    expect(emitted).toHaveLength(0); // an event type the feed does not describe
  });

  it('a feed failure never fails the phone request', async () => {
    const feedApp = createApp({ pool, jwtSecret: TEST_JWT_SECRET, emit: () => { throw new Error('socket down'); } });
    const res = await request(feedApp).post('/api/events').set('Authorization', `Bearer ${device}`).send({ events: [groupTrip(null)] });
    expect(res.status).toBe(200);
    expect(res.body.accepted).toHaveLength(1);
  });
});
