import { randomUUID } from 'crypto';
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
  await pool.query('TRUNCATE admins, cards, devices, vehicles, events RESTART IDENTITY CASCADE');
  admin = await adminToken(pool, app);
  deviceToken = await enrollDevice(pool, 'dev-1');
});

describe('Card Registry APIs', () => {
  it('enrolls a new card with normalized uppercase hex NFC UID and vehicle assignment', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111')");

    const res = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({
        nfc_uid: '6f:f1:ad:39',
        label: 'Card 001',
        notes: 'Initial batch',
        vehicle_id: 'BUS-1',
      });

    expect(res.status).toBe(201);
    expect(res.body.nfc_uid).toBe('6FF1AD39');
    expect(res.body.label).toBe('Card 001');
    expect(res.body.status).toBe('active');
    expect(res.body.vehicle_id).toBe('BUS-1');
    expect(res.body.card_uuid).toMatch(/^[0-9a-f-]{36}$/);

    const dbRow = (await pool.query('SELECT * FROM cards WHERE nfc_uid = $1', ['6FF1AD39'])).rows[0];
    expect(dbRow.label).toBe('Card 001');
    expect(dbRow.vehicle_id).toBe('BUS-1');
  });

  it('rejects enrollment with unknown vehicle_id', async () => {
    const res = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({
        nfc_uid: '6f:f1:ad:39',
        vehicle_id: 'NON-EXISTENT-BUS',
      });

    expect(res.status).toBe(400);
    expect(res.body.error).toContain('NON-EXISTENT-BUS');
  });

  it('allows specifying a custom card_uuid on enrollment', async () => {
    const customUuid = randomUUID();
    const res = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({
        nfc_uid: '36-AF-5E-75',
        card_uuid: customUuid,
        label: 'Custom QR Card',
      });

    expect(res.status).toBe(201);
    expect(res.body.card_uuid).toBe(customUuid);
    expect(res.body.nfc_uid).toBe('36AF5E75');
  });

  it('rejects duplicate NFC UID with 409 conflict', async () => {
    await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({ nfc_uid: '56:5E:A4:75' });

    const dup = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({ nfc_uid: '565EA475' });

    expect(dup.status).toBe(409);
    expect(dup.body.error).toMatch(/already enrolled/i);
  });

  it('rejects malformed NFC UIDs', async () => {
    const res = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({ nfc_uid: 'NOT-HEX-ZZ' });

    expect(res.status).toBe(400);
  });

  it('lists enrolled cards with search, status filtering, and vehicle filtering', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111'), ('BUS-2', 'BBB-222')");

    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: '11111111', label: 'Alpha', vehicle_id: 'BUS-1' });
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: '22222222', label: 'Beta', vehicle_id: 'BUS-2' });
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: '33333333', label: 'Gamma' }); // unassigned

    const listAll = await request(app).get('/api/cards').set('Authorization', `Bearer ${admin}`);
    expect(listAll.status).toBe(200);
    expect(listAll.body.metadata.total).toBe(3);

    // Filter by vehicle
    const bus1Cards = await request(app).get('/api/cards?vehicle_id=BUS-1').set('Authorization', `Bearer ${admin}`);
    expect(bus1Cards.body.cards).toHaveLength(1);
    expect(bus1Cards.body.cards[0].vehicle_id).toBe('BUS-1');
    expect(bus1Cards.body.cards[0].vehicle_plate).toBe('AAA-111');

    // Filter unassigned
    const unassigned = await request(app).get('/api/cards?vehicle_id=unassigned').set('Authorization', `Bearer ${admin}`);
    expect(unassigned.body.cards).toHaveLength(1);
    expect(unassigned.body.cards[0].label).toBe('Gamma');
    expect(unassigned.body.cards[0].vehicle_id).toBeNull();
  });

  it('updates card status, label, and vehicle assignment (assign & unassign)', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111'), ('BUS-2', 'BBB-222')");

    const created = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({ nfc_uid: '44444444', label: 'Original', vehicle_id: 'BUS-1' });

    const cardUuid = created.body.card_uuid;

    // Reassign to BUS-2
    const reassign = await request(app)
      .patch(`/api/cards/${cardUuid}`)
      .set('Authorization', `Bearer ${admin}`)
      .send({ vehicle_id: 'BUS-2', label: 'Assigned to Bus 2' });

    expect(reassign.status).toBe(200);
    expect(reassign.body.vehicle_id).toBe('BUS-2');
    expect(reassign.body.label).toBe('Assigned to Bus 2');

    // Unassign to null (spare pool)
    const unassign = await request(app)
      .patch(`/api/cards/${cardUuid}`)
      .set('Authorization', `Bearer ${admin}`)
      .send({ vehicle_id: null });

    expect(unassign.status).toBe(200);
    expect(unassign.body.vehicle_id).toBeNull();
  });

  it('retires a card via DELETE', async () => {
    const created = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({ nfc_uid: '55555555' });

    const cardUuid = created.body.card_uuid;

    const del = await request(app).delete(`/api/cards/${cardUuid}`).set('Authorization', `Bearer ${admin}`);
    expect(del.status).toBe(200);

    const row = (await pool.query('SELECT status FROM cards WHERE card_uuid = $1', [cardUuid])).rows[0];
    expect(row.status).toBe('retired');
  });

  it('GET /api/cards/approved returns vehicle cards + spare cards when device is enrolled to a vehicle', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111'), ('BUS-2', 'BBB-222')");
    await pool.query("UPDATE devices SET vehicle_id = 'BUS-1' WHERE device_id = 'dev-1'");

    const cBus1 = await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: 'AAAA1111', vehicle_id: 'BUS-1' });
    const cBus2 = await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: 'BBBB2222', vehicle_id: 'BUS-2' });
    const cSpare = await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: 'CCCC3333' }); // unassigned spare

    // Conductor device for BUS-1 fetches approved cards: receives BUS-1 cards + unassigned spare cards, NOT BUS-2 cards
    const res = await request(app)
      .get('/api/cards/approved')
      .set('Authorization', `Bearer ${deviceToken}`);

    expect(res.status).toBe(200);
    expect(res.body.count).toBe(2);
    expect(res.body.vehicle_id).toBe('BUS-1');
    expect(res.body.cards).toEqual({
      AAAA1111: cBus1.body.card_uuid,
      CCCC3333: cSpare.body.card_uuid,
    });
  });

  it('GET /api/cards/approved rejects unauthenticated requests', async () => {
    const res = await request(app).get('/api/cards/approved');
    expect(res.status).toBe(401);
  });
});
