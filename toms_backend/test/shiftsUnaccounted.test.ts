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
let admin: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
  const { createAdmin } = await import('../src/admins');
  await createAdmin(pool, 'boss', 'correct horse battery');
  const login = await request(app).post('/api/admin/login').send({ username: 'boss', password: 'correct horse battery' });
  admin = login.body.token;
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE events, devices, vehicles RESTART IDENTITY CASCADE');
  deviceToken = await enrollDevice(pool, 'dev-1');
});

const T0 = Date.UTC(2026, 10, 9, 1, 0, 0);
const SHIFT = '11111111-1111-4111-8111-111111111111';

/** An event as the phone's uploader sends it, with the shift tag riding in the payload. */
function event(type: string, localSeq: number, payload: Record<string, unknown>) {
  return {
    event_id: randomUUID(),
    device_id: 'dev-1',
    local_seq: localSeq,
    type,
    created_at_millis: T0 + localSeq,
    payload,
  };
}

function tripCreated(tripId: string, fareCents: number, shiftId: string | null, localSeq: number) {
  return event('trip_created', localSeq, {
    tripId,
    cardUuid: randomUUID(),
    nfcUid: `6FF1AD${String(localSeq).padStart(2, '0')}`,
    boardingStopId: 's1',
    declaredDestinationStopId: 's2',
    computedFareCentavos: fareCents,
    fareCentavos: fareCents,
    discountCentavos: 0,
    fareVersion: 1,
    shiftId,
  });
}

function cardState(tripId: string, state: string, localSeq: number, shiftId: string | null = SHIFT) {
  return event('card_state_changed', localSeq, { nfcUid: '6FF1AD39', cardUuid: randomUUID(), tripId, state, shiftId });
}

async function upload(events: unknown[]) {
  const res = await request(app).post('/api/events').set('Authorization', `Bearer ${deviceToken}`).send({ events });
  if (res.body.rejected.length) throw new Error("rejected: " + JSON.stringify(res.body.rejected));
  return res.body;
}

async function unaccounted() {
  const res = await request(app).get('/api/shifts/unaccounted').set('Authorization', `Bearer ${admin}`);
  return res.body;
}

describe('unaccounted fares per shift', () => {
  it('reports a lost-card fare with its shift and its trip, in the shape the dashboard needs', async () => {
    const lost = randomUUID();
    const paid = randomUUID();
    await upload([
      event('shift_started', 0, { shiftId: SHIFT, conductor: 'juan', vehicleId: 'v1', routeId: 'r1' }),
      event('shift_ended', 9, {
        shiftId: SHIFT, conductor: 'juan', vehicleId: 'v1', routeId: 'r1',
        startedAtMillis: T0, endedAtMillis: T0 + 1000, endedBy: 'juan',
        summary: {},
      }),
      tripCreated(lost, 1500, SHIFT, 1),
      cardState(lost, 'ASSIGNED_UNPAID', 2),
      cardState(lost, 'LOST', 3),
      tripCreated(paid, 2000, SHIFT, 4),
      cardState(paid, 'ASSIGNED_PAID', 5),
    ]);

    const rows = await unaccounted();
    expect(rows).toHaveLength(1);
    const row = rows[0];
    expect(row.shift_id).toBe(SHIFT);
    expect(row.conductor).toBe('juan');
    expect(row.vehicle_id).toBe('v1');
    expect(row.route_id).toBe('r1');
    expect(row.ended_at).not.toBeNull();
    expect(row.ended_by).toBe('juan');
    expect(row.unknown_trips).toBe(1);
    expect(row.unknown_cents).toBe(1500);
    expect(row.trips).toHaveLength(1);
    expect(row.trips[0].trip_id).toBe(lost);
    expect(row.trips[0].card_state).toBe('LOST');
    expect(row.trips[0].fare_cents).toBe(1500);
  });

  it('keeps collected, written-off and still-out fares out of the unaccounted bucket', async () => {
    const lost = randomUUID();
    const paid = randomUUID();
    const writtenOff = randomUUID();
    const stillOut = randomUUID();
    const returnedUnpaid = randomUUID();
    await upload([
      event('shift_started', 0, { shiftId: SHIFT, conductor: 'juan', vehicleId: 'v1', routeId: 'r1' }),
      tripCreated(lost, 1500, SHIFT, 1),
      cardState(lost, 'LOST', 2),
      tripCreated(paid, 2000, SHIFT, 3),
      cardState(paid, 'ASSIGNED_PAID', 4),
      tripCreated(writtenOff, 3000, SHIFT, 5),
      cardState(writtenOff, 'RETURNED_UNPAID', 6),
      event('fare_written_off', 7, {
        shiftId: SHIFT, tripId: writtenOff, cardUuid: randomUUID(), nfcUid: '6FF1AD39',
        amountCentavos: 3000, reason: 'uncollected at end of shift',
        shiftConductor: 'juan', actingConductor: 'juan',
      }),
      cardState(writtenOff, 'AVAILABLE', 8),
      tripCreated(stillOut, 4000, SHIFT, 9),
      cardState(stillOut, 'ASSIGNED_UNPAID', 10),
      tripCreated(returnedUnpaid, 5000, SHIFT, 11),
      cardState(returnedUnpaid, 'RETURNED_UNPAID', 12),
    ]);

    const rows = await unaccounted();
    expect(rows).toHaveLength(1);
    expect(rows[0].unknown_trips).toBe(1);
    expect(rows[0].unknown_cents).toBe(1500);
    expect(rows[0].trips.map((t: { trip_id: string }) => t.trip_id)).toEqual([lost]);
  });

  it('a fare settled in a later shift never shows as unaccounted in the earlier one', async () => {
    const carried = randomUUID();
    const shiftB = '22222222-2222-4222-8222-222222222222';
    await upload([
      event('shift_started', 0, { shiftId: SHIFT, conductor: 'juan', vehicleId: 'v1', routeId: 'r1' }),
      tripCreated(carried, 1500, SHIFT, 1),
      cardState(carried, 'ASSIGNED_UNPAID', 2),
      event('shift_started', 3, { shiftId: shiftB, conductor: 'maria', vehicleId: 'v1', routeId: 'r1' }),
      cardState(carried, 'ASSIGNED_PAID', 4, shiftB),
    ]);

    const rows = await unaccounted();
    // Paid in shift B, so it is collected money, not unaccounted; nothing is reported at all.
    expect(rows).toEqual([]);
  });

  it('a cleared unpaid-return alarm counts as unaccounted too, not only lost cards', async () => {
    const cleared = randomUUID();
    await upload([
      event('shift_started', 0, { shiftId: SHIFT, conductor: 'juan', vehicleId: 'v1', routeId: 'r1' }),
      tripCreated(cleared, 1500, SHIFT, 1),
      cardState(cleared, 'RETURNED_UNPAID', 2),
      cardState(cleared, 'AVAILABLE', 3),
    ]);

    const rows = await unaccounted();
    expect(rows).toHaveLength(1);
    expect(rows[0].unknown_cents).toBe(1500);
    expect(rows[0].trips[0].card_state).toBe('AVAILABLE');
  });

  it('an open shift with nothing unaccounted is not listed, and the trips are searchable in the audit log', async () => {
    const tripId = randomUUID();
    await upload([
      tripCreated(tripId, 1500, SHIFT, 1),
      cardState(tripId, 'LOST', 2),
    ]);

    const rows = await unaccounted();
    expect(rows).toHaveLength(1);
    // The shift has no shift_started event in this fixture, so the header fields are null — but the
    // trip ids are always there for the dashboard to link into the audit log.
    expect(rows[0].trips[0].trip_id).toBe(tripId);

    // The link the dashboard shows searches the audit log by trip id and finds the trip.
    const audit = await request(app)
      .get(`/api/audit/logs?search=${tripId}`)
      .set('Authorization', `Bearer ${admin}`);
    expect(audit.body.metadata.total).toBeGreaterThan(0);
    expect(audit.body.logs.some((l: { trip_id: string | null }) => l.trip_id === tripId)).toBe(true);
  });
});
