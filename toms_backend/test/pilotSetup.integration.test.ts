// Runs scripts/setup-pilot.mjs against the real API, backed by the test database. Nothing leaves this machine.
// The setup script is plain JavaScript, so it is imported here and given the real server's fetch.
import { randomUUID } from 'crypto';
import type { AddressInfo } from 'net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createServer } from '../src/server';
import { createAdmin } from '../src/admins';
import { createTestDb, ADMIN_PASSWORD, TEST_JWT_SECRET } from './helpers';
import { readFile } from 'fs/promises';
import path from 'path';

// @ts-expect-error the script is plain ESM JavaScript outside the backend's src tree
const setup = (await import('../../scripts/setup-pilot.mjs')) as Record<string, (...args: unknown[]) => Promise<unknown>>;

let pool: Pool;
let drop: () => Promise<void>;
let server: ReturnType<typeof createServer>['server'];
let base: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  const created = createServer({ pool, jwtSecret: TEST_JWT_SECRET, corsOrigins: [] });
  server = created.server;
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  await createAdmin(pool, 'pilot-admin', ADMIN_PASSWORD);
});
afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await drop();
});

async function exampleData() {
  const file = path.join(__dirname, '..', '..', 'scripts', 'pilot-data.example.json');
  return JSON.parse(await readFile(file, 'utf8'));
}

describe('setup-pilot against the real API', () => {
  it('creates the pilot end to end, and a second run creates nothing new', async () => {
    const data = await exampleData();
    const login = await fetch(`${base}/api/admin/login`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username: 'pilot-admin', password: ADMIN_PASSWORD }),
    }).then((r) => r.json());
    const token = login.token as string;

    const existing1 = await setup.readExisting(fetch, base, token);
    const log1 = await setup.applyPilot(fetch, base, token, data, existing1, 'conductor-pass-1') as string[];
    expect(log1.join('\n')).toMatch(/created vehicle/);
    expect(log1.join('\n')).toMatch(/published fare matrix/);

    // The fare the phone will download is the one the script published, in pesos on the way in and centavos out.
    const device = await fetch(`${base}/api/devices`, {
      method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ device_id: 'pilot-phone-1', vehicle_id: data.vehicle.id }),
    }).then((r) => r.json());
    const config = await fetch(`${base}/api/device/config`, {
      headers: { authorization: `Bearer ${device.token}` },
    }).then((r) => r.json());
    expect(config.fare.base_fare_centavos).toBe(1500);
    expect(config.fare.per_km_fare_centavos).toBe(250);
    expect(config.stops.length).toBe(3);

    // The conductor must still be on the vehicle after the route was attached: the route change must not unlink them.
    const link = await pool.query("SELECT v.assigned_route_id, v.assigned_conductor_id, c.username FROM vehicles v LEFT JOIN conductors c ON c.id = v.assigned_conductor_id WHERE v.id = $1", [data.vehicle.id]);
    expect(link.rows[0].assigned_route_id).not.toBeNull();
    expect(link.rows[0].username).toBe(data.conductor.username);

    const existing2 = await setup.readExisting(fetch, base, token);
    const log2 = await setup.applyPilot(fetch, base, token, data, existing2, undefined) as string[];
    expect(log2.every((l) => /skipped|already|left as they are/.test(l))).toBe(true);
  });
});
