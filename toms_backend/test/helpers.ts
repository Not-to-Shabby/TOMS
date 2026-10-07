import { randomUUID } from 'crypto';
import { Pool } from 'pg';
import { createAdmin } from '../src/admins';
import { newDeviceCredential } from '../src/auth';
import { migrate } from '../src/db';

export const TEST_JWT_SECRET = 'test-secret-test-secret-test-secret-0123456789';
export const ADMIN_PASSWORD = 'correct horse battery';

/** Creates a fresh, migrated database so tests never see each other's rows. */
export async function createTestDb(): Promise<{ pool: Pool; drop: () => Promise<void> }> {
  const adminUrl = process.env.TEST_DATABASE_ADMIN_URL;
  if (!adminUrl) throw new Error('TEST_DATABASE_ADMIN_URL is not set (globalSetup did not run)');

  const name = `t_${randomUUID().replace(/-/g, '')}`;
  const admin = new Pool({ connectionString: adminUrl });
  await admin.query(`CREATE DATABASE ${name}`);

  const url = new URL(adminUrl);
  url.pathname = `/${name}`;
  const pool = new Pool({ connectionString: url.toString() });
  await migrate(pool);

  return {
    pool,
    drop: async () => {
      await pool.end();
      await admin.query(`DROP DATABASE IF EXISTS ${name} WITH (FORCE)`);
      await admin.end();
    },
  };
}

/** A valid trip_created row. Override fields to build the case under test. */
export function tripCreatedRow(over: Record<string, unknown> = {}) {
  return {
    event_id: randomUUID(),
    device_id: 'dev-1',
    local_seq: 1,
    event_type: 'trip_created',
    vehicle_id: null,
    occurred_at: '2026-10-08T01:00:00Z',
    trip_id: randomUUID(),
    card_uuid: randomUUID(),
    nfc_uid: '6FF1AD39',
    boarding_stop_id: 's1',
    declared_destination_stop_id: 's2',
    computed_fare_centavos: 1500,
    fare_centavos: 1500,
    discount_centavos: 0,
    fare_version: 0,
    payload: {},
    ...over,
  };
}

const COLUMNS = [
  'event_id', 'device_id', 'local_seq', 'event_type', 'vehicle_id', 'occurred_at', 'trip_id', 'card_uuid',
  'nfc_uid', 'card_state', 'boarding_stop_id', 'declared_destination_stop_id', 'computed_fare_centavos',
  'fare_centavos', 'discount_centavos', 'fare_version', 'gps_lat', 'gps_lon', 'gps_accuracy_m', 'gps_fix_at',
  'payload',
] as const;

export async function insertEvent(pool: Pool, row: Record<string, unknown>) {
  const values = COLUMNS.map((c) => {
    const v = row[c];
    if (c === 'payload') return JSON.stringify(v ?? {});
    return v === undefined ? null : v;
  });
  const placeholders = COLUMNS.map((_, i) => `$${i + 1}`).join(', ');
  return pool.query(`INSERT INTO events (${COLUMNS.join(', ')}) VALUES (${placeholders})`, values);
}

/** Creates an admin and returns a signed-in token for it. */
export async function adminToken(pool: Pool, app: import('express').Express, username = 'boss'): Promise<string> {
  const { default: request } = await import('supertest');
  await createAdmin(pool, username, ADMIN_PASSWORD);
  const res = await request(app).post('/api/admin/login').send({ username, password: ADMIN_PASSWORD });
  if (res.status !== 200) throw new Error(`admin login failed: ${res.status}`);
  return res.body.token as string;
}

/** Enrolls a device directly in the database and returns its credential. */
export async function enrollDevice(pool: Pool, deviceId: string, vehicleId: string | null = null): Promise<string> {
  const { token, tokenHash } = newDeviceCredential(deviceId);
  await pool.query('INSERT INTO devices (device_id, vehicle_id, token_hash) VALUES ($1, $2, $3)', [
    deviceId,
    vehicleId,
    tokenHash,
  ]);
  return token;
}
