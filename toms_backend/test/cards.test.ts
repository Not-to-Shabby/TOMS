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
  it('enrolls a new card with normalized uppercase hex NFC UID', async () => {
    const res = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({
        nfc_uid: '6f:f1:ad:39',
        label: 'Card 001',
        notes: 'Initial batch',
      });

    expect(res.status).toBe(201);
    expect(res.body.nfc_uid).toBe('6FF1AD39');
    expect(res.body.label).toBe('Card 001');
    expect(res.body.status).toBe('active');
    expect(res.body.card_uuid).toMatch(/^[0-9a-f-]{36}$/);

    const dbRow = (await pool.query('SELECT * FROM cards WHERE nfc_uid = $1', ['6FF1AD39'])).rows[0];
    expect(dbRow.label).toBe('Card 001');
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

  it('lists enrolled cards with search, status filtering, and pagination', async () => {
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: '11111111', label: 'Alpha' });
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: '22222222', label: 'Beta' });
    await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: '33333333', label: 'Gamma' });

    const listAll = await request(app).get('/api/cards').set('Authorization', `Bearer ${admin}`);
    expect(listAll.status).toBe(200);
    expect(listAll.body.metadata.total).toBe(3);

    const search = await request(app).get('/api/cards?search=beta').set('Authorization', `Bearer ${admin}`);
    expect(search.body.cards).toHaveLength(1);
    expect(search.body.cards[0].label).toBe('Beta');
  });

  it('updates card status and label', async () => {
    const created = await request(app)
      .post('/api/cards')
      .set('Authorization', `Bearer ${admin}`)
      .send({ nfc_uid: '44444444', label: 'Original' });

    const cardUuid = created.body.card_uuid;

    const updated = await request(app)
      .patch(`/api/cards/${cardUuid}`)
      .set('Authorization', `Bearer ${admin}`)
      .send({ status: 'suspended', label: 'Temporarily Suspended' });

    expect(updated.status).toBe(200);
    expect(updated.body.status).toBe('suspended');
    expect(updated.body.label).toBe('Temporarily Suspended');
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

  it('GET /api/cards/approved returns active cards mapping for conductor devices and omits suspended/retired cards', async () => {
    const c1 = await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: 'AAAA1111' });
    const c2 = await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: 'BBBB2222' });
    const c3 = await request(app).post('/api/cards').set('Authorization', `Bearer ${admin}`).send({ nfc_uid: 'CCCC3333' });

    // Suspend c2 and retire c3
    await request(app).patch(`/api/cards/${c2.body.card_uuid}`).set('Authorization', `Bearer ${admin}`).send({ status: 'suspended' });
    await request(app).delete(`/api/cards/${c3.body.card_uuid}`).set('Authorization', `Bearer ${admin}`);

    // Conductor device fetches approved cards
    const res = await request(app)
      .get('/api/cards/approved')
      .set('Authorization', `Bearer ${deviceToken}`);

    expect(res.status).toBe(200);
    expect(res.body.count).toBe(1);
    expect(res.body.cards).toEqual({
      AAAA1111: c1.body.card_uuid,
    });
  });

  it('GET /api/cards/approved rejects unauthenticated requests', async () => {
    const res = await request(app).get('/api/cards/approved');
    expect(res.status).toBe(401);
  });
});
