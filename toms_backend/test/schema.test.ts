import { randomUUID } from 'crypto';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { migrate } from '../src/db';
import { createTestDb, insertEvent, tripCreatedRow } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
});
afterAll(async () => drop());

const rejects = async (row: Record<string, unknown>) => {
  await expect(insertEvent(pool, row)).rejects.toThrow();
};

describe('migrations', () => {
  it('apply once and are skipped on the next run', async () => {
    expect(await migrate(pool)).toEqual([]);
    const { rows } = await pool.query('SELECT name FROM schema_migrations ORDER BY name');
    const onDisk = fs
      .readdirSync(path.join(__dirname, '..', 'migrations'))
      .filter((f) => f.endsWith('.sql'))
      .sort();
    expect(rows.map((r) => r.name)).toEqual(onDisk);
  });

  it('roll back completely when a later file fails', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mig-'));
    fs.writeFileSync(path.join(dir, '001_ok.sql'), 'CREATE TABLE mig_ok (id int);');
    fs.writeFileSync(path.join(dir, '002_bad.sql'), 'CREATE TABLE mig_half (id int); SELECT broken FROM nowhere;');
    const fresh = await createTestDb();
    try {
      await expect(migrate(fresh.pool, dir)).rejects.toThrow(/002_bad.sql/);
      const tables = await fresh.pool.query(
        "SELECT table_name FROM information_schema.tables WHERE table_name IN ('mig_ok','mig_half')",
      );
      expect(tables.rows.map((r) => r.table_name)).toEqual(['mig_ok']);
    } finally {
      await fresh.drop();
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe('events table', () => {
  it('accepts a valid trip_created row with a GPS fix', async () => {
    await insertEvent(
      pool,
      tripCreatedRow({
        gps_lat: 8.22219,
        gps_lon: 124.25961,
        gps_accuracy_m: 3,
        gps_fix_at: '2026-10-08T00:59:58Z',
      }),
    );
    const { rows } = await pool.query('SELECT gps_lat, gps_lon, gps_accuracy_m, gps_fix_at FROM events');
    expect(rows[0].gps_lat).toBeCloseTo(8.22219, 5);
    expect(rows[0].gps_accuracy_m).toBeCloseTo(3);
    expect(rows[0].gps_fix_at).toBeInstanceOf(Date);
  });

  it('accepts a trip with no GPS fix at all', async () => {
    await insertEvent(pool, tripCreatedRow());
  });

  it('accepts a GPS fix with unknown accuracy', async () => {
    await insertEvent(pool, tripCreatedRow({ gps_lat: 8.2, gps_lon: 124.2, gps_fix_at: '2026-10-08T00:59:58Z' }));
  });

  it('rejects a duplicate event_id', async () => {
    const row = tripCreatedRow();
    await insertEvent(pool, row);
    await rejects({ ...row, trip_id: randomUUID() });
  });

  it('allows the same local_seq twice, because a reinstalled phone restarts its counter', async () => {
    await insertEvent(pool, tripCreatedRow({ local_seq: 7 }));
    await insertEvent(pool, tripCreatedRow({ local_seq: 7 }));
  });

  it('rejects a half-present GPS fix', async () => {
    await rejects(tripCreatedRow({ gps_lat: 8.2 }));
    await rejects(tripCreatedRow({ gps_lat: 8.2, gps_lon: 124.2 })); // no fix time
    await rejects(tripCreatedRow({ gps_accuracy_m: 5 })); // accuracy without a position
  });

  it('rejects GPS coordinates out of range and negative accuracy', async () => {
    const fix = { gps_fix_at: '2026-10-08T00:59:58Z' };
    await rejects(tripCreatedRow({ ...fix, gps_lat: 91, gps_lon: 124 }));
    await rejects(tripCreatedRow({ ...fix, gps_lat: 8, gps_lon: 181 }));
    await rejects(tripCreatedRow({ ...fix, gps_lat: 8, gps_lon: 124, gps_accuracy_m: -1 }));
  });

  it('rejects a trip_created row missing required trip fields', async () => {
    await rejects(tripCreatedRow({ trip_id: null }));
    await rejects(tripCreatedRow({ card_uuid: null }));
    await rejects(tripCreatedRow({ fare_version: null }));
    await rejects(tripCreatedRow({ declared_destination_stop_id: null }));
  });

  it('rejects negative money and malformed card identifiers', async () => {
    await rejects(tripCreatedRow({ fare_centavos: -1 }));
    await rejects(tripCreatedRow({ discount_centavos: -5 }));
    await rejects(tripCreatedRow({ nfc_uid: '6f:f1:ad:39' })); // must be normalized upper-case hex
    await rejects(tripCreatedRow({ card_uuid: 'not-a-uuid' }));
  });

  it('rejects an unknown delivery channel and an unknown card state', async () => {
    await expect(
      pool.query(
        `INSERT INTO events (event_id, device_id, local_seq, event_type, occurred_at, delivery_channel, payload)
         VALUES ($1, 'd', 0, 'x', now(), 'carrier_pigeon', '{}')`,
        [randomUUID()],
      ),
    ).rejects.toThrow();
    await rejects({
      event_id: randomUUID(), device_id: 'd', local_seq: 1, event_type: 'card_state_changed',
      occurred_at: '2026-10-08T01:00:00Z', card_uuid: randomUUID(), nfc_uid: 'AABBCCDD', card_state: 'TELEPORTED',
    });
  });

  it('requires card fields on card_state_changed rows', async () => {
    await rejects({
      event_id: randomUUID(), device_id: 'd', local_seq: 1, event_type: 'card_state_changed',
      occurred_at: '2026-10-08T01:00:00Z',
    });
  });

  it('keeps unknown event types, since the payload column preserves the original', async () => {
    await insertEvent(pool, {
      event_id: randomUUID(), device_id: 'd', local_seq: 2, event_type: 'future_type',
      occurred_at: '2026-10-08T01:00:00Z', payload: { anything: true },
    });
  });
});

describe('trip_status view', () => {
  it('reports the latest card state and whether the trip was ever paid', async () => {
    const tripId = randomUUID();
    const cardUuid = randomUUID();
    const base = { trip_id: tripId, card_uuid: cardUuid, nfc_uid: 'AABBCCDD', device_id: 'dev-view' };
    await insertEvent(pool, tripCreatedRow({ ...base, local_seq: 1, occurred_at: '2026-10-08T02:00:00Z' }));
    const state = (s: string, seq: number, at: string) =>
      insertEvent(pool, {
        ...base, event_id: randomUUID(), local_seq: seq, event_type: 'card_state_changed', card_state: s, occurred_at: at,
      });

    let row = (await pool.query('SELECT current_state, paid FROM trip_status WHERE trip_id = $1', [tripId])).rows[0];
    expect(row).toEqual({ current_state: 'ASSIGNED_UNPAID', paid: false });

    await state('ASSIGNED_PAID', 2, '2026-10-08T02:01:00Z');
    await state('RETURNED', 3, '2026-10-08T02:20:00Z');
    await state('AVAILABLE', 4, '2026-10-08T02:21:00Z');
    row = (await pool.query('SELECT current_state, paid FROM trip_status WHERE trip_id = $1', [tripId])).rows[0];
    expect(row).toEqual({ current_state: 'AVAILABLE', paid: true });
  });

  it('orders by the phone sequence when two events share a timestamp', async () => {
    const tripId = randomUUID();
    const base = { trip_id: tripId, card_uuid: randomUUID(), nfc_uid: 'AABBCCDE', device_id: 'dev-tie' };
    await insertEvent(pool, tripCreatedRow({ ...base, local_seq: 1 }));
    const at = '2026-10-08T03:00:00Z';
    // Inserted out of order on purpose: arrival order must not decide the state.
    await insertEvent(pool, { ...base, event_id: randomUUID(), local_seq: 3, event_type: 'card_state_changed', card_state: 'RETURNED', occurred_at: at });
    await insertEvent(pool, { ...base, event_id: randomUUID(), local_seq: 2, event_type: 'card_state_changed', card_state: 'ASSIGNED_PAID', occurred_at: at });
    const row = (await pool.query('SELECT current_state FROM trip_status WHERE trip_id = $1', [tripId])).rows[0];
    expect(row.current_state).toBe('RETURNED');
  });
});
