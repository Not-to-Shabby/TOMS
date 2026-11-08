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

const T0 = Date.UTC(2026, 10, 9, 1, 0, 0);

/** The payload exactly as the phone's DeviceStatusReporter serializes it. */
function deviceStatusPayload(over: Record<string, unknown> = {}) {
  return {
    outboxPending: 3,
    outboxParked: 1,
    uploadAttempts: 9,
    uploadFailures: 2,
    lastUploadAtMillis: T0 - 60_000,
    batteryPct: 84,
    charging: false,
    gpsFixAgeSeconds: 7,
    gpsAccuracyMeters: 4.5,
    network: 'mobile',
    rmLinkState: 'connected',
    appVersion: '0.1.0',
    shiftId: randomUUID(),
    localSeq: 42,
    ...over,
  };
}

function statusEvent(seq: number, payload: Record<string, unknown>) {
  return {
    event_id: randomUUID(),
    device_id: 'dev-1',
    local_seq: seq,
    type: 'device_status',
    created_at_millis: T0 + seq,
    payload,
  };
}

async function upload(events: unknown[]) {
  const res = await request(app)
    .post('/api/events')
    .set('Authorization', `Bearer ${deviceToken}`)
    .send({ events });
  return res.body;
}

async function viewRows(): Promise<Record<string, unknown>[]> {
  const { rows } = await pool.query('SELECT * FROM device_status ORDER BY occurred_at');
  return rows;
}

describe('device_status telemetry', () => {
  it('accepts a status and the device_status view exposes every field', async () => {
    const body = await upload([statusEvent(1, deviceStatusPayload())]);
    expect(body.accepted).toHaveLength(1);

    const rows = await viewRows();
    expect(rows).toHaveLength(1);
    const row = rows[0];
    expect(row.device_id).toBe('dev-1');
    expect(row.outbox_pending).toBe(3);
    expect(row.outbox_parked).toBe(1);
    expect(row.upload_attempts).toBe(9);
    expect(row.upload_failures).toBe(2);
    expect(Number(row.last_upload_at)).toBe(T0 - 60_000);
    expect(row.battery_pct).toBe(84);
    expect(row.charging).toBe(false);
    expect(row.gps_fix_age_seconds).toBe(7);
    expect(row.gps_accuracy_meters).toBeCloseTo(4.5);
    expect(row.network).toBe('mobile');
    expect(row.rm_link_state).toBe('connected');
    expect(row.app_version).toBe('0.1.0');
    expect(typeof row.shift_id).toBe('string');
    expect(row.local_seq).toBe(1);
  });

  it('stays idempotent: the same status sent twice is accepted once, then a duplicate', async () => {
    const status = statusEvent(1, deviceStatusPayload());
    const first = await upload([status]);
    expect(first.accepted).toHaveLength(1);
    const second = await upload([status]);
    expect(second.duplicates).toHaveLength(1);
    expect(await viewRows()).toHaveLength(1);
  });

  it('accepts a mixed batch of trip, card state and device status events', async () => {
    const tripId = randomUUID();
    const body = await upload([
      {
        event_id: randomUUID(),
        device_id: 'dev-1',
        local_seq: 1,
        type: 'trip_created',
        created_at_millis: T0,
        payload: {
          tripId,
          cardUuid: randomUUID(),
          nfcUid: '6FF1AD39',
          boardingStopId: 's1',
          declaredDestinationStopId: 's2',
          computedFareCentavos: 1500,
          fareCentavos: 1500,
          discountCentavos: 0,
          fareVersion: 1,
        },
      },
      {
        event_id: randomUUID(),
        device_id: 'dev-1',
        local_seq: 2,
        type: 'card_state_changed',
        created_at_millis: T0 + 1,
        payload: { nfcUid: '6FF1AD39', cardUuid: randomUUID(), tripId, state: 'ASSIGNED_UNPAID' },
      },
      statusEvent(3, deviceStatusPayload({ outboxPending: 2 })),
    ]);
    expect(body.accepted).toHaveLength(3);
    expect(await viewRows()).toHaveLength(1);
  });

  it('rejects unknown fields instead of storing rows the researcher could misread', async () => {
    const body = await upload([statusEvent(1, deviceStatusPayload({ passengerCount: 2 }))]);
    expect(body.accepted).toHaveLength(0);
    expect(body.rejected).toHaveLength(1);
    expect(await viewRows()).toHaveLength(0);
  });

  it('rejects a network or Return Machine link state that is not in the enum', async () => {
    const badNetwork = await upload([statusEvent(1, deviceStatusPayload({ network: '5g' }))]);
    expect(badNetwork.rejected).toHaveLength(1);
    const badRm = await upload([statusEvent(2, deviceStatusPayload({ rmLinkState: 'paired' }))]);
    expect(badRm.rejected).toHaveLength(1);
    const badBattery = await upload([statusEvent(3, deviceStatusPayload({ batteryPct: 101 }))]);
    expect(badBattery.rejected).toHaveLength(1);
  });

  it('accepts nulls for fields a phone without a fix or upload has not filled yet', async () => {
    const body = await upload([
      statusEvent(1, deviceStatusPayload({ lastUploadAtMillis: null, gpsFixAgeSeconds: null, gpsAccuracyMeters: null, batteryPct: null })),
    ]);
    expect(body.accepted).toHaveLength(1);
    const row = (await viewRows())[0];
    expect(row.last_upload_at).toBeNull();
    expect(row.gps_fix_age_seconds).toBeNull();
    expect(row.gps_accuracy_meters).toBeNull();
    expect(row.battery_pct).toBeNull();
  });

  it('keeps working for an old phone that never sends the event', async () => {
    const body = await upload([
      {
        event_id: randomUUID(),
        device_id: 'dev-1',
        local_seq: 1,
        type: 'trip_created',
        created_at_millis: T0,
        payload: {
          tripId: randomUUID(),
          cardUuid: randomUUID(),
          nfcUid: '6FF1AD39',
          boardingStopId: 's1',
          declaredDestinationStopId: 's2',
          computedFareCentavos: 1500,
          fareCentavos: 1500,
          discountCentavos: 0,
          fareVersion: 1,
        },
      },
    ]);
    expect(body.accepted).toHaveLength(1);
    expect(await viewRows()).toHaveLength(0);
  });
});
