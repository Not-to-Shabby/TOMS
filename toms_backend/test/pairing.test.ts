import { randomUUID } from 'crypto';
import request from 'supertest';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import {
  CODE_ALPHABET,
  CODE_ID_LENGTH,
  CODE_SECRET_LENGTH,
  CODE_TTL_MINUTES,
  MAX_OPEN_CODES,
  MAX_WRONG_ATTEMPTS,
  enrolPayload,
  formatCode,
  parseCode,
} from '../src/pairing';
import { RateLimiter } from '../src/rateLimit';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let admin: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  // A generous limit: the per-address limit has its own test, and these tests all come from one address.
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET, pairLimiter: new RateLimiter(10_000, 60_000) });
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query(
    'TRUNCATE admins, conductors, devices, vehicles, events, routes, pairing_codes, device_pairing_audit RESTART IDENTITY CASCADE',
  );
  await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('BUS-1', 'AAA-111'), ('BUS-2', 'BBB-222')");
  admin = await adminToken(pool, app);
});
afterEach(() => vi.restoreAllMocks());

const bearer = (t: string) => ({ Authorization: `Bearer ${t}` });

const issue = (body: Record<string, unknown> = { vehicle_id: 'BUS-1' }) =>
  request(app).post('/api/pairing-codes').set(bearer(admin)).send(body);

const pair = (code: string, deviceId: string, target: ReturnType<typeof createApp> = app) =>
  request(target).post('/api/devices/pair').send({ code, device_id: deviceId });

const event = (deviceId: string) => ({
  event_id: randomUUID(),
  device_id: deviceId,
  local_seq: 1,
  type: 'future_thing',
  created_at_millis: Date.UTC(2026, 9, 8),
  payload: {},
});

/** Issues a code and returns it with its parts. */
async function issued(body: Record<string, unknown> = { vehicle_id: 'BUS-1' }) {
  const res = await issue(body);
  expect(res.status).toBe(201);
  const parts = parseCode(res.body.code as string)!;
  return { ...parts, code: res.body.code as string, id: res.body.id as number };
}

/** The same code id with a secret that is certainly not the right one. */
const withWrongSecret = (c: { codeId: string; secret: string }) =>
  formatCode(c.codeId, c.secret === '22222222' ? '33333333' : '22222222');

const row = async (codeId: string) => (await pool.query('SELECT * FROM pairing_codes WHERE code_id = $1', [codeId])).rows[0];

describe('the code', () => {
  it('uses 30 symbols without the look-alikes 0 O 1 I L U, and enough of them', () => {
    expect(CODE_ALPHABET).toHaveLength(30);
    expect(new Set(CODE_ALPHABET).size).toBe(30);
    for (const bad of '01OILU') expect(CODE_ALPHABET).not.toContain(bad);
    expect(CODE_SECRET_LENGTH).toBeGreaterThanOrEqual(8);
    expect(CODE_ID_LENGTH + CODE_SECRET_LENGTH).toBe(12);
  });

  it('is read the way it is typed: any case, with or without dashes or spaces', () => {
    expect(formatCode('ABCD', 'EFGHJKMN')).toBe('ABCD-EFGH-JKMN');
    const want = { codeId: 'ABCD', secret: 'EFGHJKMN' };
    expect(parseCode('ABCD-EFGH-JKMN')).toEqual(want);
    expect(parseCode('abcd efgh jkmn')).toEqual(want);
    expect(parseCode(' abcdefghjkmn ')).toEqual(want);
    expect(parseCode('ABCD-EFGH-JKM')).toBeNull(); // 11 symbols
    expect(parseCode('ABCD-EFGH-JKMNP')).toBeNull(); // 13 symbols
    expect(parseCode('ABCD-EFGH-JKM0')).toBeNull(); // 0 is not in the alphabet
    expect(parseCode('ABCD-EFGH-JKMO')).toBeNull();
    expect(parseCode('')).toBeNull();
  });

  it('is the same alphabet in the code and in the database check: every symbol is accepted or refused alike', async () => {
    const symbols = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
    for (const c of symbols) {
      const attempt = pool.query(
        `INSERT INTO pairing_codes (code_id, secret_hash, vehicle_id, created_by, expires_at)
         VALUES ($1, $2, 'BUS-1', 'test', now() + interval '1 minute')`,
        [`${c}AAA`, 'a'.repeat(64)],
      );
      if (CODE_ALPHABET.includes(c)) await expect(attempt).resolves.toBeDefined();
      else await expect(attempt).rejects.toThrow(/pairing_codes_code_id_check/);
    }
  });

  it('puts the server address and the code in the QR text, with the address made safe for a URL', () => {
    expect(enrolPayload('https://toms.example', 'ABCD-EFGH-JKMN')).toBe(
      'toms-enrol://v1?server=https%3A%2F%2Ftoms.example&code=ABCD-EFGH-JKMN',
    );
  });
});

describe('issuing a code', () => {
  const adminRoutes: [string, string][] = [
    ['post', '/api/pairing-codes'],
    ['get', '/api/pairing-codes'],
    ['delete', '/api/pairing-codes/1'],
    ['get', '/api/pairing-audit'],
    ['get', '/api/devices'],
  ];

  it.each(adminRoutes)('%s %s needs an admin: nobody, a conductor and a phone are all refused', async (method, path) => {
    const call = (h?: Record<string, string>) => {
      const r = (request(app) as unknown as Record<string, (p: string) => request.Test>)[method](path);
      return (h ? r.set(h) : r).send({ vehicle_id: 'BUS-1' });
    };
    expect((await call()).status).toBe(401);

    await request(app).post('/api/conductors').set(bearer(admin)).send({ username: 'cond1', password: 'conductor-pass-1', name: 'C' });
    const login = await request(app).post('/api/conductors/login').send({ username: 'cond1', password: 'conductor-pass-1' });
    expect((await call(bearer(login.body.token))).status).toBe(403);

    const phone = await enrollDevice(pool, 'dev-x', 'BUS-1');
    expect((await call(bearer(phone))).status).toBe(401);
  });

  it('returns the code once, expiring in ten minutes, and stores only a keyed hash of its secret', async () => {
    const res = await issue({ vehicle_id: 'BUS-1' });
    expect(res.status).toBe(201);
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body.code).toMatch(/^[2-9A-HJKMNP-TV-Z]{4}-[2-9A-HJKMNP-TV-Z]{4}-[2-9A-HJKMNP-TV-Z]{4}$/);
    expect(res.body.vehicle_id).toBe('BUS-1');
    expect(res.body.expires_in_seconds).toBe(CODE_TTL_MINUTES * 60);
    expect(res.body.created_by).toBe('boss');

    const { codeId, secret } = parseCode(res.body.code)!;
    const stored = await row(codeId);
    expect(stored.secret_hash).toMatch(/^[0-9a-f]{64}$/);
    // Nothing of the secret, or the whole code, is in the stored row.
    const dump = JSON.stringify(stored).toUpperCase();
    expect(dump).not.toContain(secret);
    expect(dump).not.toContain(`${codeId}${secret}`);
    const minutes = (await pool.query('SELECT extract(epoch FROM expires_at - created_at) / 60 AS m FROM pairing_codes WHERE code_id = $1', [codeId])).rows[0].m;
    expect(Number(minutes)).toBe(CODE_TTL_MINUTES);
  });

  it('draws different codes each time', async () => {
    const seen = new Set<string>();
    for (let i = 0; i < 6; i++) seen.add((await issued()).code);
    expect(seen.size).toBe(6);
  });

  it("takes the server address for the QR from the server's own setting, never from the request", async () => {
    const before = process.env.PUBLIC_BASE_URL;
    try {
      process.env.PUBLIC_BASE_URL = 'https://toms.example/';
      const res = await request(app)
        .post('/api/pairing-codes')
        .set(bearer(admin))
        .set('Host', 'evil.example')
        .set('X-Forwarded-Host', 'evil.example')
        .send({ vehicle_id: 'BUS-1' });
      expect(res.status).toBe(201);
      expect(res.body.qr_payload).toBe(`toms-enrol://v1?server=https%3A%2F%2Ftoms.example&code=${res.body.code}`);
      expect(JSON.stringify(res.body)).not.toContain('evil');

      delete process.env.PUBLIC_BASE_URL;
      const none = await issue();
      expect(none.body.qr_payload).toBeNull();
    } finally {
      if (before === undefined) delete process.env.PUBLIC_BASE_URL;
      else process.env.PUBLIC_BASE_URL = before;
    }
  });

  it('refuses a vehicle that does not exist, and a request with no vehicle', async () => {
    const unknown = await issue({ vehicle_id: 'NOPE' });
    expect(unknown.status).toBe(400);
    expect(unknown.body.error).toBe('Unknown vehicle');
    expect((await issue({})).status).toBe(400);
    expect((await issue({ vehicle_id: 'BUS-1', label: '' })).status).toBe(400);
    expect((await pool.query('SELECT count(*)::int AS n FROM pairing_codes')).rows[0].n).toBe(0);
  });

  it('caps the codes that can be used at once, and frees a place when one is cancelled or expires', async () => {
    const made: { id: number }[] = [];
    for (let i = 0; i < MAX_OPEN_CODES; i++) made.push(await issued());
    const over = await issue();
    expect(over.status).toBe(409);
    expect(over.body.error).toMatch(/already 10 open pairing codes/);

    expect((await request(app).delete(`/api/pairing-codes/${made[0].id}`).set(bearer(admin))).status).toBe(200);
    expect((await issue()).status).toBe(201);
    expect((await issue()).status).toBe(409);

    await pool.query("UPDATE pairing_codes SET expires_at = now() - interval '1 minute' WHERE id = $1", [made[1].id]);
    expect((await issue()).status).toBe(201);
  });

  it('keeps the cap exact when administrators issue at the same moment', async () => {
    // Two things make this a real test of the lock (a first version passed even with the lock removed).
    // 1. Five codes are already open. The pool runs at most 10 requests at once, so with an empty table ten
    //    unlocked issuers would still stop at exactly the cap by luck; with five open they would reach 15.
    // 2. A trigger makes every insert wait 50 ms, after the count was taken. Unlocked issuers therefore all
    //    count the same number before any of them commits, instead of the race being real but rarely lost.
    const alreadyOpen = 5;
    for (let i = 0; i < alreadyOpen; i++) {
      await pool.query(
        `INSERT INTO pairing_codes (code_id, secret_hash, vehicle_id, created_by, expires_at)
         VALUES ($1, $2, 'BUS-1', 'test', now() + interval '10 minutes')`,
        [`${'ABCDE'[i]}222`, 'a'.repeat(64)],
      );
    }
    await pool.query(
      'CREATE FUNCTION pairing_test_slow() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(0.05); RETURN NEW; END $$',
    );
    await pool.query('CREATE TRIGGER pairing_test_slow BEFORE INSERT ON pairing_codes FOR EACH ROW EXECUTE FUNCTION pairing_test_slow()');
    try {
      const results = await Promise.all(Array.from({ length: 15 }, () => issue()));
      expect(results.filter((r) => r.status === 201)).toHaveLength(MAX_OPEN_CODES - alreadyOpen);
      expect(results.filter((r) => r.status === 409)).toHaveLength(15 - (MAX_OPEN_CODES - alreadyOpen));
      expect((await pool.query('SELECT count(*)::int AS n FROM pairing_codes')).rows[0].n).toBe(MAX_OPEN_CODES);
    } finally {
      await pool.query('DROP TRIGGER pairing_test_slow ON pairing_codes');
      await pool.query('DROP FUNCTION pairing_test_slow()');
    }
  });
});

describe('the list of codes', () => {
  it('shows each code by its public id and status, and never a secret, a hash or the code itself', async () => {
    const open = await issued();
    const cancelled = await issued();
    const used = await issued();
    const locked = await issued();
    const expired = await issued();

    await request(app).delete(`/api/pairing-codes/${cancelled.id}`).set(bearer(admin));
    expect((await pair(used.code, 'phone-used')).status).toBe(201);
    for (let i = 0; i < MAX_WRONG_ATTEMPTS; i++) await pair(withWrongSecret(locked), 'phone-guess');
    await pool.query("UPDATE pairing_codes SET expires_at = now() - interval '1 minute' WHERE id = $1", [expired.id]);

    const res = await request(app).get('/api/pairing-codes').set(bearer(admin));
    expect(res.status).toBe(200);
    const status = Object.fromEntries(res.body.map((r: { code_id: string; status: string }) => [r.code_id, r.status]));
    expect(status).toEqual({
      [open.codeId]: 'open',
      [cancelled.codeId]: 'cancelled',
      [used.codeId]: 'used',
      [locked.codeId]: 'locked',
      [expired.codeId]: 'expired',
    });
    expect(res.body.find((r: { code_id: string }) => r.code_id === used.codeId).used_device_id).toBe('phone-used');

    const text = JSON.stringify(res.body).toUpperCase();
    expect(text).not.toContain('SECRET');
    for (const c of [open, cancelled, used, locked, expired]) {
      expect(text).not.toContain(c.secret);
    }
    for (const r of res.body) expect(Object.keys(r)).not.toContain('code');
  });
});

describe('pairing a phone', () => {
  it('trades a code for a working token, and the vehicle is the one the code was issued for', async () => {
    const c = await issued({ vehicle_id: 'BUS-2', label: 'Bus 2 phone' });
    const res = await pair(c.code, 'phone-1');
    expect(res.status).toBe(201);
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.body.device_id).toBe('phone-1');
    expect(res.body.vehicle_id).toBe('BUS-2');
    expect(res.body.token.startsWith('phone-1.')).toBe(true);

    const device = (await pool.query("SELECT * FROM devices WHERE device_id = 'phone-1'")).rows[0];
    expect(device.vehicle_id).toBe('BUS-2');
    expect(device.label).toBe('Bus 2 phone');
    expect(device.token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(res.body.token).not.toContain(device.token_hash);
    expect(device.revoked_at).toBeNull();

    const used = await request(app).post('/api/events').set(bearer(res.body.token)).send({ events: [event('phone-1')] });
    expect(used.status).toBe(200);
    expect(used.body.accepted).toHaveLength(1);

    const stored = await row(c.codeId);
    expect(stored.used_device_id).toBe('phone-1');
    expect(stored.used_at).not.toBeNull();
  });

  it('accepts the code typed in lower case, with spaces instead of dashes', async () => {
    const c = await issued();
    const res = await pair(c.code.toLowerCase().replace(/-/g, ' '), 'phone-typed');
    expect(res.status).toBe(201);
  });

  it('does not let the phone choose a vehicle: a request that names one is refused and nothing is used up', async () => {
    const c = await issued({ vehicle_id: 'BUS-1' });
    const res = await request(app).post('/api/devices/pair').send({ code: c.code, device_id: 'phone-2', vehicle_id: 'BUS-2' });
    expect(res.status).toBe(400);
    expect((await pool.query('SELECT count(*)::int AS n FROM devices')).rows[0].n).toBe(0);
    expect((await row(c.codeId)).used_at).toBeNull();
    // The code is still good, and it still gives the vehicle it was issued for.
    const ok = await pair(c.code, 'phone-2');
    expect(ok.status).toBe(201);
    expect(ok.body.vehicle_id).toBe('BUS-1');
  });

  it('works once: a second phone with the same code gets the plain refusal', async () => {
    const c = await issued();
    expect((await pair(c.code, 'phone-a')).status).toBe(201);
    const again = await pair(c.code, 'phone-b');
    expect(again.status).toBe(403);
    expect((await pool.query('SELECT count(*)::int AS n FROM devices')).rows[0].n).toBe(1);
  });

  it('lets exactly one of several phones win when they use one code at the same moment', async () => {
    const c = await issued();
    const results = await Promise.all(Array.from({ length: 8 }, (_, i) => pair(c.code, `phone-race-${i}`)));
    expect(results.filter((r) => r.status === 201)).toHaveLength(1);
    expect(results.filter((r) => r.status === 403)).toHaveLength(7);
    expect((await pool.query('SELECT count(*)::int AS n FROM devices')).rows[0].n).toBe(1);
  });

  it('answers a wrong, unknown, used, cancelled, locked or expired code in exactly the same way', async () => {
    const open = await issued();
    const used = await issued();
    const cancelled = await issued();
    const locked = await issued();
    const expired = await issued();

    expect((await pair(used.code, 'phone-first')).status).toBe(201);
    await request(app).delete(`/api/pairing-codes/${cancelled.id}`).set(bearer(admin));
    for (let i = 0; i < MAX_WRONG_ATTEMPTS; i++) await pair(withWrongSecret(locked), 'phone-guess');
    await pool.query("UPDATE pairing_codes SET expires_at = now() - interval '1 minute' WHERE id = $1", [expired.id]);

    let freeId = '2222';
    while ((await row(freeId)) !== undefined) freeId = `${freeId.slice(1)}3`;

    const attempts = [
      pair(withWrongSecret(open), 'phone-x'), // right id, wrong secret
      pair(formatCode(freeId, 'ABCDEFGH'), 'phone-x'), // no such code
      pair(used.code, 'phone-x'),
      pair(cancelled.code, 'phone-x'),
      pair(locked.code, 'phone-x'), // the right secret, but the code is locked
      pair(expired.code, 'phone-x'),
    ];
    const answers = (await Promise.all(attempts)).map((r) => `${r.status} ${JSON.stringify(r.body)}`);
    expect(new Set(answers).size).toBe(1);
    expect(answers[0].startsWith('403 ')).toBe(true);
    expect(answers[0]).toContain('did not work');
  });

  it('does not lock on a few typos: the right code still works after one fewer wrong tries than the limit', async () => {
    const c = await issued();
    for (let i = 0; i < MAX_WRONG_ATTEMPTS - 1; i++) expect((await pair(withWrongSecret(c), 'phone-typo')).status).toBe(403);
    expect((await row(c.codeId)).attempts).toBe(MAX_WRONG_ATTEMPTS - 1);
    expect((await pair(c.code, 'phone-typo')).status).toBe(201);
  });

  it('locks after the limit of wrong tries and stays locked, even for the right secret', async () => {
    const c = await issued();
    for (let i = 1; i <= MAX_WRONG_ATTEMPTS; i++) {
      expect((await pair(withWrongSecret(c), 'phone-guess')).status).toBe(403);
      const r = await row(c.codeId);
      expect(r.attempts).toBe(i);
      expect(r.locked_at === null).toBe(i < MAX_WRONG_ATTEMPTS);
    }
    expect((await pair(c.code, 'phone-guess')).status).toBe(403);
    expect((await pool.query('SELECT count(*)::int AS n FROM devices')).rows[0].n).toBe(0);
    // Nothing more is counted once it is locked.
    expect((await row(c.codeId)).attempts).toBe(MAX_WRONG_ATTEMPTS);

    const audit = (await request(app).get('/api/pairing-audit').set(bearer(admin))).body;
    const locks = audit.filter((a: { event_type: string }) => a.event_type === 'pairing_code_locked');
    expect(locks).toHaveLength(1);
    expect(locks[0]).toMatchObject({ code_id: c.codeId, vehicle_id: 'BUS-1', actor: 'system' });
  });

  it('cannot be beaten by guessing in parallel: the count stops at the limit and the right code is then refused', async () => {
    const c = await issued();
    const guesses = await Promise.all(Array.from({ length: 30 }, () => pair(withWrongSecret(c), 'phone-guess')));
    expect(guesses.every((r) => r.status === 403)).toBe(true);
    const r = await row(c.codeId);
    expect(r.attempts).toBe(MAX_WRONG_ATTEMPTS);
    expect(r.locked_at).not.toBeNull();
    expect((await pair(c.code, 'phone-guess')).status).toBe(403);
  });

  it('limits attempts per address, answers 429 with Retry-After, and does not weigh a guess made while limited', async () => {
    const limited = createApp({ pool, jwtSecret: TEST_JWT_SECRET, pairLimiter: new RateLimiter(3, 60_000) });
    const c = await issued();
    for (let i = 0; i < 3; i++) expect((await pair(withWrongSecret(c), 'phone-guess', limited)).status).toBe(403);

    const blocked = await pair(withWrongSecret(c), 'phone-guess', limited);
    expect(blocked.status).toBe(429);
    expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
    // The right code is held back too while the address is limited, and no extra guess was counted.
    expect((await pair(c.code, 'phone-guess', limited)).status).toBe(429);
    expect((await row(c.codeId)).attempts).toBe(3);
    // From another address (the unlimited app) the code is still good.
    expect((await pair(c.code, 'phone-guess')).status).toBe(201);
  });

  it("refuses a malformed request, and leaves the code's try count and use untouched", async () => {
    const c = await issued();
    expect((await pair(c.code, 'x')).status).toBe(400); // a Device ID has at least 3 characters
    expect((await pair(c.code, 'has space')).status).toBe(400);
    expect((await pair('not a code', 'phone-1')).status).toBe(400);
    expect((await pair('ABCD-EFGH-JKM0', 'phone-1')).status).toBe(400); // 0 is not in the alphabet
    expect((await request(app).post('/api/devices/pair').send({})).status).toBe(400);
    expect((await request(app).post('/api/devices/pair').send({ code: 5, device_id: 'phone-1' })).status).toBe(400);
    const bad = await request(app).post('/api/devices/pair').set('Content-Type', 'application/json').send('{not json');
    expect(bad.status).toBe(400);
    expect((await row(c.codeId)).attempts).toBe(0);
    expect((await pair(c.code, 'phone-1')).status).toBe(201);
  });

  it('refuses a code whose vehicle was deleted in the meantime', async () => {
    const c = await issued({ vehicle_id: 'BUS-2' });
    await pool.query("DELETE FROM vehicles WHERE id = 'BUS-2'");
    expect((await pair(c.code, 'phone-1')).status).toBe(403);
    expect((await pool.query('SELECT count(*)::int AS n FROM devices')).rows[0].n).toBe(0);
  });
});

describe('Device IDs that are taken', () => {
  it('cannot take over a phone the administrator enrolled, and the code is not used up', async () => {
    const first = await request(app).post('/api/devices').set(bearer(admin)).send({ device_id: 'phone-5', vehicle_id: 'BUS-1' });
    expect(first.status).toBe(201);
    const c = await issued();

    const res = await pair(c.code, 'phone-5');
    expect(res.status).toBe(409);
    expect(res.body.error).toMatch(/already enrolled/);
    expect((await row(c.codeId)).used_at).toBeNull();
    // The old token still works, and the code still enrols a different phone.
    const kept = await request(app).post('/api/events').set(bearer(first.body.token)).send({ events: [event('phone-5')] });
    expect(kept.body.accepted).toHaveLength(1);
    expect((await pair(c.code, 'phone-6')).status).toBe(201);
  });

  it('keeps a revoked ID revoked: it cannot be paired again, and its old token stays dead', async () => {
    const a = await issued();
    const paired = await pair(a.code, 'phone-9');
    expect(paired.status).toBe(201);
    const hashBefore = (await pool.query("SELECT token_hash FROM devices WHERE device_id = 'phone-9'")).rows[0].token_hash;

    expect((await request(app).delete('/api/devices/phone-9').set(bearer(admin))).status).toBe(200);
    const dead = await request(app).post('/api/events').set(bearer(paired.body.token)).send({ events: [event('phone-9')] });
    expect(dead.status).toBe(401);

    const b = await issued();
    const again = await pair(b.code, 'phone-9');
    expect(again.status).toBe(409);
    expect(again.body).not.toHaveProperty('token');
    // Nothing was changed on the revoked row, and the second code is still unused.
    const after = (await pool.query("SELECT token_hash, revoked_at FROM devices WHERE device_id = 'phone-9'")).rows[0];
    expect(after.token_hash).toBe(hashBefore);
    expect(after.revoked_at).not.toBeNull();
    expect((await row(b.codeId)).used_at).toBeNull();
    expect((await request(app).post('/api/events').set(bearer(paired.body.token)).send({ events: [event('phone-9')] })).status).toBe(401);

    // A phone with a new ID can use the second code as normal.
    expect((await pair(b.code, 'phone-10')).status).toBe(201);
  });
});

describe('cancelling a code', () => {
  it('stops it working, can only be done once, and says who did it', async () => {
    const c = await issued();
    expect((await request(app).delete(`/api/pairing-codes/${c.id}`).set(bearer(admin))).status).toBe(200);
    expect((await request(app).delete(`/api/pairing-codes/${c.id}`).set(bearer(admin))).status).toBe(404);
    expect((await request(app).delete('/api/pairing-codes/999999').set(bearer(admin))).status).toBe(404);
    expect((await request(app).delete('/api/pairing-codes/abc').set(bearer(admin))).status).toBe(404);
    expect((await pair(c.code, 'phone-1')).status).toBe(403);

    const audit = (await request(app).get('/api/pairing-audit').set(bearer(admin))).body;
    expect(audit.find((a: { event_type: string }) => a.event_type === 'pairing_code_cancelled')).toMatchObject({
      code_id: c.codeId,
      actor: 'admin:boss',
    });
  });

  it('cannot cancel a code that was already used', async () => {
    const c = await issued();
    expect((await pair(c.code, 'phone-1')).status).toBe(201);
    expect((await request(app).delete(`/api/pairing-codes/${c.id}`).set(bearer(admin))).status).toBe(404);
  });
});

describe('the list of phones', () => {
  it('shows each phone with its vehicle, last seen and revoked flag, and never a credential', async () => {
    const c = await issued({ vehicle_id: 'BUS-1', label: 'Front phone' });
    const paired = await pair(c.code, 'phone-1');
    await request(app).post('/api/events').set(bearer(paired.body.token)).send({ events: [event('phone-1')] });
    await enrollDevice(pool, 'phone-2', 'BUS-2');
    await request(app).delete('/api/devices/phone-2').set(bearer(admin));

    const res = await request(app).get('/api/devices').set(bearer(admin));
    expect(res.status).toBe(200);
    const byId = Object.fromEntries(res.body.map((d: { device_id: string }) => [d.device_id, d]));
    expect(byId['phone-1']).toMatchObject({ vehicle_id: 'BUS-1', label: 'Front phone', revoked: false });
    expect(byId['phone-1'].last_seen).not.toBeNull();
    expect(byId['phone-2']).toMatchObject({ vehicle_id: 'BUS-2', revoked: true });
    expect(byId['phone-2'].last_seen).toBeNull();

    const text = JSON.stringify(res.body);
    for (const d of res.body) {
      expect(Object.keys(d)).not.toContain('token');
      expect(Object.keys(d)).not.toContain('token_hash');
    }
    expect(text).not.toContain(paired.body.token.split('.')[1]);
    const stored = (await pool.query('SELECT token_hash FROM devices')).rows.map((r) => r.token_hash as string);
    for (const h of stored) expect(text).not.toContain(h);
  });
});

describe('secrets stay out of logs, lists and the audit trail', () => {
  it('never writes a code secret, a stored hash or a token anywhere but the one response that issues it', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => undefined));

    const good = await issued({ vehicle_id: 'BUS-1', label: 'Front phone' });
    const wrong = await issued({ vehicle_id: 'BUS-2' });
    const cancelled = await issued();
    await pair(withWrongSecret(wrong), 'phone-guess');
    for (let i = 0; i < MAX_WRONG_ATTEMPTS; i++) await pair(withWrongSecret(wrong), 'phone-guess');
    const paired = await pair(good.code, 'phone-1');
    expect(paired.status).toBe(201);
    await pair(good.code, 'phone-2');
    await request(app).delete(`/api/pairing-codes/${cancelled.id}`).set(bearer(admin));

    const lists = [
      (await request(app).get('/api/pairing-codes').set(bearer(admin))).body,
      (await request(app).get('/api/pairing-audit').set(bearer(admin))).body,
      (await request(app).get('/api/devices').set(bearer(admin))).body,
    ];
    const audit = (await pool.query('SELECT * FROM device_pairing_audit ORDER BY id')).rows;
    const logged = spies.flatMap((s) => s.mock.calls);

    const haystack = JSON.stringify({ lists, audit, logged }).toUpperCase();
    const stored = (await pool.query('SELECT secret_hash FROM pairing_codes')).rows.map((r) => r.secret_hash as string);
    const tokenHashes = (await pool.query('SELECT token_hash FROM devices')).rows.map((r) => r.token_hash as string);
    const needles = [
      // Every way a code can be written down: the secret alone, the id and secret run together, and the dashed
      // form people read and the server returns. (A first version left out the dashed form, and a mutation that
      // logged the whole code when it was issued passed unnoticed.)
      ...[good, wrong, cancelled].flatMap((c) => [c.secret, `${c.codeId}${c.secret}`, c.code]),
      // A wrong guess comes from a stranger, but it can be one typo away from a real secret, so it stays out too.
      '22222222',
      '33333333',
      paired.body.token.split('.')[1],
      ...stored,
      ...tokenHashes,
    ];
    expect(needles.length).toBeGreaterThan(8);
    for (const n of needles) expect(haystack).not.toContain(String(n).toUpperCase());

    // The audit trail says who and what, in order, and holds only the public id of a code.
    expect(audit.map((a) => `${a.event_type} ${a.actor}`)).toEqual([
      'pairing_code_issued admin:boss',
      'pairing_code_issued admin:boss',
      'pairing_code_issued admin:boss',
      'pairing_code_locked system',
      'device_paired device:phone-1',
      'pairing_code_cancelled admin:boss',
    ]);
    expect(audit.find((a) => a.event_type === 'device_paired')).toMatchObject({
      code_id: good.codeId,
      vehicle_id: 'BUS-1',
      device_id: 'phone-1',
    });
  });
});
