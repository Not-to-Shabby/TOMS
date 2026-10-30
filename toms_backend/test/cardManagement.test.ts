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
  admin = await adminToken(pool, app, 'boss');
  deviceToken = await enrollDevice(pool, 'dev-1');
});

const auth = () => ({ Authorization: `Bearer ${admin}` });
const post = (path: string, body: object = {}) => request(app).post(path).set(auth()).send(body);
const patch = (uuid: string, body: object) => request(app).patch(`/api/cards/${uuid}`).set(auth()).send(body);
const history = async (uuid: string) => (await request(app).get(`/api/cards/${uuid}/history`).set(auth())).body.events as {
  action: string; from_status: string | null; to_status: string | null; from_nfc_uid: string | null; to_nfc_uid: string | null;
  reason: string | null; admin_username: string; details: Record<string, { from: unknown; to: unknown }>;
}[];

/** A paired, active card. */
async function activeCard(nfc = '04A1B2C3', extra: object = {}) {
  const res = await post('/api/cards', { nfc_uid: nfc, label: 'Card A', ...extra });
  expect(res.status).toBe(201);
  return res.body.card_uuid as string;
}

/** Puts a passenger on the card: an open trip uploaded the way a phone does it. */
async function openTrip(cardUuid: string, nfcUid = '04A1B2C3', seq = 1) {
  const res = await request(app)
    .post('/api/events')
    .set('Authorization', `Bearer ${deviceToken}`)
    .send({
      events: [
        {
          event_id: randomUUID(),
          device_id: 'dev-1',
          local_seq: seq,
          type: 'trip_created',
          created_at_millis: Date.now(),
          payload: {
            tripId: randomUUID(), cardUuid, nfcUid, boardingStopId: 'A', declaredDestinationStopId: 'B',
            computedFareCentavos: 1500, fareCentavos: 1500, discountCentavos: 0, fareVersion: 1, passengerCount: 1,
            passengers: [{ categoryId: null, count: 1, perPersonCentavos: 1500, fareCentavos: 1500, discountCentavos: 0 }],
          },
        },
      ],
    });
  expect(res.body.accepted).toHaveLength(1);
}

describe('card history', () => {
  it('records who enrolled a card and with which chip', async () => {
    const uuid = await activeCard();
    const [e] = await history(uuid);
    expect(e).toMatchObject({ action: 'enrolled', to_status: 'active', to_nfc_uid: '04A1B2C3', admin_username: 'boss' });
  });

  it('records one created event for every card in a batch', async () => {
    const res = await post('/api/cards/batch', { count: 3, prefix: 'H-' });
    const events = await pool.query("SELECT card_uuid FROM card_events WHERE action = 'created'");
    expect(events.rowCount).toBe(3);
    expect(new Set(events.rows.map((r) => r.card_uuid))).toEqual(new Set(res.body.cards.map((c: { card_uuid: string }) => c.card_uuid)));
  });

  it('records pairing and unpairing with the chip involved', async () => {
    const batch = await post('/api/cards/batch', { count: 1, prefix: 'P-' });
    const uuid = batch.body.cards[0].card_uuid;
    await post(`/api/cards/${uuid}/pair`, { nfc_uid: '04AA0001' });
    await post(`/api/cards/${uuid}/unpair`, { reason: 'wrong card' });
    const events = await history(uuid);
    expect(events.map((e) => e.action)).toEqual(['unpaired', 'paired', 'created']);
    expect(events[1]).toMatchObject({ from_status: 'unpaired', to_status: 'active', to_nfc_uid: '04AA0001' });
    expect(events[0]).toMatchObject({ from_nfc_uid: '04AA0001', reason: 'wrong card' });
  });

  it('records each status change with the reason and the admin', async () => {
    const uuid = await activeCard();
    await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'Left on the bus' });
    const [e] = await history(uuid);
    expect(e).toMatchObject({ action: 'status_changed', from_status: 'active', to_status: 'lost', reason: 'Left on the bus', admin_username: 'boss' });
  });

  it('records label, notes and vehicle edits as before and after', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111')");
    const uuid = await activeCard();
    await patch(uuid, { label: 'Seat 5', notes: 'new strap', vehicle_id: 'BUS-1' });
    const events = await history(uuid);
    const edited = events.find((e) => e.action === 'edited')!;
    expect(edited.details.label).toEqual({ from: 'Card A', to: 'Seat 5' });
    expect(edited.details.notes).toEqual({ from: null, to: 'new strap' });
    expect(events.find((e) => e.action === 'vehicle_changed')!.details.vehicle_id).toEqual({ from: null, to: 'BUS-1' });
  });

  it('does not write an event when nothing actually changed', async () => {
    const uuid = await activeCard();
    await patch(uuid, { label: 'Card A' });
    expect((await history(uuid)).map((e) => e.action)).toEqual(['enrolled']);
  });

  it('lists newest first and needs an admin', async () => {
    const uuid = await activeCard();
    await post(`/api/cards/${uuid}/status`, { status: 'suspended' });
    expect((await history(uuid)).map((e) => e.action)).toEqual(['status_changed', 'enrolled']);
    expect((await request(app).get(`/api/cards/${uuid}/history`)).status).toBe(401);
    expect((await request(app).get(`/api/cards/${randomUUID()}/history`).set(auth())).status).toBe(404);
    expect((await request(app).get('/api/cards/not-a-uuid/history').set(auth())).status).toBe(400);
  });

  it('writes no history when the change is refused', async () => {
    const uuid = await activeCard();
    const before = (await history(uuid)).length;
    expect((await patch(uuid, { vehicle_id: 'NO-SUCH-BUS' })).status).toBe(400);
    expect((await history(uuid)).length).toBe(before);
  });

  it('undoes the status change and its history together when a later step of the same request fails', async () => {
    const uuid = await activeCard();
    const before = (await history(uuid)).length;
    // The status change succeeds first (and writes its history row); the unknown vehicle then fails the request.
    const res = await patch(uuid, { status: 'suspended', vehicle_id: 'NO-SUCH-BUS' });
    expect(res.status).toBe(400);
    expect((await pool.query('SELECT status FROM cards WHERE card_uuid = $1', [uuid])).rows[0].status).toBe('active');
    expect((await history(uuid)).length).toBe(before);
  });
});

describe('editing label and notes', () => {
  it('clears a label or note when sent null or blank', async () => {
    const uuid = await activeCard('04A1B2C3', { notes: 'old note' });
    const cleared = await patch(uuid, { label: null, notes: '   ' });
    expect(cleared.status).toBe(200);
    expect(cleared.body.label).toBeNull();
    expect(cleared.body.notes).toBeNull();
  });

  it('keeps a field that is left out', async () => {
    const uuid = await activeCard('04A1B2C3', { notes: 'keep me' });
    const res = await patch(uuid, { label: 'Only label' });
    expect(res.body.notes).toBe('keep me');
    expect(res.body.label).toBe('Only label');
  });

  it('moves updated_at forward when a card changes', async () => {
    const uuid = await activeCard();
    const before = (await pool.query('SELECT updated_at FROM cards WHERE card_uuid = $1', [uuid])).rows[0].updated_at as Date;
    await new Promise((r) => setTimeout(r, 20));
    await patch(uuid, { label: 'Changed' });
    const after = (await pool.query('SELECT updated_at FROM cards WHERE card_uuid = $1', [uuid])).rows[0].updated_at as Date;
    expect(after.getTime()).toBeGreaterThan(before.getTime());
  });
});

describe('status rules', () => {
  it.each([
    ['active', 'suspended'], ['active', 'lost'], ['active', 'retired'],
  ])('allows %s to %s', async (_from, to) => {
    const uuid = await activeCard();
    const res = await post(`/api/cards/${uuid}/status`, { status: to, reason: 'because' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe(to);
  });

  it('lets a lost card be found again', async () => {
    const uuid = await activeCard();
    await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'misplaced' });
    const found = await post(`/api/cards/${uuid}/status`, { status: 'active' });
    expect(found.status).toBe(200);
    expect(found.body.status).toBe('active');
  });

  it('refuses an illegal move with a plain message and leaves the card alone', async () => {
    const uuid = await activeCard();
    await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'misplaced' });
    const res = await post(`/api/cards/${uuid}/status`, { status: 'suspended' });
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('A lost card cannot be changed to suspended.');
    expect((await pool.query('SELECT status FROM cards WHERE card_uuid = $1', [uuid])).rows[0].status).toBe('lost');
  });

  it('requires a reason for lost and retired but not for suspend', async () => {
    const uuid = await activeCard();
    const noReason = await post(`/api/cards/${uuid}/status`, { status: 'lost' });
    expect(noReason.status).toBe(400);
    expect(noReason.body.needs_reason).toBe(true);
    const blank = await post(`/api/cards/${uuid}/status`, { status: 'retired', reason: '   ' });
    expect(blank.status).toBe(400);
    expect((await post(`/api/cards/${uuid}/status`, { status: 'suspended' })).status).toBe(200);
  });

  it('keeps a retired card final except for restoring it', async () => {
    const uuid = await activeCard();
    await post(`/api/cards/${uuid}/status`, { status: 'retired', reason: 'worn out' });
    const bad = await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'x' });
    expect(bad.status).toBe(409);
    expect(bad.body.error).toMatch(/Restore it first/);
    const restored = await post(`/api/cards/${uuid}/status`, { status: 'active' });
    expect(restored.status).toBe(200);
  });

  it('restores a card that was retired before it ever got a chip to unpaired', async () => {
    const batch = await post('/api/cards/batch', { count: 1, prefix: 'R-' });
    const uuid = batch.body.cards[0].card_uuid;
    await post(`/api/cards/${uuid}/status`, { status: 'retired', reason: 'misprint' });
    const back = await post(`/api/cards/${uuid}/status`, { status: 'unpaired' });
    expect(back.status).toBe(200);
    expect(back.body.status).toBe('unpaired');
  });

  it('refuses to make an unpaired card active or suspended by status alone', async () => {
    const batch = await post('/api/cards/batch', { count: 1, prefix: 'U-' });
    const uuid = batch.body.cards[0].card_uuid;
    const res = await post(`/api/cards/${uuid}/status`, { status: 'active' });
    expect(res.status).toBe(409);
    expect(res.body.error).toMatch(/Pair it first/);
  });

  it('applies the same rules to PATCH, so there is no way around them', async () => {
    const uuid = await activeCard();
    await patch(uuid, { status: 'lost', reason: 'misplaced' });
    const res = await patch(uuid, { status: 'suspended' });
    expect(res.status).toBe(409);
    expect((await patch(uuid, { status: 'retired' })).status).toBe(400);
  });

  it('does nothing and writes nothing when the status is already the one asked for', async () => {
    const uuid = await activeCard();
    const res = await post(`/api/cards/${uuid}/status`, { status: 'active' });
    expect(res.status).toBe(200);
    expect((await history(uuid)).map((e) => e.action)).toEqual(['enrolled']);
  });

  it('answers 404 for a card that does not exist and 400 for a bad id or status', async () => {
    expect((await post(`/api/cards/${randomUUID()}/status`, { status: 'lost', reason: 'x' })).status).toBe(404);
    expect((await post('/api/cards/nope/status', { status: 'lost', reason: 'x' })).status).toBe(400);
    const uuid = await activeCard();
    expect((await post(`/api/cards/${uuid}/status`, { status: 'broken' })).status).toBe(400);
  });

  it('keeps DELETE working, now through the same rules and recorded', async () => {
    const uuid = await activeCard();
    expect((await request(app).delete(`/api/cards/${uuid}`).set(auth())).status).toBe(200);
    const [e] = await history(uuid);
    expect(e).toMatchObject({ action: 'status_changed', to_status: 'retired' });
    expect(e.reason).toMatch(/Retired through the API/);
    expect((await request(app).delete(`/api/cards/${uuid}`).set(auth())).status).toBe(404);
  });
});

describe('open trips', () => {
  it('warns before suspending, losing or retiring a card that has a passenger aboard', async () => {
    const uuid = await activeCard();
    await openTrip(uuid);
    for (const status of ['suspended', 'lost', 'retired']) {
      const res = await post(`/api/cards/${uuid}/status`, { status, reason: 'x' });
      expect(res.status).toBe(409);
      expect(res.body.open_trips).toBe(1);
    }
    expect((await pool.query('SELECT status FROM cards WHERE card_uuid = $1', [uuid])).rows[0].status).toBe('active');
  });

  it('goes ahead once the admin confirms', async () => {
    const uuid = await activeCard();
    await openTrip(uuid);
    const res = await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'gone', confirm: true });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('lost');
  });

  it('does not warn about a card with no open trip', async () => {
    const uuid = await activeCard();
    expect((await post(`/api/cards/${uuid}/status`, { status: 'suspended' })).status).toBe(200);
  });

  it('warns on unpair too, and on PATCH', async () => {
    const uuid = await activeCard();
    await openTrip(uuid);
    const un = await post(`/api/cards/${uuid}/unpair`);
    expect(un.status).toBe(409);
    expect(un.body.open_trips).toBe(1);
    const p = await patch(uuid, { status: 'suspended' });
    expect(p.status).toBe(409);
    expect(p.body.open_trips).toBe(1);
    expect((await post(`/api/cards/${uuid}/unpair`, { confirm: true })).status).toBe(200);
  });

  it('does not warn when reactivating', async () => {
    const uuid = await activeCard();
    await post(`/api/cards/${uuid}/status`, { status: 'suspended' });
    await openTrip(uuid);
    expect((await post(`/api/cards/${uuid}/status`, { status: 'active' })).status).toBe(200);
  });
});

describe('a card that is no longer in use', () => {
  it.each(['lost', 'retired', 'suspended'])('shows a %s card to a passenger as not in use, not as an old receipt', async (status) => {
    const uuid = await activeCard();
    await openTrip(uuid);
    await post(`/api/cards/${uuid}/status`, { status, reason: 'x', confirm: true });

    const json = await request(app).get(`/r/${uuid}`).set('Accept', 'application/json');
    expect(json.status).toBe(410);
    expect(json.body).toMatchObject({ active: false, card_status: status });
    expect(json.body.receipt_token).toBeUndefined();

    const html = await request(app).get(`/r/${uuid}`).set('Accept', 'text/html');
    expect(html.status).toBe(302);
    expect(html.headers.location).toBe(`/receipt/inactive?card=${uuid}&state=${status}`);
  });

  it('still shows the receipt for an active card', async () => {
    const uuid = await activeCard();
    await openTrip(uuid);
    const res = await request(app).get(`/r/${uuid}`).set('Accept', 'application/json');
    expect(res.status).toBe(200);
    expect(res.body.receipt_token).toBeDefined();
  });

  it('shows the receipt again once a lost card is found', async () => {
    const uuid = await activeCard();
    await openTrip(uuid);
    await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'x', confirm: true });
    await post(`/api/cards/${uuid}/status`, { status: 'active' });
    expect((await request(app).get(`/r/${uuid}`).set('Accept', 'application/json')).status).toBe(200);
  });
});
