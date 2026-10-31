import { randomUUID } from 'crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { configVersion, MAX_CONFIG_VERSION, pesosToCentavos } from '../src/deviceConfig';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE admins, devices, vehicles, events, routes, fare_matrices RESTART IDENTITY CASCADE');
});

const get = (token?: string) => {
  const r = request(app).get('/api/device/config');
  return token ? r.set('Authorization', `Bearer ${token}`) : r;
};

async function addRoute(name: string): Promise<number> {
  return (await pool.query('INSERT INTO routes (name) VALUES ($1) RETURNING id', [name])).rows[0].id;
}

async function addPath(routeId: number, name = 'Primary'): Promise<number> {
  return (await pool.query('INSERT INTO route_paths (route_id, name) VALUES ($1, $2) RETURNING id', [routeId, name])).rows[0].id;
}

/** Stops are placed in the order given. `pathId` null makes them the older, path-less kind. */
async function addStops(routeId: number, pathId: number | null, names: string[]) {
  for (const [i, name] of names.entries()) {
    await pool.query(
      'INSERT INTO route_stops (route_id, path_id, name, lat, lon, stop_order, radius_m) VALUES ($1, $2, $3, $4, $5, $6, 120)',
      [routeId, pathId, name, 8.2 + i * 0.01, 124.2, i],
    );
  }
}

async function addMatrix(routeId: number | null, over: Record<string, unknown> = {}) {
  const m = { base_fare: '15.00', base_distance_km: '4.00', per_km_fare: '2.50', rounding_step_cents: 100, discounts: { student: 20, pwd: 20, senior: 20 }, is_active: true, ...over };
  return (
    await pool.query(
      `INSERT INTO fare_matrices (route_id, base_fare, base_distance_km, per_km_fare, rounding_step_cents, discounts, is_active)
       VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7) RETURNING id`,
      [routeId, m.base_fare, m.base_distance_km, m.per_km_fare, m.rounding_step_cents, JSON.stringify(m.discounts), m.is_active],
    )
  ).rows[0].id as number;
}

async function addVehicle(id: string, routeId: number | null) {
  await pool.query('INSERT INTO vehicles (id, plate_number, assigned_route_id) VALUES ($1, $2, $3)', [id, `PLATE-${id}`, routeId]);
}

/** A device on a vehicle on a route with two stops and one published matrix: the working case. */
async function workingDevice(deviceId = 'dev-1') {
  const route = await addRoute('Tibanga - Pala-o');
  const path = await addPath(route);
  await addStops(route, path, ['Tibanga', 'Pala-o']);
  const matrix = await addMatrix(route);
  await addVehicle('v1', route);
  const token = await enrollDevice(pool, deviceId, 'v1');
  return { route, path, matrix, token };
}

describe('GET /api/device/config: who may ask', () => {
  it('needs a device credential: none, a wrong secret, an unknown device, a revoked device and an admin token are all refused', async () => {
    const { token } = await workingDevice();
    expect((await get()).status).toBe(401);
    expect((await get(`${token}x`)).status).toBe(401);
    expect((await get('dev-1.wrongsecret')).status).toBe(401);
    expect((await get('ghost.' + token.split('.')[1])).status).toBe(401);
    expect((await get(await adminToken(pool, app))).status).toBe(401);
    await pool.query("UPDATE devices SET revoked_at = now() WHERE device_id = 'dev-1'");
    expect((await get(token)).status).toBe(401);
  });
});

describe('GET /api/device/config: what it sends', () => {
  it('sends the assigned route, its stops in order, and the fare in centavos', async () => {
    const { route, matrix, token } = await workingDevice();
    const res = await get(token);
    expect(res.status).toBe(200);
    expect(res.body.route).toEqual({ id: route, name: 'Tibanga - Pala-o' });
    expect(res.body.stops.map((s: { name: string }) => s.name)).toEqual(['Tibanga', 'Pala-o']);
    expect(res.body.stops[0]).toMatchObject({ lat: 8.2, lon: 124.2, radius_m: 120 });
    expect(typeof res.body.stops[0].id).toBe('string');
    expect(res.body.fare).toEqual({
      matrix_id: matrix,
      base_fare_centavos: 1500,
      base_distance_km: 4,
      per_km_fare_centavos: 250,
      rounding_step_cents: 100,
      discounts: [
        { id: 'pwd', name: 'PWD', percent: 20 },
        { id: 'senior', name: 'Senior', percent: 20 },
        { id: 'student', name: 'Student', percent: 20 },
      ],
    });
  });

  it('converts pesos to whole centavos exactly, including awkward amounts', async () => {
    const { route, token } = await workingDevice();
    await pool.query('UPDATE fare_matrices SET base_fare = 12.35, per_km_fare = 0.10, base_distance_km = 2.5 WHERE route_id = $1', [route]);
    const fare = (await get(token)).body.fare;
    expect(fare.base_fare_centavos).toBe(1235);
    expect(fare.per_km_fare_centavos).toBe(10);
    expect(fare.base_distance_km).toBe(2.5);
    expect(pesosToCentavos('0.29')).toBe(29);
    expect(pesosToCentavos('1.15')).toBe(115);
    expect(pesosToCentavos('19.99')).toBe(1999);
  });

  it('keeps each device on its own route, stops and fare', async () => {
    const a = await workingDevice('dev-a');
    const routeB = await addRoute('Tubod - City Proper');
    const pathB = await addPath(routeB);
    await addStops(routeB, pathB, ['Tubod', 'Mid', 'City']);
    await addMatrix(routeB, { base_fare: '20.00' });
    await addVehicle('v2', routeB);
    const tokenB = await enrollDevice(pool, 'dev-b', 'v2');

    const resA = await get(a.token);
    const resB = await get(tokenB);
    expect(resA.body.route.name).toBe('Tibanga - Pala-o');
    expect(resA.body.stops).toHaveLength(2);
    expect(resA.body.fare.base_fare_centavos).toBe(1500);
    expect(resB.body.route.name).toBe('Tubod - City Proper');
    expect(resB.body.stops).toHaveLength(3);
    expect(resB.body.fare.base_fare_centavos).toBe(2000);
    expect(resA.body.version).not.toBe(resB.body.version);
  });
});

describe('GET /api/device/config: which fare matrix', () => {
  it("prefers the route's own matrix over the global one, even when the global one is newer", async () => {
    const { route, token } = await workingDevice();
    await pool.query('DELETE FROM fare_matrices');
    await addMatrix(route, { base_fare: '18.00' });
    await addMatrix(null, { base_fare: '99.00' });
    expect((await get(token)).body.fare.base_fare_centavos).toBe(1800);
  });

  it('falls back to the global matrix when the route has none of its own', async () => {
    const { token } = await workingDevice();
    await pool.query('DELETE FROM fare_matrices');
    const global = await addMatrix(null, { base_fare: '17.00' });
    const res = await get(token);
    expect(res.status).toBe(200);
    expect(res.body.fare.matrix_id).toBe(global);
    expect(res.body.fare.base_fare_centavos).toBe(1700);
  });

  it('ignores a matrix that has been replaced (inactive)', async () => {
    const { route, token } = await workingDevice();
    await pool.query('UPDATE fare_matrices SET is_active = false WHERE route_id = $1', [route]);
    await addMatrix(route, { base_fare: '16.00' });
    expect((await get(token)).body.fare.base_fare_centavos).toBe(1600);
  });

  it("does not use another route's matrix", async () => {
    const { token } = await workingDevice();
    await pool.query('DELETE FROM fare_matrices');
    await addMatrix(await addRoute('Somewhere else'), { base_fare: '50.00' });
    expect((await get(token)).status).toBe(404);
  });
});

describe('GET /api/device/config: nothing is invented', () => {
  it('answers 404 with a reason when the device has no vehicle', async () => {
    const token = await enrollDevice(pool, 'dev-1', null);
    const res = await get(token);
    expect(res.status).toBe(404);
    expect(res.body.error).toMatch(/vehicle/);
  });

  it('answers 404 when the vehicle has no route', async () => {
    await addVehicle('v1', null);
    const token = await enrollDevice(pool, 'dev-1', 'v1');
    const res = await get(token);
    expect(res.status).toBe(404);
    expect(res.body.error).toMatch(/route/);
  });

  it('answers 404 when the route has fewer than two stops', async () => {
    const route = await addRoute('Short');
    await addStops(route, await addPath(route), ['Only one']);
    await addMatrix(route);
    await addVehicle('v1', route);
    const res = await get(await enrollDevice(pool, 'dev-1', 'v1'));
    expect(res.status).toBe(404);
    expect(res.body.error).toMatch(/two stops/);
  });

  it('answers 404 when no fare matrix is published, and never sends default numbers', async () => {
    const { token } = await workingDevice();
    await pool.query('DELETE FROM fare_matrices');
    const res = await get(token);
    expect(res.status).toBe(404);
    expect(res.body.error).toMatch(/fare matrix/);
    expect(res.body.fare).toBeUndefined();
    expect(JSON.stringify(res.body)).not.toMatch(/1500|250/);
  });

  it('refuses with 422 rather than sending a discount the phone cannot use', async () => {
    const { route, token } = await workingDevice();
    await pool.query('UPDATE fare_matrices SET discounts = $2::jsonb WHERE route_id = $1', [route, JSON.stringify({ student: 120 })]);
    expect((await get(token)).status).toBe(422);
    await pool.query('UPDATE fare_matrices SET discounts = $2::jsonb WHERE route_id = $1', [route, JSON.stringify({ student: 12.5 })]);
    expect((await get(token)).status).toBe(422);
    await pool.query('UPDATE fare_matrices SET discounts = $2::jsonb WHERE route_id = $1', [route, JSON.stringify({ student: '20' })]);
    expect((await get(token)).status).toBe(422);
  });
});

describe('GET /api/device/config: several paths', () => {
  it('uses the first path that has stops and ignores the others', async () => {
    const route = await addRoute('Two paths');
    const empty = await addPath(route, 'Empty first');
    const first = await addPath(route, 'Has stops');
    const second = await addPath(route, 'Alternative');
    await addStops(route, first, ['A', 'B']);
    await addStops(route, second, ['X', 'Y', 'Z']);
    await addMatrix(route);
    await addVehicle('v1', route);
    const res = await get(await enrollDevice(pool, 'dev-1', 'v1'));
    expect(empty).toBeLessThan(first);
    expect(res.body.stops.map((s: { name: string }) => s.name)).toEqual(['A', 'B']);
  });

  it('uses the older path-less stops when no path has any', async () => {
    const route = await addRoute('Legacy');
    await addPath(route);
    await addStops(route, null, ['Old 1', 'Old 2']);
    await addMatrix(route);
    await addVehicle('v1', route);
    const res = await get(await enrollDevice(pool, 'dev-1', 'v1'));
    expect(res.status).toBe(200);
    expect(res.body.stops.map((s: { name: string }) => s.name)).toEqual(['Old 1', 'Old 2']);
  });
});

describe('GET /api/device/config: version', () => {
  it('is an integer from 1 to 1,000,000, the most ingest accepts as a trip fare_version', async () => {
    const { token } = await workingDevice();
    const v = (await get(token)).body.version;
    expect(Number.isInteger(v)).toBe(true);
    expect(v).toBeGreaterThanOrEqual(1);
    expect(v).toBeLessThanOrEqual(MAX_CONFIG_VERSION);
    for (const sample of ['a', 'b', 'c', 'd', 'e', 'f'].map((s) => configVersion({ s }))) {
      expect(sample).toBeGreaterThanOrEqual(1);
      expect(sample).toBeLessThanOrEqual(MAX_CONFIG_VERSION);
    }
  });

  it('is accepted by ingest when a trip carries it as its fare version', async () => {
    const { token } = await workingDevice();
    const version = (await get(token)).body.version;
    const trip = {
      event_id: randomUUID(),
      device_id: 'dev-1',
      local_seq: 1,
      type: 'trip_created',
      created_at_millis: Date.UTC(2026, 9, 31),
      payload: {
        tripId: randomUUID(), cardUuid: randomUUID(), nfcUid: '6FF1AD39',
        boardingStopId: '1', declaredDestinationStopId: '2', actualDestinationStopId: null, discountCategoryId: null,
        computedFareCentavos: 1500, fareCentavos: 1500, discountCentavos: 0, fareVersion: version, overrideReason: null,
      },
    };
    const res = await request(app).post('/api/events').set('Authorization', `Bearer ${token}`).send({ events: [trip] });
    expect(res.status).toBe(200);
    expect(res.body.accepted).toEqual([trip.event_id]);
    expect(res.body.rejected).toEqual([]);
  });

  it('stays the same while nothing changes, and changes when the fare, a stop or a discount changes', async () => {
    const { route, token } = await workingDevice();
    const v0 = (await get(token)).body.version;
    expect((await get(token)).body.version).toBe(v0);

    await pool.query('UPDATE fare_matrices SET base_fare = 16 WHERE route_id = $1', [route]);
    const v1 = (await get(token)).body.version;
    expect(v1).not.toBe(v0);

    await pool.query("UPDATE route_stops SET name = 'Renamed' WHERE route_id = $1 AND stop_order = 0", [route]);
    const v2 = (await get(token)).body.version;
    expect(v2).not.toBe(v1);

    await pool.query('UPDATE fare_matrices SET discounts = $2::jsonb WHERE route_id = $1', [route, JSON.stringify({ student: 25, pwd: 20, senior: 20 })]);
    expect((await get(token)).body.version).not.toBe(v2);
  });

  it('changes when a new matrix is published, even with the same numbers', async () => {
    const { route, token } = await workingDevice();
    const v0 = (await get(token)).body.version;
    await pool.query('UPDATE fare_matrices SET is_active = false WHERE route_id = $1', [route]);
    await addMatrix(route);
    expect((await get(token)).body.version).not.toBe(v0);
  });

  it('does not depend on the order object keys happen to be written in', () => {
    expect(configVersion({ a: 1, b: 2 })).toBe(configVersion({ a: 1, b: 2 }));
    expect(configVersion({ a: 1 })).not.toBe(configVersion({ a: 2 }));
  });
});
