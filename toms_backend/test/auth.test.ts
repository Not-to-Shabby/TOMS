import { randomUUID } from 'crypto';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import argon2 from 'argon2';
import { createAdmin } from '../src/admins';
import { createApp } from '../src/app';
import { assertJwtSecret, LoginLimiter } from '../src/auth';
import { ADMIN_PASSWORD, adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET, corsOrigins: ['https://dash.example'] });
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE admins, conductors, devices, vehicles, events, routes RESTART IDENTITY CASCADE');
});

const event = (deviceId: string) => ({
  event_id: randomUUID(),
  device_id: deviceId,
  local_seq: 1,
  type: 'future_thing',
  created_at_millis: Date.UTC(2026, 9, 8),
  payload: {},
});

describe('startup secret check', () => {
  it('refuses a missing, short or placeholder JWT secret', () => {
    expect(() => assertJwtSecret(undefined)).toThrow(/JWT_SECRET/);
    expect(() => assertJwtSecret('short')).toThrow(/at least 32/);
    expect(() => assertJwtSecret('CHANGE_ME'.padEnd(40, 'x'))).toThrow(/placeholder/);
    expect(assertJwtSecret(TEST_JWT_SECRET)).toBe(TEST_JWT_SECRET);
  });

  it('refuses to build the app with a weak secret', () => {
    expect(() => createApp({ pool, jwtSecret: 'weak' })).toThrow(/JWT_SECRET/);
  });
});

describe('admin accounts', () => {
  it('start with no seeded or default account', async () => {
    expect((await pool.query('SELECT count(*) FROM admins')).rows[0].count).toBe(0);
    expect((await pool.query('SELECT count(*) FROM conductors')).rows[0].count).toBe(0);
    const res = await request(app).post('/api/admin/login').send({ username: 'test1', password: 'password123' });
    expect(res.status).toBe(401);
  });

  it('store an argon2id hash, never the password', async () => {
    await createAdmin(pool, 'boss', ADMIN_PASSWORD);
    const { password_hash } = (await pool.query('SELECT password_hash FROM admins')).rows[0];
    expect(password_hash.startsWith('$argon2id$')).toBe(true);
    expect(password_hash).not.toContain(ADMIN_PASSWORD);
    expect(await argon2.verify(password_hash, ADMIN_PASSWORD)).toBe(true);
  });

  it('reject weak passwords, odd usernames and duplicates', async () => {
    await expect(createAdmin(pool, 'boss', 'short')).rejects.toThrow(/12 to 200/);
    await expect(createAdmin(pool, 'a b', ADMIN_PASSWORD)).rejects.toThrow(/Username/);
    await createAdmin(pool, 'boss', ADMIN_PASSWORD);
    await expect(createAdmin(pool, 'boss', ADMIN_PASSWORD)).rejects.toThrow(/already exists/);
  });
});

describe('admin login', () => {
  beforeEach(async () => {
    await createAdmin(pool, 'boss', ADMIN_PASSWORD);
  });

  it('returns a token with the admin role for the right password', async () => {
    const res = await request(app).post('/api/admin/login').send({ username: 'boss', password: ADMIN_PASSWORD });
    expect(res.status).toBe(200);
    const claims = jwt.decode(res.body.token) as jwt.JwtPayload;
    expect(claims.role).toBe('admin');
    expect(claims.exp! - claims.iat!).toBe(8 * 3600);
    expect(res.body.user).toMatchObject({ username: 'boss', role: 'admin' });
    expect(JSON.stringify(res.body)).not.toContain('argon2');
  });

  it('gives the same answer for a wrong password and an unknown user', async () => {
    const wrong = await request(app).post('/api/admin/login').send({ username: 'boss', password: 'wrong-wrong-wrong' });
    const none = await request(app).post('/api/admin/login').send({ username: 'nobody', password: 'wrong-wrong-wrong' });
    expect(wrong.status).toBe(401);
    expect(none.status).toBe(401);
    expect(wrong.body).toEqual(none.body);
  });

  it('rejects malformed login bodies', async () => {
    expect((await request(app).post('/api/admin/login').send({})).status).toBe(400);
    expect((await request(app).post('/api/admin/login').send({ username: 'boss' })).status).toBe(400);
    expect((await request(app).post('/api/admin/login').send({ username: 'boss', password: 12345 })).status).toBe(400);
  });

  it('does not let a conductor password log in as an admin or the reverse', async () => {
    const token = await adminToken(pool, app, 'boss2');
    await request(app).post('/api/conductors').set('Authorization', `Bearer ${token}`)
      .send({ username: 'cond1', password: 'conductor-pass-1', name: 'Cond One' });
    const asAdmin = await request(app).post('/api/admin/login').send({ username: 'cond1', password: 'conductor-pass-1' });
    expect(asAdmin.status).toBe(401);
    const asConductor = await request(app).post('/api/conductors/login').send({ username: 'boss', password: ADMIN_PASSWORD });
    expect(asConductor.status).toBe(401);
  });

  it('blocks a username after repeated failures, even with the right password', async () => {
    const tight = createApp({ pool, jwtSecret: TEST_JWT_SECRET, loginLimiter: new LoginLimiter(3, 60_000) });
    for (let i = 0; i < 3; i++) {
      expect((await request(tight).post('/api/admin/login').send({ username: 'boss', password: `bad-password-${i}` })).status).toBe(401);
    }
    const blocked = await request(tight).post('/api/admin/login').send({ username: 'boss', password: ADMIN_PASSWORD });
    expect(blocked.status).toBe(429);
    expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
    const other = await request(tight).post('/api/admin/login').send({ username: 'someone-else', password: 'x' });
    expect(other.status).toBe(401); // a different name is not locked out
  });

  it('forgets earlier failures after a successful login', async () => {
    const tight = createApp({ pool, jwtSecret: TEST_JWT_SECRET, loginLimiter: new LoginLimiter(3, 60_000) });
    for (let i = 0; i < 2; i++) await request(tight).post('/api/admin/login').send({ username: 'boss', password: 'nope-nope-nope' });
    expect((await request(tight).post('/api/admin/login').send({ username: 'boss', password: ADMIN_PASSWORD })).status).toBe(200);
    for (let i = 0; i < 2; i++) {
      expect((await request(tight).post('/api/admin/login').send({ username: 'boss', password: 'nope-nope-nope' })).status).toBe(401);
    }
  });
});

describe('login limiter', () => {
  it('expires the block after the window', () => {
    let t = 1_000;
    const l = new LoginLimiter(2, 10_000, () => t);
    l.fail('k'); l.fail('k');
    expect(l.blockedFor('k')).toBeGreaterThan(0);
    t += 10_001;
    expect(l.blockedFor('k')).toBe(0);
  });
});

describe('protected routes', () => {
  const admin = ['get /api/routes', 'get /api/vehicles', 'post /api/vehicles', 'post /api/conductors', 'post /api/routes',
    'delete /api/vehicles/x', 'post /api/devices', 'put /api/vehicles/x'];

  it.each(admin)('%s needs a token', async (line) => {
    const [method, path] = line.split(' ');
    const res = await (request(app) as unknown as Record<string, (p: string) => request.Test>)[method](path).send({});
    expect(res.status).toBe(401);
  });

  it('accepts a valid admin token', async () => {
    const token = await adminToken(pool, app);
    expect((await request(app).get('/api/routes').set('Authorization', `Bearer ${token}`)).status).toBe(200);
  });

  it('rejects a conductor token on admin routes with 403', async () => {
    const t = await adminToken(pool, app);
    await request(app).post('/api/conductors').set('Authorization', `Bearer ${t}`)
      .send({ username: 'cond1', password: 'conductor-pass-1', name: 'C' });
    const login = await request(app).post('/api/conductors/login').send({ username: 'cond1', password: 'conductor-pass-1' });
    expect(login.status).toBe(200);
    const res = await request(app).get('/api/vehicles').set('Authorization', `Bearer ${login.body.token}`);
    expect(res.status).toBe(403);
  });

  it('rejects a tampered, wrong-secret, expired, none-algorithm or wrong-audience token', async () => {
    const t = await adminToken(pool, app);
    const id = (jwt.decode(t) as jwt.JwtPayload).sub!;
    const base = { role: 'admin', username: 'boss' };
    const opts = { subject: id, issuer: 'toms-backend', audience: 'toms-clients' } as const;
    const bad = [
      t.slice(0, -3) + (t.endsWith('aaa') ? 'bbb' : 'aaa'),
      jwt.sign(base, 'a-different-secret-a-different-secret-xx', { ...opts, expiresIn: '1h' }),
      jwt.sign(base, TEST_JWT_SECRET, { ...opts, expiresIn: -10 }),
      jwt.sign(base, TEST_JWT_SECRET, { ...opts, audience: 'someone-else', expiresIn: '1h' }),
      jwt.sign(base, TEST_JWT_SECRET, { ...opts, issuer: 'someone-else', expiresIn: '1h' }),
      `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(JSON.stringify({ ...base, sub: id })).toString('base64url')}.`,
      'not.a.jwt',
    ];
    for (const token of bad) {
      expect((await request(app).get('/api/routes').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    }
  });

  it('rejects a token signed with the right secret but a different algorithm', async () => {
    const t = await adminToken(pool, app);
    const id = (jwt.decode(t) as jwt.JwtPayload).sub!;
    for (const algorithm of ['HS384', 'HS512'] as const) {
      const token = jwt.sign({ role: 'admin', username: 'boss' }, TEST_JWT_SECRET, {
        algorithm, subject: id, issuer: 'toms-backend', audience: 'toms-clients', expiresIn: '1h',
      });
      expect((await request(app).get('/api/routes').set('Authorization', `Bearer ${token}`)).status).toBe(401);
    }
  });

  it('rejects a token that claims a role the account does not have', async () => {
    await createAdmin(pool, 'boss', ADMIN_PASSWORD);
    const adminId = (await pool.query('SELECT id FROM admins')).rows[0].id;
    // A conductor-signed token reusing an admin's numeric id must not pass as that admin.
    const forged = jwt.sign({ role: 'conductor', username: 'boss' }, TEST_JWT_SECRET, {
      subject: String(adminId), issuer: 'toms-backend', audience: 'toms-clients', expiresIn: '1h',
    });
    const res = await request(app).get('/api/routes').set('Authorization', `Bearer ${forged}`);
    expect(res.status).toBe(401); // no conductor with that id
  });

  it('stops honouring a token once its account is deleted', async () => {
    const token = await adminToken(pool, app);
    expect((await request(app).get('/api/routes').set('Authorization', `Bearer ${token}`)).status).toBe(200);
    await pool.query('DELETE FROM admins');
    expect((await request(app).get('/api/routes').set('Authorization', `Bearer ${token}`)).status).toBe(401);
  });

  it('ignores a malformed Authorization header', async () => {
    for (const h of ['', 'Bearer', 'Basic abc', 'Bearer a b', 'bearer x']) {
      expect((await request(app).get('/api/routes').set('Authorization', h)).status).toBe(401);
    }
  });

  it('keeps /health public and does not advertise the framework', async () => {
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.headers['x-powered-by']).toBeUndefined();
  });
});

describe('device credentials', () => {
  it('lets an enrolled device send events and nothing else', async () => {
    const token = await enrollDevice(pool, 'dev-1');
    const ok = await request(app).post('/api/events').set('Authorization', `Bearer ${token}`).send({ events: [event('dev-1')] });
    expect(ok.status).toBe(200);
    expect(ok.body.accepted).toHaveLength(1);
    expect((await request(app).get('/api/vehicles').set('Authorization', `Bearer ${token}`)).status).toBe(401);
  });

  it('rejects no credential, a wrong secret, an unknown device and a revoked device', async () => {
    const token = await enrollDevice(pool, 'dev-1');
    const send = (auth?: string) => {
      const r = request(app).post('/api/events');
      return (auth ? r.set('Authorization', auth) : r).send({ events: [event('dev-1')] });
    };
    expect((await send()).status).toBe(401);
    expect((await send(`Bearer ${token}x`)).status).toBe(401);
    expect((await send('Bearer dev-1.wrongsecret')).status).toBe(401);
    expect((await send('Bearer ghost.' + token.split('.')[1])).status).toBe(401);
    expect((await send('Bearer dev-1')).status).toBe(401);
    await pool.query("UPDATE devices SET revoked_at = now() WHERE device_id = 'dev-1'");
    expect((await send(`Bearer ${token}`)).status).toBe(401);
  });

  it('rejects an admin token on the event endpoint', async () => {
    const t = await adminToken(pool, app);
    expect((await request(app).post('/api/events').set('Authorization', `Bearer ${t}`).send({ events: [event('dev-1')] })).status).toBe(401);
  });

  it('refuses events that name a different device, and stores none of the batch', async () => {
    const token = await enrollDevice(pool, 'dev-1');
    await enrollDevice(pool, 'dev-2');
    const res = await request(app).post('/api/events').set('Authorization', `Bearer ${token}`)
      .send({ events: [event('dev-1'), event('dev-2')] });
    expect(res.status).toBe(403);
    expect((await pool.query('SELECT count(*) FROM events')).rows[0].count).toBe(0);
  });

  it('is enrolled through the admin API; the credential is shown once and only its hash is stored', async () => {
    const t = await adminToken(pool, app);
    const res = await request(app).post('/api/devices').set('Authorization', `Bearer ${t}`).send({ device_id: 'phone-7' });
    expect(res.status).toBe(201);
    expect(res.body.token.startsWith('phone-7.')).toBe(true);
    const stored = (await pool.query("SELECT token_hash FROM devices WHERE device_id = 'phone-7'")).rows[0].token_hash;
    expect(stored).toMatch(/^[0-9a-f]{64}$/);
    expect(res.body.token).not.toContain(stored);
    const used = await request(app).post('/api/events').set('Authorization', `Bearer ${res.body.token}`).send({ events: [event('phone-7')] });
    expect(used.body.accepted).toHaveLength(1);
    expect((await request(app).post('/api/devices').set('Authorization', `Bearer ${t}`).send({ device_id: 'phone-7' })).status).toBe(409);
    expect((await request(app).post('/api/devices').set('Authorization', `Bearer ${t}`).send({ device_id: 'x' })).status).toBe(400);
    expect((await request(app).post('/api/devices').set('Authorization', `Bearer ${t}`).send({ device_id: 'phone-8', vehicle_id: 'NOPE' })).status).toBe(400);
  });

  it('can be revoked by an admin, after which events are refused', async () => {
    const t = await adminToken(pool, app);
    const enrolled = await request(app).post('/api/devices').set('Authorization', `Bearer ${t}`).send({ device_id: 'phone-9' });
    const revoke = await request(app).delete('/api/devices/phone-9').set('Authorization', `Bearer ${t}`);
    expect(revoke.status).toBe(200);
    expect((await request(app).delete('/api/devices/phone-9').set('Authorization', `Bearer ${t}`)).status).toBe(404);
    const res = await request(app).post('/api/events').set('Authorization', `Bearer ${enrolled.body.token}`).send({ events: [event('phone-9')] });
    expect(res.status).toBe(401);
  });
});

describe('CORS', () => {
  it('allows only configured origins', async () => {
    const ok = await request(app).get('/health').set('Origin', 'https://dash.example');
    expect(ok.headers['access-control-allow-origin']).toBe('https://dash.example');
    const bad = await request(app).get('/health').set('Origin', 'https://evil.example');
    expect(bad.headers['access-control-allow-origin']).toBeUndefined();
  });

  it('allows no browser origin at all when none are configured', async () => {
    const closed = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
    const res = await request(closed).get('/health').set('Origin', 'https://dash.example');
    expect(res.headers['access-control-allow-origin']).toBeUndefined();
  });

  it('never answers with a wildcard origin', async () => {
    const res = await request(app).options('/api/events').set('Origin', 'https://evil.example').set('Access-Control-Request-Method', 'POST');
    expect(res.headers['access-control-allow-origin']).not.toBe('*');
  });
});

describe('conductor management', () => {
  it('stores conductor passwords as argon2id and enforces a minimum length', async () => {
    const t = await adminToken(pool, app);
    const short = await request(app).post('/api/conductors').set('Authorization', `Bearer ${t}`).send({ username: 'abc', password: 'short', name: 'X' });
    expect(short.status).toBe(400);
    const ok = await request(app).post('/api/conductors').set('Authorization', `Bearer ${t}`).send({ username: 'abc', password: 'long-enough-pass', name: 'X' });
    expect(ok.status).toBe(201);
    expect(JSON.stringify(ok.body)).not.toMatch(/password|argon2/);
    expect((await pool.query("SELECT password_hash FROM conductors WHERE username = 'abc'")).rows[0].password_hash).toMatch(/^\$argon2id\$/);
  });

  it('lets a conductor sign in and gives a conductor-role token', async () => {
    const t = await adminToken(pool, app);
    await request(app).post('/api/conductors').set('Authorization', `Bearer ${t}`).send({ username: 'abc', password: 'long-enough-pass', name: 'Ana' });
    const res = await request(app).post('/api/conductors/login').send({ username: 'abc', password: 'long-enough-pass' });
    expect(res.status).toBe(200);
    expect(res.body.user).toMatchObject({ username: 'abc', name: 'Ana', role: 'conductor' });
    expect((jwt.decode(res.body.token) as jwt.JwtPayload).exp! - (jwt.decode(res.body.token) as jwt.JwtPayload).iat!).toBe(16 * 3600);
  });
});
