import { randomUUID } from 'crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let deviceToken: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE events, devices, vehicles RESTART IDENTITY CASCADE');
  deviceToken = await enrollDevice(pool, 'dev-1');
});

const T0 = Date.UTC(2026, 9, 8, 1, 0, 0);
const NFC = '6FF1AD39';
const shiftId = randomUUID();

const post = (body: unknown) =>
  request(app).post('/api/events').set('Authorization', `Bearer ${deviceToken}`).send(body as object);
const count = async () => Number((await pool.query('SELECT count(*) FROM events')).rows[0].count);

function event(type: string, seq: number, payload: Record<string, unknown>) {
  return { event_id: randomUUID(), device_id: 'dev-1', local_seq: seq, type, created_at_millis: T0 + seq * 1000, payload };
}

function tripCreated(seq: number, tripId: string, cardUuid: string) {
  return event('trip_created', seq, {
    tripId, cardUuid, nfcUid: NFC, boardingStopId: 's1', declaredDestinationStopId: 's2',
    actualDestinationStopId: null, discountCategoryId: null, computedFareCentavos: 1500, fareCentavos: 1500,
    discountCentavos: 0, fareVersion: 0, overrideReason: null, shiftId,
  });
}

function cardState(seq: number, tripId: string, cardUuid: string, state: string) {
  return event('card_state_changed', seq, { nfcUid: NFC, cardUuid, tripId, state, shiftId });
}

function auditPayload(tripId: string, cardUuid: string) {
  return {
    shiftId, tripId, cardUuid, nfcUid: NFC, amountCentavos: 1500,
    reason: 'uncollected at end of shift', shiftConductor: 'juan', actingConductor: 'maria',
  };
}

/** The order the phone writes at End Shift for a card still with an unpaid passenger. */
function endOfShiftBatch(tripId: string, cardUuid: string) {
  return [
    event('shift_started', 1, { shiftId, conductor: 'juan', vehicleId: 'v1', routeId: 'r1' }),
    tripCreated(2, tripId, cardUuid),
    event('fare_written_off', 3, auditPayload(tripId, cardUuid)),
    event('conductor_released_unpaid', 4, auditPayload(tripId, cardUuid)),
    cardState(5, tripId, cardUuid, 'AVAILABLE'),
    event('shift_ended', 6, { shiftId, conductor: 'juan', endedBy: 'maria', summary: { collectedCashCentavos: 0 } }),
  ];
}

const status = async (tripId: string) =>
  (await pool.query('SELECT current_state, paid FROM trip_status WHERE trip_id = $1', [tripId])).rows[0];

describe('shift and write-off events', () => {
  it('accepts all four new event types and stores each exactly as the phone sent it', async () => {
    const batch = endOfShiftBatch(randomUUID(), randomUUID());

    const res = await post({ events: batch });
    expect(res.status).toBe(200);
    expect(res.body.rejected).toEqual([]);
    expect(res.body.accepted).toEqual(batch.map((e) => e.event_id));

    for (const type of ['shift_started', 'fare_written_off', 'conductor_released_unpaid', 'shift_ended']) {
      const row = (await pool.query('SELECT payload FROM events WHERE event_type = $1', [type])).rows[0];
      expect(row.payload, type).toEqual(batch.find((e) => e.type === type)!.payload);
    }
    const audit = (await pool.query("SELECT payload FROM events WHERE event_type = 'fare_written_off'")).rows[0].payload;
    expect(audit).toMatchObject({ shiftConductor: 'juan', actingConductor: 'maria', amountCentavos: 1500, shiftId });
  });

  it('shows a written off and released trip as not paid, with the card available', async () => {
    const tripId = randomUUID();
    await post({ events: endOfShiftBatch(tripId, randomUUID()) });
    expect(await status(tripId)).toEqual({ current_state: 'AVAILABLE', paid: false });
  });

  it('does not show a written off trip as paid even though its card was freed', async () => {
    // The dashboard counts a trip as paid when it was ever paid or returned. A release is neither.
    const tripId = randomUUID();
    const cardUuid = randomUUID();
    await post({ events: [tripCreated(1, tripId, cardUuid), event('fare_written_off', 2, auditPayload(tripId, cardUuid)), cardState(3, tripId, cardUuid, 'AVAILABLE')] });
    expect((await status(tripId)).paid).toBe(false);
  });

  it('records no terminal state for a card that was only released', async () => {
    const tripId = randomUUID();
    await post({ events: endOfShiftBatch(tripId, randomUUID()) });
    const states = (await pool.query(
      'SELECT card_state FROM events WHERE trip_id = $1 AND card_state IS NOT NULL ORDER BY local_seq', [tripId],
    )).rows;
    expect(states.map((r) => r.card_state)).toEqual(['AVAILABLE']);
  });

  it('keeps the true terminal return of a card that came back unpaid and was then written off', async () => {
    const tripId = randomUUID();
    const cardUuid = randomUUID();
    await post({
      events: [
        tripCreated(1, tripId, cardUuid),
        cardState(2, tripId, cardUuid, 'RETURNED_UNPAID'),
        event('fare_written_off', 3, auditPayload(tripId, cardUuid)),
        cardState(4, tripId, cardUuid, 'AVAILABLE'),
      ],
    });
    const states = (await pool.query(
      'SELECT card_state FROM events WHERE trip_id = $1 AND card_state IS NOT NULL ORDER BY local_seq', [tripId],
    )).rows;
    expect(states.map((r) => r.card_state)).toEqual(['RETURNED_UNPAID', 'AVAILABLE']);
    expect(await status(tripId)).toEqual({ current_state: 'AVAILABLE', paid: false });
  });

  it('treats a re-sent end of shift batch as duplicates and stores nothing new', async () => {
    const batch = endOfShiftBatch(randomUUID(), randomUUID());
    await post({ events: batch });
    const again = await post({ events: batch });
    expect(again.body.accepted).toEqual([]);
    expect(again.body.duplicates).toEqual(batch.map((e) => e.event_id));
    expect(await count()).toBe(batch.length);
  });

  it('keeps the shift tag inside the trip and card state payloads', async () => {
    const tripId = randomUUID();
    await post({ events: endOfShiftBatch(tripId, randomUUID()) });
    const rows = (await pool.query(
      "SELECT event_type, payload->>'shiftId' AS shift FROM events WHERE event_type IN ('trip_created', 'card_state_changed')",
    )).rows;
    expect(rows.length).toBe(2);
    for (const r of rows) expect(r.shift).toBe(shiftId);
  });
});
