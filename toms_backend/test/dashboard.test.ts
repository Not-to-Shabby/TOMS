import { randomUUID } from 'crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { csvText, EXPORT_ROW_LIMIT, likePattern } from '../src/dashboard';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let admin: string;
let emitted: { event: string; payload: any }[];

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  emitted = [];
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET, emit: (event, payload) => emitted.push({ event, payload }) });
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE admins, conductors, devices, vehicles, events, routes RESTART IDENTITY CASCADE');
  emitted.length = 0;
  admin = await adminToken(pool, app);
});

const get = (path: string) => request(app).get(path).set('Authorization', `Bearer ${admin}`);

/** Inserts events straight into the table so each test controls time, vehicle and state exactly. */
async function addTrip(o: {
  vehicle?: string | null; fare?: number; discount?: number; category?: string | null; at?: string;
  states?: string[]; suspect?: boolean; received?: string; uid?: string; gps?: [number, number, string, number?];
  stop?: string; override?: string;
} = {}) {
  const tripId = randomUUID();
  const at = o.at ?? new Date().toISOString();
  const base = {
    trip: tripId, card: randomUUID(), uid: o.uid ?? 'AABBCCDD', vehicle: o.vehicle === undefined ? 'BUS-1' : o.vehicle,
  };
  const cols = `(event_id, device_id, local_seq, event_type, vehicle_id, occurred_at, received_at, clock_suspect,
    trip_id, card_uuid, nfc_uid, boarding_stop_id, declared_destination_stop_id, computed_fare_centavos,
    fare_centavos, discount_centavos, fare_version, discount_category_id, override_reason,
    gps_lat, gps_lon, gps_fix_at, gps_accuracy_m, payload)`;
  await pool.query(
    `INSERT INTO events ${cols} VALUES ($1,'dev-1',1,'trip_created',$2,$3,$4,$5,$6,$7,$8,$9,'s2',$10,$10,$11,0,$12,$13,$14,$15,$16,$17,'{}')`,
    [randomUUID(), base.vehicle, at, o.received ?? at, o.suspect ?? false, base.trip, base.card, base.uid,
      o.stop ?? 's1', o.fare ?? 1500, o.discount ?? 0, o.category ?? null, o.override ?? null,
      o.gps?.[0] ?? null, o.gps?.[1] ?? null, o.gps?.[2] ?? null, o.gps?.[3] ?? null],
  );
  let seq = 2;
  for (const state of o.states ?? []) {
    await pool.query(
      `INSERT INTO events (event_id, device_id, local_seq, event_type, vehicle_id, occurred_at, received_at, trip_id,
        card_uuid, nfc_uid, card_state, payload)
       VALUES ($1,'dev-1',$2,'card_state_changed',$3,$4,$4,$5,$6,$7,$8,'{}')`,
      [randomUUID(), seq++, base.vehicle, at, base.trip, base.card, base.uid, state],
    );
  }
  return tripId;
}

const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toISOString();

describe('dashboard access', () => {
  it.each(['/api/fleet/status', '/api/audit/logs', '/api/analytics/revenue', '/api/export/audit', '/api/conductors', '/api/delivery/summary'])(
    '%s needs an admin token',
    async (path) => {
      expect((await request(app).get(path)).status).toBe(401);
      expect((await get(path)).status).toBe(200);
    },
  );
});

describe('fleet status', () => {
  beforeEach(async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number, max_capacity) VALUES ('BUS-1','AAA-111', 20), ('BUS-2','BBB-222', 20)");
  });

  it('lists every vehicle, including ones that never reported', async () => {
    const rows = (await get('/api/fleet/status')).body;
    expect(rows.map((r: any) => r.vehicle_id)).toEqual(['BUS-1', 'BUS-2']);
    expect(rows[1]).toMatchObject({ occupancy_now: 0, daily_revenue: 0, current_lat: null, last_updated: null });
  });

  it('counts only cards currently out as occupancy', async () => {
    await addTrip({ states: [] }); // unpaid, still out
    await addTrip({ states: ['ASSIGNED_PAID'] }); // out
    await addTrip({ states: ['ASSIGNED_PAID', 'RETURNED'] }); // returned
    await addTrip({ states: ['ASSIGNED_PAID', 'RETURNED', 'AVAILABLE'] }); // released
    await addTrip({ states: ['RETURNED_UNPAID'] }); // alarm, no longer aboard
    expect((await get('/api/fleet/status')).body[0].occupancy_now).toBe(2);
  });

  it('does not count a trip abandoned for more than a day as a passenger still aboard', async () => {
    await addTrip({ at: daysAgo(2), states: [] });
    await addTrip({ states: [] });
    expect((await get('/api/fleet/status')).body[0].occupancy_now).toBe(1);
  });

  it("sums today's collected fares only, not unpaid or other days", async () => {
    await addTrip({ fare: 1500, states: ['ASSIGNED_PAID'] });
    await addTrip({ fare: 2000, states: ['ASSIGNED_PAID', 'RETURNED', 'AVAILABLE'] }); // paid, later released
    await addTrip({ fare: 999, states: [] }); // unpaid
    await addTrip({ fare: 700, at: daysAgo(3), states: ['ASSIGNED_PAID'] }); // another day
    expect((await get('/api/fleet/status')).body[0].daily_revenue).toBe(3500);
  });

  it('reports the newest GPS fix with its age, ignoring trips without one', async () => {
    const old = new Date(Date.now() - 600_000).toISOString();
    const recent = new Date(Date.now() - 20_000).toISOString();
    await addTrip({ gps: [8.1, 124.1, old, 5] });
    await addTrip({ gps: [8.22219, 124.25961, recent, 3] });
    await addTrip({}); // no fix, newest by time
    const bus = (await get('/api/fleet/status')).body[0];
    expect(bus.current_lat).toBeCloseTo(8.22219, 5);
    expect(bus.current_lon).toBeCloseTo(124.25961, 5);
    expect(new Date(bus.gps_fix_at).toISOString()).toBe(recent);
    expect(bus.gps_accuracy_m).toBeCloseTo(3);
  });

  it('keeps vehicles separate and reports assigned route and conductor names', async () => {
    await pool.query("INSERT INTO routes (name) VALUES ('Tibanga - Pala-o')");
    await pool.query("INSERT INTO conductors (username, password_hash, name) VALUES ('c1','x','Ana')");
    await pool.query("UPDATE vehicles SET assigned_route_id = 1, assigned_conductor_id = 1 WHERE id = 'BUS-1'");
    await addTrip({ vehicle: 'BUS-2', fare: 800, states: ['ASSIGNED_PAID'] });
    const [b1, b2] = (await get('/api/fleet/status')).body;
    expect(b1).toMatchObject({ assigned_route_name: 'Tibanga - Pala-o', assigned_conductor_name: 'Ana', daily_revenue: 0 });
    expect(b2.daily_revenue).toBe(800);
  });

  it('files a trip from a phone with a wrong clock under the server receive time', async () => {
    await addTrip({ at: '1970-01-01T00:00:00Z', suspect: true, received: new Date().toISOString(), fare: 1100, states: [] });
    await addTrip({ at: '1970-01-01T00:00:00Z', suspect: true, received: new Date().toISOString(), fare: 1300, states: ['ASSIGNED_PAID'] });
    const bus = (await get('/api/fleet/status')).body[0];
    expect(bus.daily_revenue).toBe(1300);
    expect(bus.occupancy_now).toBe(2);
  });

  it('draws the "today" boundary in the report time zone, not UTC', async () => {
    // Manila is UTC+8. Today's first and last minutes in Manila fall on different UTC dates,
    // so a query that used UTC days would put them on the wrong side of "today".
    const manilaToday = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);
    const [y, m, d] = manilaToday.split('-').map(Number);
    const manila = (day: number, hour: number, min: number) => new Date(Date.UTC(y, m - 1, day, hour - 8, min)).toISOString();
    const cases = [
      { label: '00:30 Manila today (still yesterday in UTC)', at: manila(d, 0, 30), counts: true },
      { label: '23:30 Manila today (already counted in UTC)', at: manila(d, 23, 30), counts: true },
      { label: '23:30 Manila yesterday (today in UTC? no: yesterday)', at: manila(d - 1, 23, 30), counts: false },
      { label: '07:59 Manila yesterday', at: manila(d - 1, 7, 59), counts: false },
    ];
    for (const c of cases) {
      await pool.query('TRUNCATE events');
      await addTrip({ at: c.at, fare: 1000, states: ['ASSIGNED_PAID'] });
      expect((await get('/api/fleet/status')).body[0].daily_revenue, c.label).toBe(c.counts ? 1000 : 0);
    }
  });
});

describe('audit logs', () => {
  it('pages, counts and orders newest first', async () => {
    for (let i = 0; i < 7; i++) await addTrip({ at: new Date(Date.now() - i * 60_000).toISOString(), uid: `AABBCC0${i}` });
    const p1 = (await get('/api/audit/logs?page=1&limit=3')).body;
    expect(p1.metadata).toEqual({ total: 7, page: 1, limit: 3, totalPages: 3 });
    expect(p1.logs.map((l: any) => l.nfc_uid)).toEqual(['AABBCC00', 'AABBCC01', 'AABBCC02']);
    const p3 = (await get('/api/audit/logs?page=3&limit=3')).body;
    expect(p3.logs).toHaveLength(1);
  });

  it('searches card, vehicle and stop fields; wildcards are literal', async () => {
    await addTrip({ uid: 'DEADBEEF', vehicle: 'BUS-1' });
    await addTrip({ uid: '12345678', vehicle: 'BUS-2', override: '50% off, waived' });
    expect((await get('/api/audit/logs?search=deadbeef')).body.logs).toHaveLength(1);
    expect((await get('/api/audit/logs?search=BUS-2')).body.logs).toHaveLength(1);
    expect((await get('/api/audit/logs?search=50%25')).body.logs).toHaveLength(1);
    expect((await get('/api/audit/logs?search=%25')).body.logs).toHaveLength(1); // not "match everything"
    expect((await get('/api/audit/logs?search=_______')).body.logs).toHaveLength(0);
    expect((await get("/api/audit/logs?search=' OR 1=1 --")).body.logs).toHaveLength(0);
  });

  it('filters by date in the report time zone', async () => {
    await addTrip({ at: '2026-03-10T15:00:00Z' }); // 23:00 Manila on the 10th
    await addTrip({ at: '2026-03-10T17:00:00Z', uid: 'AABBCC01' }); // 01:00 Manila on the 11th
    const on11 = (await get('/api/audit/logs?startDate=2026-03-11&endDate=2026-03-11')).body;
    expect(on11.logs.map((l: any) => l.nfc_uid)).toEqual(['AABBCC01']);
    const on10 = (await get('/api/audit/logs?startDate=2026-03-10&endDate=2026-03-10')).body;
    expect(on10.metadata.total).toBe(1);
  });

  it('rejects bad paging and dates instead of guessing', async () => {
    for (const q of ['page=0', 'page=abc', 'limit=0', 'limit=100000', 'startDate=yesterday', 'search=' + 'x'.repeat(101)]) {
      expect((await get(`/api/audit/logs?${q}`)).status, q).toBe(400);
    }
  });

  it('shows the card UUID, GPS fix, fare version, override and clock flag', async () => {
    await addTrip({ override: 'driver waived', suspect: true, gps: [8.2, 124.2, daysAgo(0), 4] });
    const log = (await get('/api/audit/logs')).body.logs[0];
    expect(log).toMatchObject({ override_reason: 'driver waived', clock_suspect: true, fare_version: 0, gps_lat: 8.2, event_type: 'trip_created' });
    expect(log.card_uuid).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('revenue analytics', () => {
  it('groups paid fares by local day and by passenger category', async () => {
    await addTrip({ fare: 1500, states: ['ASSIGNED_PAID'] });
    await addTrip({ fare: 1200, discount: 300, category: 'student', states: ['ASSIGNED_PAID'] });
    await addTrip({ fare: 1000, discount: 250, category: 'pwd', states: ['ASSIGNED_PAID', 'RETURNED'] });
    await addTrip({ fare: 900, states: [] }); // boarded, never paid
    const today = (await get('/api/analytics/revenue?days=7')).body.at(-1);
    expect(today).toMatchObject({
      total_revenue: 37, regular_revenue: 15, discounted_revenue: 22, discount_given: 5.5,
      total_payments: 3, total_boardings: 4,
    });
    expect(today.by_category).toEqual({ regular: 15, student: 12, pwd: 10 });
  });

  it('honours the days window and omits days with no trips', async () => {
    await addTrip({ at: daysAgo(10), states: ['ASSIGNED_PAID'] });
    await addTrip({ at: daysAgo(2), states: ['ASSIGNED_PAID'] });
    expect((await get('/api/analytics/revenue?days=7')).body).toHaveLength(1);
    expect((await get('/api/analytics/revenue?days=30')).body).toHaveLength(2);
  });

  it('rejects an out-of-range window', async () => {
    for (const d of ['0', '-1', '366', 'x']) expect((await get(`/api/analytics/revenue?days=${d}`)).status, d).toBe(400);
  });
});

describe('CSV export', () => {
  it('has a header, a BOM, CRLF lines and money in pesos', async () => {
    await addTrip({ fare: 1250, discount: 250, category: 'student', states: ['ASSIGNED_PAID'] });
    const res = await get('/api/export/audit');
    expect(res.headers['content-type']).toMatch(/text\/csv/);
    expect(res.headers['content-disposition']).toMatch(/attachment/);
    const text = res.text;
    expect(text.charCodeAt(0)).toBe(0xfeff);
    const lines = text.slice(1).trimEnd().split('\r\n');
    expect(lines).toHaveLength(3);
    expect(lines[0]).toMatch(/^Time \(UTC\),Received/);
    const trip = lines.find((l) => l.includes('trip_created'))!;
    expect(trip).toContain(',12.50,12.50,2.50,');
    expect(trip.split(',')[1]).toMatch(/^"\d{4}-/);
  });

  it('neutralises spreadsheet formulas and survives quotes and commas', async () => {
    await addTrip({ override: '=HYPERLINK("http://evil","x")' });
    await addTrip({ override: 'said "ok", then left', uid: 'AABBCC01' });
    await addTrip({ override: '@SUM(A1)', uid: 'AABBCC02' });
    const text = (await get('/api/export/audit')).text;
    expect(text).toContain(`"'=HYPERLINK(""http://evil"",""x"")"`);
    expect(text).toContain('"said ""ok"", then left"');
    expect(text).toContain(`"'@SUM(A1)"`);
    expect(text).not.toMatch(/,"=HYPERLINK/);
  });

  it('csvText handles null, newlines and every formula starter', () => {
    expect(csvText(null)).toBe('');
    expect(csvText('a\nb')).toBe('"a\nb"');
    for (const c of ['=', '+', '-', '@', '\t', '\r']) expect(csvText(`${c}x`).startsWith(`"'${c}`)).toBe(true);
    expect(csvText('plain')).toBe('"plain"');
  });

  it('flags a truncated export instead of silently cutting it', async () => {
    expect(EXPORT_ROW_LIMIT).toBe(50_000);
    const res = await get('/api/export/audit');
    expect(res.headers['x-export-truncated']).toBeUndefined();
  });
});

describe('conductor list', () => {
  it('shows each conductor with their vehicle, sales today and last sync', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1','AAA-111')");
    await pool.query("INSERT INTO conductors (username, password_hash, name) VALUES ('c1','x','Ana'), ('c2','x','Ben')");
    await pool.query("UPDATE vehicles SET assigned_conductor_id = 1 WHERE id = 'BUS-1'");
    await addTrip({ fare: 1500, states: ['ASSIGNED_PAID'] });
    const [ana, ben] = (await get('/api/conductors')).body;
    expect(ana).toMatchObject({ name: 'Ana', assigned_vehicle: 'BUS-1', total_sales_today: 15, status: 'Active' });
    expect(ana.last_sync).not.toBe('N/A');
    expect(ben).toMatchObject({ name: 'Ben', assigned_vehicle: 'N/A', total_sales_today: 0, last_sync: 'N/A', status: 'Offline' });
    expect(JSON.stringify(ana)).not.toMatch(/password|hash/);
  });
});

describe('delivery summary', () => {
  it('reports per-channel volume and lag, and per-device last seen', async () => {
    const token = await enrollDevice(pool, 'dev-1');
    const sendAs = (channel: string, seq: number, ageMs: number) =>
      request(app).post('/api/events').set('Authorization', `Bearer ${token}`).send({
        delivery_channel: channel,
        events: [{ event_id: randomUUID(), device_id: 'dev-1', local_seq: seq, type: 'x_future', created_at_millis: Date.now() - ageMs, payload: {} }],
      });
    await sendAs('data_a', 1, 2_000);
    await sendAs('sms', 2, 120_000);
    const body = (await get('/api/delivery/summary')).body;
    const byChannel = Object.fromEntries(body.channels.map((c: any) => [c.delivery_channel, c]));
    expect(byChannel.data_a.events).toBe(1);
    expect(byChannel.sms.lag_p50_seconds).toBeGreaterThan(100);
    expect(body.devices[0]).toMatchObject({ device_id: 'dev-1', revoked: false, events_24h: 2 });
  });

  it('excludes wrong-clock events from lag figures and counts them per device', async () => {
    await enrollDevice(pool, 'dev-1');
    await addTrip({ at: '1970-01-01T00:00:00Z', suspect: true });
    const body = (await get('/api/delivery/summary')).body;
    expect(body.channels).toEqual([]);
    expect(body.devices[0].clock_suspect_events).toBe(1);
  });
});

describe('live updates', () => {
  it('pushes the new fleet state to dashboards after events are accepted', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1','AAA-111')");
    const token = await enrollDevice(pool, 'dev-1', 'BUS-1');
    const tripId = randomUUID();
    const res = await request(app).post('/api/events').set('Authorization', `Bearer ${token}`).send({
      events: [{
        event_id: randomUUID(), device_id: 'dev-1', local_seq: 1, type: 'trip_created', created_at_millis: Date.now(),
        payload: {
          tripId, cardUuid: randomUUID(), nfcUid: 'AABBCCDD', boardingStopId: 's1', declaredDestinationStopId: 's2',
          computedFareCentavos: 1500, fareCentavos: 1500, discountCentavos: 0, fareVersion: 0,
          gpsLat: 8.2, gpsLon: 124.2, gpsAccuracyMeters: 3, gpsFixAtMillis: Date.now() - 1000,
        },
      }],
    });
    expect(res.body.accepted).toHaveLength(1);
    const update = emitted.find((e) => e.event === 'fleet_update')!;
    expect(update.payload).toMatchObject({ vehicle_id: 'BUS-1', occupancy_now: 1, current_lat: 8.2 });
  });

  it('does not push anything for a batch of only duplicates, and a push failure never fails the request', async () => {
    const token = await enrollDevice(pool, 'dev-1');
    const batch = { events: [{ event_id: randomUUID(), device_id: 'dev-1', local_seq: 1, type: 'x_future', created_at_millis: Date.now(), payload: {} }] };
    await request(app).post('/api/events').set('Authorization', `Bearer ${token}`).send(batch);
    emitted.length = 0;
    await request(app).post('/api/events').set('Authorization', `Bearer ${token}`).send(batch);
    expect(emitted).toEqual([]);

    const failing = createApp({ pool, jwtSecret: TEST_JWT_SECRET, emit: () => { throw new Error('socket down'); } });
    const again = await request(failing).post('/api/events').set('Authorization', `Bearer ${token}`)
      .send({ events: [{ ...batch.events[0], event_id: randomUUID(), local_seq: 2 }] });
    expect(again.status).toBe(200);
    expect(again.body.accepted).toHaveLength(1);
  });
});

describe('likePattern', () => {
  it('escapes percent, underscore and backslash', () => {
    expect(likePattern('50%_a\\b')).toBe('%50\\%\\_a\\\\b%');
  });
});
