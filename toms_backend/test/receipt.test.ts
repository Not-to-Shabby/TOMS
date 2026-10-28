import { randomUUID } from 'crypto';
import fs from 'fs';
import path from 'path';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let admin: string;
let deviceToken: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
});

afterAll(async () => drop());

beforeEach(async () => {
  await pool.query('TRUNCATE admins, cards, devices, vehicles, routes, fare_matrices, events RESTART IDENTITY CASCADE');
  admin = await adminToken(pool, app);
  deviceToken = await enrollDevice(pool, 'dev-1');
});

describe('Verified Fares & Document Upload', () => {
  it('allows admin to set verified fare matrix and upload scanned LTFRB photocopy document', async () => {
    // Create dummy route
    const routeRes = await pool.query("INSERT INTO routes (name) VALUES ('Tibanga - Pala-o') RETURNING id");
    const routeId = routeRes.rows[0].id;

    // Create a temporary document file
    const tmpDoc = path.join(__dirname, 'test-ltfrb.png');
    fs.writeFileSync(tmpDoc, Buffer.from('fake-png-content-representing-ltfrb-signature'));

    try {
      const res = await request(app)
        .post('/api/fares')
        .set('Authorization', `Bearer ${admin}`)
        .field('route_id', routeId)
        .field('base_fare', 15.0)
        .field('base_distance_km', 4.0)
        .field('per_km_fare', 2.5)
        .field('rounding_step_cents', 100)
        .field('order_reference', 'LTFRB Memorandum Circular No. 2023-045')
        .field('discounts', JSON.stringify({ student: 20, pwd: 20, senior: 20 }))
        .attach('document', tmpDoc);

      expect(res.status).toBe(201);
      expect(res.body.order_reference).toBe('LTFRB Memorandum Circular No. 2023-045');
      expect(res.body.document_url).toMatch(/^\/uploads\/fares\/fare-.*\.png$/);
      expect(res.body.is_active).toBe(true);

      // Verify static serving of uploaded file
      const fileFetch = await request(app).get(res.body.document_url);
      expect(fileFetch.status).toBe(200);
      expect(fileFetch.body.toString()).toBe('fake-png-content-representing-ltfrb-signature');
    } finally {
      if (fs.existsSync(tmpDoc)) fs.unlinkSync(tmpDoc);
    }
  });

  it('deactivates previous fare matrix when a new one is uploaded for the same route', async () => {
    const routeRes = await pool.query("INSERT INTO routes (name) VALUES ('Tubod - City Proper') RETURNING id");
    const routeId = routeRes.rows[0].id;

    await request(app)
      .post('/api/fares')
      .set('Authorization', `Bearer ${admin}`)
      .field('route_id', routeId)
      .field('base_fare', 14.0)
      .field('per_km_fare', 2.0);

    const second = await request(app)
      .post('/api/fares')
      .set('Authorization', `Bearer ${admin}`)
      .field('route_id', routeId)
      .field('base_fare', 16.0)
      .field('per_km_fare', 2.5);

    expect(second.status).toBe(201);

    const activeRes = await request(app).get(`/api/fares/active/${routeId}`);
    expect(activeRes.status).toBe(200);
    expect(Number(activeRes.body.base_fare)).toBe(16.0);
  });
});

describe('Static Card QR & Public Receipt Flow', () => {
  it('GET /r/:card_uuid redirects to /receipt/:receipt_token when an active trip exists', async () => {
    const cardUuid = randomUUID();
    const tripId = randomUUID();
    const now = Date.now();

    // Register card
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({
      nfc_uid: '6FF1AD39',
      card_uuid: cardUuid,
    });

    // Device creates a trip with this card
    const uploadRes = await request(app)
      .post('/api/events')
      .set('Authorization', `Bearer ${deviceToken}`)
      .send({
        events: [
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 1,
            type: 'trip_created',
            created_at_millis: now,
            payload: {
              tripId,
              cardUuid,
              nfcUid: '6FF1AD39',
              boardingStopId: 'Stop 1 (Origin)',
              declaredDestinationStopId: 'Stop 4 (Terminal)',
              computedFareCentavos: 4500,
              fareCentavos: 4500,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 2,
              passengers: [
                { categoryId: null, count: 2, perPersonCentavos: 2250, fareCentavos: 4500, discountCentavos: 0 },
              ],
            },
          },
        ],
      });

    expect(uploadRes.body.accepted).toHaveLength(1);

    // Test JSON request
    const jsonRes = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(jsonRes.status).toBe(200);
    expect(jsonRes.body.active).toBe(true);
    expect(jsonRes.body.receipt_token).toMatch(/^[0-9a-f-]{36}$/);
    expect(jsonRes.body.redirect_url).toBe(`/receipt/${jsonRes.body.receipt_token}`);

    // Test HTML browser request (simulating scanning QR on camera)
    const htmlRes = await request(app).get(`/r/${cardUuid}`).set('Accept', 'text/html');
    expect(htmlRes.status).toBe(302);
    expect(htmlRes.headers.location).toBe(`/receipt/${jsonRes.body.receipt_token}`);
  });

  it('GET /r/:card_uuid returns inactive when card has no trips recorded', async () => {
    const cardUuid = randomUUID();
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({
      nfc_uid: '12345678',
      card_uuid: cardUuid,
    });

    const jsonRes = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(jsonRes.status).toBe(404);
    expect(jsonRes.body.active).toBe(false);

    const htmlRes = await request(app).get(`/r/${cardUuid}`).set('Accept', 'text/html');
    expect(htmlRes.status).toBe(302);
    expect(htmlRes.headers.location).toContain('/receipt/inactive');
  });

  it('GET /api/receipt/:receipt_token returns complete trip details and verified LTFRB fare information', async () => {
    const cardUuid = randomUUID();
    const tripId = randomUUID();
    const now = Date.now();

    // Setup vehicle, route, and verified fare matrix
    const routeRes = await pool.query("INSERT INTO routes (name) VALUES ('Buru-un Loop') RETURNING id");
    const routeId = routeRes.rows[0].id;
    await pool.query("INSERT INTO vehicles (id, plate_number, assigned_route_id) VALUES ('BUS-99', 'TO-9999', $1)", [routeId]);
    await pool.query("UPDATE devices SET vehicle_id = 'BUS-99' WHERE device_id = 'dev-1'");

    await pool.query(
      `INSERT INTO fare_matrices (route_id, base_fare, per_km_fare, order_reference, document_url, is_active)
       VALUES ($1, 15.0, 2.5, 'LTFRB Order 2024-001', '/uploads/fares/sample-signed.png', true)`,
      [routeId],
    );

    await request(app)
      .post('/api/events')
      .set('Authorization', `Bearer ${deviceToken}`)
      .send({
        events: [
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 1,
            type: 'trip_created',
            created_at_millis: now,
            payload: {
              tripId,
              cardUuid,
              nfcUid: '6FF1AD39',
              boardingStopId: 'Buru-un',
              declaredDestinationStopId: 'Del Carmen',
              computedFareCentavos: 3600,
              fareCentavos: 3600,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 2,
              passengers: [
                { categoryId: null, count: 2, perPersonCentavos: 1800, fareCentavos: 3600, discountCentavos: 0 },
              ],
            },
          },
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 2,
            type: 'card_state_changed',
            created_at_millis: now + 5000,
            payload: {
              nfcUid: '6FF1AD39',
              cardUuid,
              tripId,
              state: 'ASSIGNED_PAID',
            },
          },
        ],
      });

    // Lookup receipt_token for this trip
    const tokenRes = await pool.query('SELECT receipt_token FROM trip_status WHERE trip_id = $1', [tripId]);
    const receiptToken = tokenRes.rows[0].receipt_token;

    // Fetch public digital receipt
    const res = await request(app).get(`/api/receipt/${receiptToken}`);
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('PAID');
    expect(res.body.boarding_stop).toBe('Buru-un');
    expect(res.body.destination_stop).toBe('Del Carmen');
    expect(res.body.fare_pesos).toBe('36.00');
    expect(res.body.passenger_count).toBe(2);
    expect(res.body.vehicle.plate).toBe('TO-9999');
    expect(res.body.route_name).toBe('Buru-un Loop');
    expect(res.body.ltfrb.order_reference).toBe('LTFRB Order 2024-001');
    expect(res.body.ltfrb.document_url).toBe('/uploads/fares/sample-signed.png');
  });
});
