import { randomUUID } from 'crypto';
import fs from 'fs';
import path from 'path';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { RateLimiter } from '../src/rateLimit';
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

describe('Active fare with nothing on file', () => {
  it('answers 404 instead of a made-up matrix, for a route and for all routes', async () => {
    const byRoute = await request(app).get('/api/fares/active/123');
    expect(byRoute.status).toBe(404);
    expect(byRoute.body.base_fare).toBeUndefined();
    const all = await request(app).get('/api/fares/active');
    expect(all.status).toBe(404);
  });

  it('refuses a blank base fare or per-km fare instead of saving zero as the fare', async () => {
    const routeRes = await pool.query("INSERT INTO routes (name) VALUES ('Blank Fare Route') RETURNING id");
    const routeId = routeRes.rows[0].id;
    const blankBase = await request(app)
      .post('/api/fares')
      .set('Authorization', `Bearer ${admin}`)
      .field('route_id', routeId)
      .field('base_fare', '')
      .field('per_km_fare', '2.5');
    const blankPerKm = await request(app)
      .post('/api/fares')
      .set('Authorization', `Bearer ${admin}`)
      .field('route_id', routeId)
      .field('base_fare', '15')
      .field('per_km_fare', '');
    expect(blankBase.status).toBe(400);
    expect(blankPerKm.status).toBe(400);
    expect((await pool.query('SELECT count(*) FROM fare_matrices')).rows[0].count).toBe(0);
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

  it('shows no fare figures when no matrix is on file, instead of made-up defaults', async () => {
    const cardUuid = randomUUID();
    const tripId = randomUUID();
    await request(app).post('/api/events').set('Authorization', `Bearer ${deviceToken}`).send({
      events: [
        {
          event_id: randomUUID(),
          device_id: 'dev-1',
          local_seq: 1,
          type: 'trip_created',
          created_at_millis: Date.now(),
          payload: {
            tripId,
            cardUuid,
            nfcUid: '6FF1AD39',
            boardingStopId: 'A',
            declaredDestinationStopId: 'B',
            computedFareCentavos: 1500,
            fareCentavos: 1500,
            discountCentavos: 0,
            fareVersion: 0,
            passengerCount: 1,
            passengers: [],
          },
        },
      ],
    });
    const { rows } = await pool.query('SELECT receipt_token FROM trip_status WHERE trip_id = $1', [tripId]);
    const res = await request(app).get(`/api/receipt/${rows[0].receipt_token}`);
    expect(res.status).toBe(200);
    expect(res.body.ltfrb).toEqual({ configured: false });
    expect(res.body.ltfrb).not.toHaveProperty('base_fare');
    expect(res.body.ltfrb).not.toHaveProperty('per_km_fare');
  });

  it('GET /r/:card_uuid refuses to leak past receipt once card is RETURNED, while past receipt_token remains viewable', async () => {
    const cardUuid = randomUUID();
    const tripId = randomUUID();
    // The whole ride is placed 40 minutes back, so the return sits beyond the 30-minute grace window.
    const boardedAt = Date.now() - 40 * 60 * 1000;

    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({
      nfc_uid: 'A1B2C3D4',
      card_uuid: cardUuid,
    });

    // 1. Passenger boards and pays: trip is ASSIGNED_PAID
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
            created_at_millis: boardedAt,
            payload: {
              tripId,
              cardUuid,
              nfcUid: 'A1B2C3D4',
              boardingStopId: 'Stop A',
              declaredDestinationStopId: 'Stop B',
              computedFareCentavos: 2500,
              fareCentavos: 2500,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 1,
              passengers: [],
            },
          },
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 2,
            type: 'card_state_changed',
            created_at_millis: boardedAt + 1000,
            payload: {
              nfcUid: 'A1B2C3D4',
              cardUuid,
              tripId,
              state: 'ASSIGNED_PAID',
            },
          },
        ],
      });

    // While passenger is on the bus, scanning card QR successfully redirects to their receipt
    const activeScan = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(activeScan.status).toBe(200);
    expect(activeScan.body.active).toBe(true);
    const passengerReceiptToken = activeScan.body.receipt_token;
    expect(passengerReceiptToken).toMatch(/^[0-9a-f-]{36}$/);

    // 2. Passenger alights and drops card into Return Machine -> card state becomes RETURNED, 35 minutes ago
    await request(app)
      .post('/api/events')
      .set('Authorization', `Bearer ${deviceToken}`)
      .send({
        events: [
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 3,
            type: 'card_state_changed',
            created_at_millis: Date.now() - 35 * 60 * 1000,
            payload: {
              nfcUid: 'A1B2C3D4',
              cardUuid,
              tripId,
              state: 'RETURNED',
            },
          },
        ],
      });

    // 3. Someone scans the physical card QR code (or a photo of it) AFTER the grace window has passed:
    // It must NOT reveal the passenger's receipt!
    const returnedJson = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(returnedJson.status).toBe(404);
    expect(returnedJson.body.active).toBe(false);
    expect(returnedJson.body.receipt_token).toBeUndefined();

    const returnedHtml = await request(app).get(`/r/${cardUuid}`).set('Accept', 'text/html');
    expect(returnedHtml.status).toBe(302);
    expect(returnedHtml.headers.location).toContain('/receipt/inactive');

    // 4. BUT the legitimate passenger who already saved their receipt token can STILL view their receipt:
    const pastReceipt = await request(app).get(`/api/receipt/${passengerReceiptToken}`);
    expect(pastReceipt.status).toBe(200);
    expect(pastReceipt.body.status).toBe('PAID');
    expect(pastReceipt.body.fare_pesos).toBe('25.00');
  });

  it('GET /r/:card_uuid treats an open trip older than 24 hours as inactive', async () => {
    const cardUuid = randomUUID();
    const tripId = randomUUID();
    // 25 hours ago
    const oldTime = Date.now() - 25 * 3600 * 1000;

    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({
      nfc_uid: 'E5F6A7B8',
      card_uuid: cardUuid,
    });

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
            created_at_millis: oldTime,
            payload: {
              tripId,
              cardUuid,
              nfcUid: 'E5F6A7B8',
              boardingStopId: 'Stop 1',
              declaredDestinationStopId: 'Stop 2',
              computedFareCentavos: 1500,
              fareCentavos: 1500,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 1,
              passengers: [],
            },
          },
        ],
      });

    // Older than 24h open window -> inactive on static card scan
    const res = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(res.status).toBe(404);
    expect(res.body.active).toBe(false);
  });

  it('GET /r/:card_uuid still opens the last receipt within the return grace window, flagged as grace', async () => {
    const cardUuid = randomUUID();
    const tripId = randomUUID();
    // The passenger got off and handed the card back 5 minutes ago: inside RETURN_GRACE_MINUTES.
    const boardedAt = Date.now() - 7 * 60 * 1000;

    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({
      nfc_uid: 'B1C2D3E4',
      card_uuid: cardUuid,
    });

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
            created_at_millis: boardedAt,
            payload: {
              tripId,
              cardUuid,
              nfcUid: 'B1C2D3E4',
              boardingStopId: 'Stop A',
              declaredDestinationStopId: 'Stop B',
              computedFareCentavos: 2500,
              fareCentavos: 2500,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 1,
              passengers: [],
            },
          },
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 2,
            type: 'card_state_changed',
            created_at_millis: boardedAt + 1000,
            payload: { nfcUid: 'B1C2D3E4', cardUuid, tripId, state: 'ASSIGNED_PAID' },
          },
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 3,
            type: 'card_state_changed',
            created_at_millis: Date.now() - 5 * 60 * 1000,
            payload: { nfcUid: 'B1C2D3E4', cardUuid, tripId, state: 'RETURNED' },
          },
        ],
      });

    const json = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(json.status).toBe(200);
    expect(json.body.active).toBe(true);
    expect(json.body.grace).toBe(true);
    expect(json.body.receipt_token).toMatch(/^[0-9a-f-]{36}$/);

    const html = await request(app).get(`/r/${cardUuid}`).set('Accept', 'text/html');
    expect(html.status).toBe(302);
    expect(html.headers.location).toBe(`/receipt/${json.body.receipt_token}?grace=1`);

    // The receipt behind the grace redirect is fully viewable.
    const receipt = await request(app).get(`/api/receipt/${json.body.receipt_token}`);
    expect(receipt.status).toBe(200);
  });

  it('GET /r/:card_uuid shows the next passenger their own receipt, never the previous one, even within the grace window', async () => {
    const cardUuid = randomUUID();
    const firstTripId = randomUUID();
    const secondTripId = randomUUID();
    // The previous passenger handed the card back 5 minutes ago; a new passenger boards now.
    const firstBoardedAt = Date.now() - 7 * 60 * 1000;

    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({
      nfc_uid: 'C2D3E4F5',
      card_uuid: cardUuid,
    });

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
            created_at_millis: firstBoardedAt,
            payload: {
              tripId: firstTripId,
              cardUuid,
              nfcUid: 'C2D3E4F5',
              boardingStopId: 'Stop A',
              declaredDestinationStopId: 'Stop B',
              computedFareCentavos: 2500,
              fareCentavos: 2500,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 1,
              passengers: [],
            },
          },
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 2,
            type: 'card_state_changed',
            created_at_millis: firstBoardedAt + 1000,
            payload: { nfcUid: 'C2D3E4F5', cardUuid, tripId: firstTripId, state: 'RETURNED' },
          },
          {
            event_id: randomUUID(),
            device_id: 'dev-1',
            local_seq: 3,
            type: 'trip_created',
            created_at_millis: Date.now(),
            payload: {
              tripId: secondTripId,
              cardUuid,
              nfcUid: 'C2D3E4F5',
              boardingStopId: 'Stop A',
              declaredDestinationStopId: 'Stop C',
              computedFareCentavos: 1800,
              fareCentavos: 1800,
              discountCentavos: 0,
              fareVersion: 1,
              passengerCount: 1,
              passengers: [],
            },
          },
        ],
      });

    const { rows } = await pool.query('SELECT receipt_token FROM trip_status WHERE trip_id = $1', [firstTripId]);
    const firstReceiptToken = rows[0].receipt_token;

    const res = await request(app).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(res.status).toBe(200);
    expect(res.body.active).toBe(true);
    expect(res.body.grace).toBeUndefined();
    expect(res.body.current_state).toBe('ASSIGNED_UNPAID');
    // The scan opens the NEW trip's receipt, and the previous passenger's is not reachable from this card.
    expect(res.body.receipt_token).not.toBe(firstReceiptToken);
  });

  it('limits how fast one address can look up public card and receipt links', async () => {
    const limited = createApp({ pool, jwtSecret: TEST_JWT_SECRET, publicLimiter: new RateLimiter(3, 60_000) });
    const cardUuid = randomUUID();
    for (let i = 0; i < 3; i++) {
      expect((await request(limited).get(`/r/${cardUuid}`).set('Accept', 'application/json')).status).toBe(404);
    }
    const blocked = await request(limited).get(`/r/${cardUuid}`).set('Accept', 'application/json');
    expect(blocked.status).toBe(429);
    expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
    expect((await request(limited).get(`/api/receipt/${randomUUID()}`)).status).toBe(429);
  });
});
