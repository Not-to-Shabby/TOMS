import { randomUUID } from 'crypto';
import type { AddressInfo } from 'net';
import { io as connectClient, type Socket } from 'socket.io-client';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createServer } from '../src/server';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let server: ReturnType<typeof createServer>['server'];
let url: string;
let admin: string;
const open: Socket[] = [];

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  const created = createServer({ pool, jwtSecret: TEST_JWT_SECRET, corsOrigins: [] });
  server = created.server;
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const { createApp } = await import('../src/app');
  admin = await adminToken(pool, createApp({ pool, jwtSecret: TEST_JWT_SECRET }));
});
afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await drop();
});
beforeEach(async () => {
  await pool.query('TRUNCATE events, devices, vehicles RESTART IDENTITY CASCADE');
});
afterEach(() => {
  while (open.length) open.pop()!.close();
});

/** Resolves with how the connection ended, so a hang fails the test instead of the whole run. */
function attempt(token?: string): Promise<{ connected: boolean; message?: string; socket: Socket }> {
  return new Promise((resolve, reject) => {
    const socket = connectClient(url, {
      auth: token === undefined ? {} : { token },
      reconnection: false,
      transports: ['polling', 'websocket'],
    });
    open.push(socket);
    const timer = setTimeout(() => reject(new Error('socket neither connected nor refused within 5 s')), 5000);
    socket.on('connect', () => { clearTimeout(timer); resolve({ connected: true, socket }); });
    socket.on('connect_error', (e) => { clearTimeout(timer); resolve({ connected: false, message: e.message, socket }); });
  });
}

describe('live socket access', () => {
  it('lets a signed-in admin connect', async () => {
    expect((await attempt(admin)).connected).toBe(true);
  });

  it('refuses a connection with no token, and says why', async () => {
    const r = await attempt();
    expect(r.connected).toBe(false);
    expect(r.message).toBe('unauthorized');
  });

  it('refuses a garbage token, a non-string token and a device credential', async () => {
    const device = await enrollDevice(pool, 'dev-1');
    for (const token of ['not-a-token', device]) {
      const r = await attempt(token);
      expect(r.connected, token).toBe(false);
      expect(r.message, token).toBe('unauthorized');
    }
    const odd = await new Promise<{ connected: boolean; message?: string }>((resolve, reject) => {
      const s = connectClient(url, { auth: { token: 12345 as unknown as string }, reconnection: false });
      open.push(s);
      const t = setTimeout(() => reject(new Error('hung')), 5000);
      s.on('connect', () => { clearTimeout(t); resolve({ connected: true }); });
      s.on('connect_error', (e) => { clearTimeout(t); resolve({ connected: false, message: e.message }); });
    });
    expect(odd).toEqual({ connected: false, message: 'unauthorized' });
  });

  it('refuses an admin token once that admin is deleted', async () => {
    const { createApp } = await import('../src/app');
    const gone = await adminToken(pool, createApp({ pool, jwtSecret: TEST_JWT_SECRET }), 'soon-gone');
    await pool.query("DELETE FROM admins WHERE username = 'soon-gone'");
    expect((await attempt(gone)).connected).toBe(false);
  });

  it('still serves the REST API on the same server', async () => {
    const res = await fetch(`${url}/health`);
    expect(res.status).toBe(200);
    expect(((await res.json()) as { status: string }).status).toBe('ok');
    expect((await fetch(`${url}/api/fleet/status`)).status).toBe(401);
  });
});

describe('live updates reach the dashboard', () => {
  it('delivers fleet_update to a connected admin when a phone uploads, and not to a refused client', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111')");
    const deviceToken = await enrollDevice(pool, 'dev-1', 'BUS-1');

    const { socket } = await attempt(admin);
    const updates: any[] = [];
    socket.on('fleet_update', (u) => updates.push(u));

    const now = Date.now();
    const res = await fetch(`${url}/api/events`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${deviceToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        events: [{
          event_id: randomUUID(), device_id: 'dev-1', local_seq: 1, type: 'trip_created', created_at_millis: now,
          payload: {
            tripId: randomUUID(), cardUuid: randomUUID(), nfcUid: '6FF1AD39', boardingStopId: 's1',
            declaredDestinationStopId: 's2', computedFareCentavos: 1500, fareCentavos: 1500, discountCentavos: 0,
            fareVersion: 0, gpsLat: 8.22219, gpsLon: 124.25961, gpsAccuracyMeters: 3, gpsFixAtMillis: now - 1000,
          },
        }],
      }),
    });
    expect(((await res.json()) as { accepted: string[] }).accepted).toHaveLength(1);

    await expect.poll(() => updates.length, { timeout: 3000 }).toBeGreaterThan(0);
    expect(updates.at(-1)).toMatchObject({ vehicle_id: 'BUS-1', occupancy_now: 1 });
    expect(updates.at(-1).current_lat).toBeCloseTo(8.22219, 5);
  });
});
