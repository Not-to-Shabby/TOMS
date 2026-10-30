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

describe('replacing a chip', () => {
  const replace = (uuid: string, body: object) => post(`/api/cards/${uuid}/replace`, body);

  it('swaps the chip and keeps the serial, label and QR id', async () => {
    const batch = await post('/api/cards/batch', { count: 1, prefix: 'RP-' });
    const uuid = batch.body.cards[0].card_uuid;
    await post(`/api/cards/${uuid}/pair`, { nfc_uid: '04AA0001' });

    const res = await replace(uuid, { nfc_uid: '04:bb:00:02', reason: 'Chip cracked' });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ card_uuid: uuid, serial: 'RP-0001', nfc_uid: '04BB0002', status: 'active' });
  });

  it('records the old and new chip together in the history', async () => {
    const uuid = await activeCard('04AA0001');
    await replace(uuid, { nfc_uid: '04BB0002', reason: 'Chip cracked' });
    const [e] = await history(uuid);
    expect(e).toMatchObject({ action: 'replaced', from_nfc_uid: '04AA0001', to_nfc_uid: '04BB0002', reason: 'Chip cracked', admin_username: 'boss' });
  });

  it('brings a lost card back into service with its new chip', async () => {
    const uuid = await activeCard('04AA0001');
    await post(`/api/cards/${uuid}/status`, { status: 'lost', reason: 'misplaced' });
    const res = await replace(uuid, { nfc_uid: '04BB0002' });
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('active');
    expect((await history(uuid))[0]).toMatchObject({ from_status: 'lost', to_status: 'active' });
  });

  it('leaves a suspended card suspended', async () => {
    const uuid = await activeCard('04AA0001');
    await post(`/api/cards/${uuid}/status`, { status: 'suspended' });
    expect((await replace(uuid, { nfc_uid: '04BB0002' })).body.status).toBe('suspended');
  });

  it('lets the new chip work and the old one stop, on the phones list', async () => {
    const uuid = await activeCard('04AA0001');
    await replace(uuid, { nfc_uid: '04BB0002' });
    const list = await request(app).get('/api/cards/approved').set('Authorization', `Bearer ${deviceToken}`);
    expect(list.body.cards).toEqual({ '04BB0002': uuid });
  });

  it('refuses a chip that already belongs to another card, and changes nothing', async () => {
    const a = await activeCard('04AA0001');
    await activeCard('04CC0003');
    const res = await replace(a, { nfc_uid: '04CC0003' });
    expect(res.status).toBe(409);
    expect(res.body.error).toMatch(/already paired to another card/);
    expect((await pool.query('SELECT nfc_uid FROM cards WHERE card_uuid = $1', [a])).rows[0].nfc_uid).toBe('04AA0001');
    expect((await history(a)).map((e) => e.action)).toEqual(['enrolled']);
  });

  it('refuses the same chip, a card with no chip, and a retired card', async () => {
    const uuid = await activeCard('04AA0001');
    expect((await replace(uuid, { nfc_uid: '04AA0001' })).body.error).toMatch(/already the one on this card/);

    const batch = await post('/api/cards/batch', { count: 1, prefix: 'NC-' });
    const bare = await replace(batch.body.cards[0].card_uuid, { nfc_uid: '04DD0004' });
    expect(bare.status).toBe(409);
    expect(bare.body.error).toMatch(/Pair one instead/);

    await post(`/api/cards/${uuid}/status`, { status: 'retired', reason: 'worn' });
    const retired = await replace(uuid, { nfc_uid: '04BB0002' });
    expect(retired.status).toBe(409);
    expect(retired.body.error).toMatch(/Restore it first/);
  });

  it('warns about an open trip first, then goes ahead when confirmed', async () => {
    const uuid = await activeCard('04A1B2C3');
    await openTrip(uuid);
    const warned = await replace(uuid, { nfc_uid: '04BB0002' });
    expect(warned.status).toBe(409);
    expect(warned.body.open_trips).toBe(1);
    expect((await replace(uuid, { nfc_uid: '04BB0002', confirm: true })).status).toBe(200);
  });

  it('answers 404 for an unknown card, 400 for bad input, and needs an admin', async () => {
    expect((await replace(randomUUID(), { nfc_uid: '04BB0002' })).status).toBe(404);
    expect((await replace('nope', { nfc_uid: '04BB0002' })).status).toBe(400);
    const uuid = await activeCard();
    expect((await replace(uuid, { nfc_uid: 'xyz' })).status).toBe(400);
    expect((await request(app).post(`/api/cards/${uuid}/replace`).send({ nfc_uid: '04BB0002' })).status).toBe(401);
  });
});

describe('searching treats percent, underscore and backslash as plain characters', () => {
  const find = async (q: string) =>
    (await request(app).get('/api/cards').query({ search: q }).set(auth())).body.cards.map((c: { serial: string }) => c.serial);

  it('does not let a % or _ act as a wildcard', async () => {
    await post('/api/cards', { nfc_uid: '04000001', serial: 'AB-1', label: 'plain' });
    await post('/api/cards', { nfc_uid: '04000002', serial: 'AB-2', label: '50% off' });
    await post('/api/cards', { nfc_uid: '04000003', serial: 'AB-3', label: 'under_score' });
    expect(await find('%')).toEqual(['AB-2']);
    expect(await find('_')).toEqual(['AB-3']);
    expect(await find('50%')).toEqual(['AB-2']);
  });

  it('matches a backslash literally instead of treating it as an escape', async () => {
    await post('/api/cards', { nfc_uid: '04000004', serial: 'BS-1', label: 'plain' });
    await post('/api/cards', { nfc_uid: '04000005', serial: 'BS-2', label: 'back\\slash' });
    expect(await find('\\')).toEqual(['BS-2']);
    expect(await find('back\\slash')).toEqual(['BS-2']);
  });
});

/** Several paired, active cards: BK-1 .. BK-n. */
async function cards(n: number, prefix = 'BK-') {
  const ids: string[] = [];
  for (let i = 1; i <= n; i++) {
    const r = await post('/api/cards', { nfc_uid: `04${prefix.charCodeAt(0).toString(16).padStart(2, '0')}${String(i).padStart(4, '0')}`, serial: `${prefix}${i}`, label: `Card ${i}` });
    expect(r.status).toBe(201);
    ids.push(r.body.card_uuid);
  }
  return ids;
}
const statusOf = async (uuid: string) => (await pool.query('SELECT status FROM cards WHERE card_uuid = $1', [uuid])).rows[0].status as string;

describe('bulk changes', () => {
  const bulk = (body: object) => post('/api/cards/bulk', body);

  it('changes the status of many cards at once and records each in its history', async () => {
    const ids = await cards(3);
    const res = await bulk({ card_uuids: ids, action: 'status', status: 'suspended' });
    expect(res.status).toBe(200);
    expect(res.body.changed).toBe(3);
    for (const id of ids) {
      expect(await statusOf(id)).toBe('suspended');
      expect((await history(id))[0]).toMatchObject({ action: 'status_changed', to_status: 'suspended', admin_username: 'boss' });
    }
  });

  it('moves many cards to a vehicle, and back to the spare pool with null', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111')");
    const ids = await cards(2);
    expect((await bulk({ card_uuids: ids, action: 'vehicle', vehicle_id: 'BUS-1' })).body.changed).toBe(2);
    const rows = await pool.query('SELECT vehicle_id FROM cards WHERE card_uuid = ANY($1)', [ids]);
    expect(rows.rows.every((r) => r.vehicle_id === 'BUS-1')).toBe(true);
    expect((await history(ids[0]))[0]).toMatchObject({ action: 'vehicle_changed' });
    await bulk({ card_uuids: ids, action: 'vehicle', vehicle_id: null });
    expect((await pool.query('SELECT vehicle_id FROM cards WHERE card_uuid = $1', [ids[0]])).rows[0].vehicle_id).toBeNull();
  });

  it('is all or nothing: one card that cannot move leaves every card untouched', async () => {
    const ids = await cards(3);
    await post(`/api/cards/${ids[1]}/status`, { status: 'lost', reason: 'misplaced' }); // lost cannot become suspended
    const before = (await history(ids[0])).length;

    const res = await bulk({ card_uuids: ids, action: 'status', status: 'suspended' });
    expect(res.status).toBe(409);
    expect(res.body.error).toBe('1 of 3 cards could not be changed. Nothing was changed.');
    expect(res.body.problems).toEqual([{ card_uuid: ids[1], serial: 'BK-2', error: 'A lost card cannot be changed to suspended.' }]);
    expect(await statusOf(ids[0])).toBe('active');
    expect(await statusOf(ids[2])).toBe('active');
    expect((await history(ids[0])).length).toBe(before);
  });

  it('lists every problem card, not only the first', async () => {
    const ids = await cards(3);
    await post(`/api/cards/${ids[0]}/status`, { status: 'lost', reason: 'x' });
    await post(`/api/cards/${ids[2]}/status`, { status: 'lost', reason: 'x' });
    const res = await bulk({ card_uuids: ids, action: 'status', status: 'suspended' });
    expect(res.body.problems.map((p: { serial: string }) => p.serial)).toEqual(['BK-1', 'BK-3']);
  });

  it('requires a reason for lost and retired, and applies it to every card', async () => {
    const ids = await cards(2);
    const no = await bulk({ card_uuids: ids, action: 'status', status: 'lost' });
    expect(no.status).toBe(409);
    expect(no.body.problems).toHaveLength(2);
    const ok = await bulk({ card_uuids: ids, action: 'status', status: 'lost', reason: 'Box lost in a flood' });
    expect(ok.status).toBe(200);
    expect((await history(ids[0]))[0].reason).toBe('Box lost in a flood');
    expect((await history(ids[1]))[0].reason).toBe('Box lost in a flood');
  });

  it('warns about open trips across the whole group, then goes ahead when confirmed', async () => {
    const ids = await cards(2);
    await openTrip(ids[0], '04420001', 1);
    const warned = await bulk({ card_uuids: ids, action: 'status', status: 'suspended' });
    expect(warned.status).toBe(409);
    expect(warned.body.open_trips).toBe(1);
    expect(await statusOf(ids[1])).toBe('active');
    expect((await bulk({ card_uuids: ids, action: 'status', status: 'suspended', confirm: true })).status).toBe(200);
  });

  it('works on everything matching a filter', async () => {
    await cards(2, 'AA-');
    await cards(2, 'BB-');
    const res = await bulk({ filter: { search: 'BB-' }, action: 'status', status: 'suspended' });
    expect(res.body.changed).toBe(2);
    const rows = await pool.query("SELECT serial, status FROM cards ORDER BY serial");
    expect(rows.rows.map((r) => `${r.serial}:${r.status}`)).toEqual(['AA-1:active', 'AA-2:active', 'BB-1:suspended', 'BB-2:suspended']);
  });

  it('refuses a filter that matches more than the limit, and one that matches nothing', async () => {
    await post('/api/cards/batch', { count: 500, prefix: 'M-' });
    await post('/api/cards/batch', { count: 3, prefix: 'N-' });
    const big = await bulk({ filter: {}, action: 'status', status: 'retired', reason: 'x' });
    expect(big.status).toBe(400);
    expect(big.body.error).toMatch(/more than 500 cards/);
    expect((await bulk({ filter: { search: 'zzz-no-such' }, action: 'status', status: 'suspended' })).status).toBe(404);
  });

  it('ignores duplicate ids and unknown ids', async () => {
    const [id] = await cards(1);
    const res = await bulk({ card_uuids: [id, id, randomUUID()], action: 'status', status: 'suspended' });
    expect(res.status).toBe(200);
    expect(res.body.changed).toBe(1);
  });

  it('rejects a malformed request with 400 and the reason', async () => {
    const [id] = await cards(1);
    expect((await bulk({ action: 'status', status: 'suspended' })).status).toBe(400);
    expect((await bulk({ card_uuids: [id], filter: {}, action: 'status', status: 'suspended' })).status).toBe(400);
    expect((await bulk({ card_uuids: [id], action: 'status' })).status).toBe(400);
    expect((await bulk({ card_uuids: [id], action: 'vehicle' })).status).toBe(400);
    expect((await bulk({ card_uuids: ['nope'], action: 'status', status: 'suspended' })).status).toBe(400);
    expect((await bulk({ card_uuids: [], action: 'status', status: 'suspended' })).status).toBe(400);
    expect((await request(app).post('/api/cards/bulk').send({})).status).toBe(401);
  });

  it('refuses an unknown vehicle and changes nothing', async () => {
    const ids = await cards(2);
    const res = await bulk({ card_uuids: ids, action: 'vehicle', vehicle_id: 'NO-SUCH-BUS' });
    expect(res.status).toBe(400);
    expect(res.body.error).toMatch(/does not exist/);
  });

  it('does nothing and writes nothing for cards already in the asked state', async () => {
    const ids = await cards(2);
    const res = await bulk({ card_uuids: ids, action: 'status', status: 'active' });
    expect(res.status).toBe(200);
    expect((await history(ids[0])).map((e) => e.action)).toEqual(['enrolled']);
  });
});

describe('looking up cards for a reprint', () => {
  const lookup = (body: object) => post('/api/cards/lookup', body);

  it('returns the chosen cards in serial order', async () => {
    const ids = await cards(3);
    const res = await lookup({ card_uuids: [ids[2], ids[0]] });
    expect(res.body.cards.map((c: { serial: string }) => c.serial)).toEqual(['BK-1', 'BK-3']);
    expect(res.body.count).toBe(2);
  });

  it('returns what matches a filter, including cards still waiting for a chip', async () => {
    await post('/api/cards/batch', { count: 3, prefix: 'LK-' });
    const res = await lookup({ filter: { search: 'LK-', status: 'unpaired' } });
    expect(res.body.cards.map((c: { serial: string }) => c.serial)).toEqual(['LK-0001', 'LK-0002', 'LK-0003']);
    expect(Object.keys(res.body.cards[0]).sort()).toEqual(['card_uuid', 'label', 'nfc_uid', 'serial', 'status', 'vehicle_id']);
  });

  it('is empty, not an error, when nothing matches, and needs an admin', async () => {
    expect((await lookup({ filter: { search: 'zzz-none' } })).body).toEqual({ cards: [], count: 0 });
    expect((await lookup({})).status).toBe(400);
    expect((await request(app).post('/api/cards/lookup').send({ filter: {} })).status).toBe(401);
  });
});

describe('exporting the card list', () => {
  const csv = (qs = '') => request(app).get(`/api/cards/export${qs}`).set(auth());
  const rowsOf = (text: string) => text.trim().split('\r\n');

  it('downloads a CSV with a header and one row per card', async () => {
    await cards(2);
    const res = await csv();
    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toMatch(/text\/csv/);
    expect(res.headers['content-disposition']).toBe('attachment; filename="toms_cards.csv"');
    const lines = rowsOf(res.text);
    expect(lines[0]).toBe('serial,status,nfc_uid,vehicle,plate,label,notes,card_uuid,enrolled_at,updated_at,last_used_at');
    expect(lines).toHaveLength(3);
    expect(lines[1]).toMatch(/^"BK-1","active","04/);
  });

  it('uses the same filters as the registry list', async () => {
    await cards(2, 'AA-');
    await cards(2, 'BB-');
    const res = await csv('?search=BB-');
    expect(rowsOf(res.text).slice(1).map((l) => l.split(',')[0])).toEqual(['"BB-1"', '"BB-2"']);
    expect(rowsOf((await csv('?status=lost')).text)).toHaveLength(1);
  });

  it('shows when a card was last used', async () => {
    const [id] = await cards(1);
    await openTrip(id, '04420001', 1);
    const row = rowsOf((await csv()).text)[1].split(',');
    expect(row[10]).toMatch(/^"\d{4}-\d{2}-\d{2}T/);
  });

  it('is not fooled by a label that starts like a spreadsheet formula', async () => {
    await post('/api/cards', { nfc_uid: '04EE0001', serial: 'CSV-1', label: '=HYPERLINK("http://evil")', notes: 'a, "quoted" note' });
    const text = (await csv('?search=CSV-1')).text;
    expect(text).toContain(`"'=HYPERLINK(""http://evil"")"`);
    expect(text).toContain('"a, ""quoted"" note"');
  });

  it('says whether the export was cut short, and needs an admin', async () => {
    expect((await csv()).headers['x-export-truncated']).toBe('false');
    expect((await request(app).get('/api/cards/export')).status).toBe(401);
    expect((await csv('?status=broken')).status).toBe(400);
  });

  it('is not captured by the :card_uuid routes', async () => {
    expect((await csv()).status).toBe(200);
    expect((await request(app).post('/api/cards/bulk').set(auth()).send({})).status).toBe(400);
    expect((await request(app).post('/api/cards/lookup').set(auth()).send({})).status).toBe(400);
  });
});

describe('how a card is used', () => {
  const list = async (q = '') => (await request(app).get(`/api/cards${q}`).set(auth())).body.cards as {
    serial: string; last_used_at: string | null; open_trips: number;
  }[];

  it('says when a card was last used, and null for a card never used', async () => {
    const [used, unused] = await cards(2);
    await openTrip(used, '04420001', 1);
    const rows = await list('?search=BK-');
    const byId = Object.fromEntries(rows.map((r) => [r.serial, r]));
    expect(byId['BK-1'].last_used_at).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(byId['BK-2'].last_used_at).toBeNull();
    expect(unused).toBeDefined();
  });

  it('counts the open trips on each card', async () => {
    const [a, b] = await cards(2);
    await openTrip(a, '04420001', 1);
    const rows = Object.fromEntries((await list('?search=BK-')).map((r) => [r.serial, r.open_trips]));
    expect(rows).toEqual({ 'BK-1': 1, 'BK-2': 0 });
    expect(b).toBeDefined();
  });

  it('keeps the fields the registry already used', async () => {
    await cards(1);
    const [row] = await list();
    expect(Object.keys(row)).toEqual(expect.arrayContaining(['card_uuid', 'nfc_uid', 'serial', 'label', 'status', 'enrolled_at', 'notes', 'vehicle_id', 'vehicle_plate']));
  });
});

describe('a card\u2019s trips', () => {
  const trips = (uuid: string, qs = '') => request(app).get(`/api/cards/${uuid}/trips${qs}`).set(auth());

  it('lists the trips newest first with the fields an admin needs', async () => {
    const [id] = await cards(1);
    await openTrip(id, '04420001', 1);
    await openTrip(id, '04420001', 2);
    const res = await trips(id);
    expect(res.status).toBe(200);
    expect(res.body.count).toBe(2);
    expect(Object.keys(res.body.trips[0]).sort()).toEqual(
      ['actual_destination_stop_id', 'boarding_stop_id', 'current_state', 'declared_destination_stop_id', 'effective_at', 'fare_centavos', 'paid', 'passenger_count', 'trip_id', 'vehicle_id'],
    );
    expect(res.body.trips[0].current_state).toBe('ASSIGNED_UNPAID');
    expect(res.body.trips[0].fare_centavos).toBe(1500);
    expect(res.body.trips[0].passenger_count).toBe(1);
  });

  it('only shows this card\u2019s trips', async () => {
    const [a, b] = await cards(2);
    await openTrip(a, '04420001', 1);
    expect((await trips(b)).body.count).toBe(0);
    expect((await trips(a)).body.count).toBe(1);
  });

  it('respects a limit, and refuses a bad one', async () => {
    const [id] = await cards(1);
    await openTrip(id, '04420001', 1);
    await openTrip(id, '04420001', 2);
    expect((await trips(id, '?limit=1')).body.count).toBe(1);
    expect((await trips(id, '?limit=0')).status).toBe(400);
    expect((await trips(id, '?limit=101')).status).toBe(400);
  });

  it('answers 404 for an unknown card, 400 for a bad id, 401 without an admin', async () => {
    expect((await trips(randomUUID())).status).toBe(404);
    expect((await trips('nope')).status).toBe(400);
    const [id] = await cards(1);
    expect((await request(app).get(`/api/cards/${id}/trips`)).status).toBe(401);
  });
});

describe('telling the phone why a card is refused', () => {
  const approved = (token = deviceToken, qs = '') => request(app).get(`/api/cards/approved${qs}`).set('Authorization', `Bearer ${token}`);

  it('keeps the old card map exactly as it was: only active, paired cards', async () => {
    const [active, suspended, lost, retired] = await cards(4);
    await post(`/api/cards/${suspended}/status`, { status: 'suspended' });
    await post(`/api/cards/${lost}/status`, { status: 'lost', reason: 'x' });
    await post(`/api/cards/${retired}/status`, { status: 'retired', reason: 'x' });
    const res = await approved();
    expect(Object.values(res.body.cards)).toEqual([active]);
    expect(res.body.count).toBe(1);
  });

  it('adds a statuses field for suspended, lost and retired cards', async () => {
    const [, suspended, lost, retired] = await cards(4);
    await post(`/api/cards/${suspended}/status`, { status: 'suspended' });
    await post(`/api/cards/${lost}/status`, { status: 'lost', reason: 'x' });
    await post(`/api/cards/${retired}/status`, { status: 'retired', reason: 'x' });
    const row = async (id: string) => (await pool.query('SELECT nfc_uid FROM cards WHERE card_uuid = $1', [id])).rows[0].nfc_uid as string;
    const { statuses } = (await approved()).body;
    expect(statuses).toEqual({ [await row(suspended)]: 'suspended', [await row(lost)]: 'lost', [await row(retired)]: 'retired' });
  });

  it('leaves out cards with no chip and cards that are simply active', async () => {
    await cards(1);
    await post('/api/cards/batch', { count: 2, prefix: 'WAIT-' });
    expect((await approved()).body.statuses).toEqual({});
  });

  it('is empty, not missing, when every card is fine', async () => {
    const res = await approved();
    expect(res.body.statuses).toEqual({});
    expect(res.body.cards).toEqual({});
  });

  it('follows a card through lost and found', async () => {
    const [id] = await cards(1);
    const nfc = (await pool.query('SELECT nfc_uid FROM cards WHERE card_uuid = $1', [id])).rows[0].nfc_uid as string;
    await post(`/api/cards/${id}/status`, { status: 'lost', reason: 'x' });
    expect((await approved()).body.statuses[nfc]).toBe('lost');
    await post(`/api/cards/${id}/status`, { status: 'active' });
    const back = (await approved()).body;
    expect(back.statuses[nfc]).toBeUndefined();
    expect(back.cards[nfc]).toBe(id);
  });

  it('is scoped to the phone\u2019s vehicle plus the spare pool, like the card map', async () => {
    await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111'), ('BUS-2', 'BBB-222')");
    const mine = (await post('/api/cards', { nfc_uid: '04110001', serial: 'V-1', vehicle_id: 'BUS-1' })).body.card_uuid;
    const other = (await post('/api/cards', { nfc_uid: '04110002', serial: 'V-2', vehicle_id: 'BUS-2' })).body.card_uuid;
    const spare = (await post('/api/cards', { nfc_uid: '04110003', serial: 'V-3' })).body.card_uuid;
    for (const id of [mine, other, spare]) await post(`/api/cards/${id}/status`, { status: 'lost', reason: 'x' });
    await pool.query("UPDATE devices SET vehicle_id = 'BUS-1' WHERE device_id = 'dev-1'");
    const { statuses } = (await approved()).body;
    expect(Object.keys(statuses).sort()).toEqual(['04110001', '04110003']);
  });

  it('still needs authentication', async () => {
    expect((await request(app).get('/api/cards/approved')).status).toBe(401);
  });
});

describe('migration 010', () => {
  it('adds the index that trip lookups use', async () => {
    const { rows } = await pool.query("SELECT 1 FROM pg_indexes WHERE indexname = 'events_card_time_idx'");
    expect(rows).toHaveLength(1);
  });
});
