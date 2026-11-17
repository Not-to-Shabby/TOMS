import crypto from 'crypto';
import request from 'supertest';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { GRACE_WINDOW_MS } from '../src/returnMachines';
import { KeyVault, parseMasterKey, vaultFromEnv } from '../src/keyVault';
import { RateLimiter } from '../src/rateLimit';
import { adminToken, createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let admin: string;
let vault: KeyVault;
let masterHex: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  // The master key is generated at test time: no key is written in any file.
  masterHex = crypto.randomBytes(32).toString('hex');
  vault = new KeyVault(masterHex);
  // A roomy limiter here: these tests fetch the key many times per phone. The limit itself has its own test below.
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET, keyVault: vault, rmKeyFetchLimiter: new RateLimiter(10_000, 60_000) });
  admin = await adminToken(pool, app);
});
afterAll(async () => drop());

beforeEach(async () => {
  await pool.query('TRUNCATE return_machine_audit, return_machines, devices, vehicles RESTART IDENTITY CASCADE');
  await pool.query("INSERT INTO vehicles (id, plate_number) VALUES ('v1', 'AAA 111'), ('v2', 'BBB 222')");
});
afterEach(() => vi.restoreAllMocks());

const asAdmin = (r: request.Test) => r.set('Authorization', `Bearer ${admin}`);
const asDevice = (r: request.Test, token: string) => r.set('Authorization', `Bearer ${token}`);

async function register(machineId = 'TOMS-RM-3C48', vehicleId = 'v1') {
  return asAdmin(request(app).post('/api/return-machines')).send({ machine_id: machineId, vehicle_id: vehicleId });
}

async function fetchKey(token: string) {
  return asDevice(request(app).get('/api/device/return-machine-key'), token);
}

describe('registering and listing Return Machines', () => {
  it('registers a machine for a vehicle and never puts a key in the response', async () => {
    const res = await register();
    expect(res.status).toBe(201);
    expect(res.body.machine_id).toBe('TOMS-RM-3C48');
    expect(res.body.vehicle_id).toBe('v1');
    expect(res.body.key_version).toBe(1);
    // No key field, in any spelling, and nothing that looks like 32 bytes of key material.
    expect(JSON.stringify(res.body)).not.toMatch(/key_enc|"key"|previous_key/);
    expect(JSON.stringify(res.body)).not.toMatch(/[A-Za-z0-9+/]{43}=/);
  });

  it('is admin-only: no token, a conductor and a phone are all refused', async () => {
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    expect((await request(app).post('/api/return-machines').send({ machine_id: 'TOMS-RM-0001', vehicle_id: 'v1' })).status).toBe(401);
    expect((await asDevice(request(app).post('/api/return-machines'), phone).send({ machine_id: 'TOMS-RM-0001', vehicle_id: 'v1' })).status).toBe(401);
    expect((await request(app).get('/api/return-machines')).status).toBe(401);
    expect((await request(app).post('/api/return-machines/TOMS-RM-0001/rotate')).status).toBe(401);
  });

  it('refuses a duplicate machine id, a second machine on one vehicle, an unknown vehicle and a bad id', async () => {
    expect((await register('TOMS-RM-3C48', 'v1')).status).toBe(201);
    expect((await register('TOMS-RM-3C48', 'v2')).status).toBe(409);
    expect((await register('TOMS-RM-0002', 'v1')).status).toBe(409);
    expect((await register('TOMS-RM-0003', 'ghost')).status).toBe(400);
    expect((await register('x', 'v2')).status).toBe(400);
    expect((await register('has space', 'v2')).status).toBe(400);
  });

  it('lists machines with their key bookkeeping but no key', async () => {
    await register();
    const res = await asAdmin(request(app).get('/api/return-machines'));
    expect(res.status).toBe(200);
    expect(res.body).toHaveLength(1);
    expect(Object.keys(res.body[0]).sort()).toEqual([
      'created_at', 'id', 'in_grace', 'key_created_at', 'key_version', 'last_issued_at', 'last_issued_device',
      'last_issued_version', 'machine_id', 'mismatch_reason', 'mismatch_since', 'rotated_at', 'vehicle_id',
    ]);
  });
});

describe('storage at rest', () => {
  it('stores the key only encrypted: the database holds no plaintext key, and the blob does not open under another master key', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const issued = (await fetchKey(phone)).body.key as string;
    const plain = Buffer.from(issued, 'base64');
    expect(plain).toHaveLength(32);

    const row = (await pool.query('SELECT id, key_enc FROM return_machines')).rows[0];
    const blob = row.key_enc as Buffer;
    expect(blob.length).toBe(12 + 32 + 16);
    expect(blob.includes(plain)).toBe(false);

    // Opens only with the right master key and the right record id.
    expect(vault.open(blob, String(row.id))!.equals(plain)).toBe(true);
    expect(new KeyVault(crypto.randomBytes(32).toString('hex')).open(blob, String(row.id))).toBeNull();
    expect(vault.open(blob, String(Number(row.id) + 1))).toBeNull();
    const tampered = Buffer.from(blob);
    tampered[20] ^= 1;
    expect(vault.open(tampered, String(row.id))).toBeNull();
  });

  it('the master key must be 64 hex characters and not a placeholder', () => {
    expect(() => parseMasterKey('short')).toThrow(/64 hex/);
    expect(() => parseMasterKey('0'.repeat(64))).toThrow();
    expect(() => parseMasterKey('f'.repeat(64))).toThrow();
    expect(() => parseMasterKey(undefined)).toThrow();
    expect(parseMasterKey(masterHex)).toHaveLength(32);
    expect(vaultFromEnv({} as NodeJS.ProcessEnv)).toBeUndefined();
    expect(() => vaultFromEnv({ RM_KEY_MASTER_KEY: 'not hex' } as NodeJS.ProcessEnv)).toThrow();
  });

  it('without a master key the routes answer 503 and store nothing', async () => {
    const bare = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
    const noVaultAdmin = await adminToken(pool, bare, 'boss2');
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const created = await request(bare).post('/api/return-machines').set('Authorization', `Bearer ${noVaultAdmin}`)
      .send({ machine_id: 'TOMS-RM-3C48', vehicle_id: 'v1' });
    expect(created.status).toBe(503);
    expect((await pool.query('SELECT 1 FROM return_machines')).rowCount).toBe(0);
    expect((await request(bare).get('/api/device/return-machine-key').set('Authorization', `Bearer ${phone}`)).status).toBe(503);
  });
});

describe('the phone fetches the key for its own vehicle only', () => {
  it('returns a key for the enrolled vehicle, and the same key again on a second fetch (a replaced phone gets it back)', async () => {
    await register('TOMS-RM-3C48', 'v1');
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const first = await fetchKey(phone);
    expect(first.status).toBe(200);
    expect(first.headers['cache-control']).toBe('no-store');
    expect(first.body.machine_id).toBe('TOMS-RM-3C48');
    expect(first.body.key_version).toBe(1);
    expect(Buffer.from(first.body.key, 'base64')).toHaveLength(32);
    expect(first.body.previous_key).toBeNull();

    const replacement = await enrollDevice(pool, 'dev-2', 'v1');
    const second = await fetchKey(replacement);
    expect(second.body.key).toBe(first.body.key);
  });

  it("refuses another vehicle's phone: it is never given this machine's key", async () => {
    await register('TOMS-RM-3C48', 'v1');
    const otherVehiclePhone = await enrollDevice(pool, 'dev-other', 'v2');
    const res = await fetchKey(otherVehiclePhone);
    expect(res.status).toBe(404);
    expect(JSON.stringify(res.body)).not.toMatch(/"key"/);
    // And the machine id cannot be asked for by name: the route has no such parameter.
    const byName = await asDevice(request(app).get('/api/device/return-machine-key?machine_id=TOMS-RM-3C48&vehicle_id=v1'), otherVehiclePhone);
    expect(byName.status).toBe(404);
    expect(JSON.stringify(byName.body)).not.toMatch(/"key"/);
  });

  it('refuses a phone with no vehicle, an unknown token, a revoked phone, and a conductor or admin token', async () => {
    await register('TOMS-RM-3C48', 'v1');
    const noVehicle = await enrollDevice(pool, 'dev-free', null);
    expect((await fetchKey(noVehicle)).status).toBe(404);
    expect((await fetchKey('dev-1.nottherealsecret')).status).toBe(401);
    expect((await request(app).get('/api/device/return-machine-key')).status).toBe(401);
    expect((await fetchKey(admin)).status).toBe(401);

    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    await pool.query("UPDATE devices SET revoked_at = now() WHERE device_id = 'dev-1'");
    expect((await fetchKey(phone)).status).toBe(401);
  });

  it('404s when the vehicle has no machine registered', async () => {
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    expect((await fetchKey(phone)).status).toBe(404);
  });

  it('rate-limits key fetches per phone, with Retry-After, and other phones are unaffected', async () => {
    const limited = createApp({
      pool, jwtSecret: TEST_JWT_SECRET, keyVault: vault, rmKeyFetchLimiter: new RateLimiter(3, 60_000),
    });
    const adm = await adminToken(pool, limited, 'boss3');
    await request(limited).post('/api/return-machines').set('Authorization', `Bearer ${adm}`).send({ machine_id: 'TOMS-RM-3C48', vehicle_id: 'v1' });
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const phone2 = await enrollDevice(pool, 'dev-2', 'v1');

    for (let i = 0; i < 3; i++) {
      expect((await request(limited).get('/api/device/return-machine-key').set('Authorization', `Bearer ${phone}`)).status).toBe(200);
    }
    const blocked = await request(limited).get('/api/device/return-machine-key').set('Authorization', `Bearer ${phone}`);
    expect(blocked.status).toBe(429);
    expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
    expect(JSON.stringify(blocked.body)).not.toMatch(/"key"/);
    expect((await request(limited).get('/api/device/return-machine-key').set('Authorization', `Bearer ${phone2}`)).status).toBe(200);
  });
});

describe('rotation', () => {
  it('rotating issues a new key, keeps the old one for one grace window, and bumps the version', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const before = (await fetchKey(phone)).body;

    const rotated = await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    expect(rotated.status).toBe(200);
    expect(rotated.body.key_version).toBe(2);
    expect(rotated.body.in_grace).toBe(true);
    expect(JSON.stringify(rotated.body)).not.toMatch(/"key"|previous_key/);

    const after = (await fetchKey(phone)).body;
    expect(after.key_version).toBe(2);
    expect(after.key).not.toBe(before.key);
    expect(after.previous_key).toBe(before.key);
    expect(new Date(after.previous_valid_until).getTime()).toBeGreaterThan(Date.now());
    expect(new Date(after.previous_valid_until).getTime()).toBeLessThanOrEqual(Date.now() + GRACE_WINDOW_MS + 5_000);
  });

  it('the old key is no longer issued once the grace window has passed', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    await pool.query("UPDATE return_machines SET previous_valid_until = now() - interval '1 second'");

    const after = (await fetchKey(phone)).body;
    expect(after.previous_key).toBeNull();
    expect(after.previous_valid_until).toBeNull();
    const list = await asAdmin(request(app).get('/api/return-machines'));
    expect(list.body[0].in_grace).toBe(false);
  });

  it('a second rotation replaces the grace key with the one just retired, so at most one old key is held', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const v1 = (await fetchKey(phone)).body.key;
    await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    const v2 = (await fetchKey(phone)).body;
    await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    const v3 = (await fetchKey(phone)).body;

    expect(v3.key_version).toBe(3);
    expect(v3.previous_key).toBe(v2.key);
    expect(v3.previous_key).not.toBe(v1);
  });

  it('rotation of an unknown machine is a 404 and a rotation clears a mismatch flag', async () => {
    expect((await asAdmin(request(app).post('/api/return-machines/NOPE-0001/rotate'))).status).toBe(404);
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    await asDevice(request(app).post('/api/device/return-machine-key/mismatch'), phone).send({ reason: 'machine_has_other_key' });
    expect((await asAdmin(request(app).get('/api/return-machines'))).body[0].mismatch_reason).toBe('machine_has_other_key');
    await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    expect((await asAdmin(request(app).get('/api/return-machines'))).body[0].mismatch_reason).toBeNull();
  });
});

describe('key mismatch', () => {
  it('a phone can flag a machine that does not accept the key; it shows on the dashboard until a signed message verifies', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const flagged = await asDevice(request(app).post('/api/device/return-machine-key/mismatch'), phone).send({ reason: 'machine_has_other_key' });
    expect(flagged.status).toBe(200);

    let row = (await asAdmin(request(app).get('/api/return-machines'))).body[0];
    expect(row.mismatch_reason).toBe('machine_has_other_key');
    expect(row.mismatch_since).not.toBeNull();
    const since = row.mismatch_since;

    // A repeat keeps the first-seen time.
    await asDevice(request(app).post('/api/device/return-machine-key/mismatch'), phone).send({ reason: 'machine_has_other_key' });
    row = (await asAdmin(request(app).get('/api/return-machines'))).body[0];
    expect(row.mismatch_since).toBe(since);

    // A verified message under an OLD version does not clear it...
    await asDevice(request(app).post('/api/device/return-machine-key/verified'), phone).send({ key_version: 99 });
    expect((await asAdmin(request(app).get('/api/return-machines'))).body[0].mismatch_reason).not.toBeNull();
    // ...under the current version it does.
    await asDevice(request(app).post('/api/device/return-machine-key/verified'), phone).send({ key_version: 1 });
    expect((await asAdmin(request(app).get('/api/return-machines'))).body[0].mismatch_reason).toBeNull();
  });

  it("another vehicle's phone cannot flag or clear this machine's mismatch", async () => {
    await register('TOMS-RM-3C48', 'v1');
    const stranger = await enrollDevice(pool, 'dev-x', 'v2');
    expect((await asDevice(request(app).post('/api/device/return-machine-key/mismatch'), stranger).send({ reason: 'machine_has_other_key' })).status).toBe(404);
    expect((await asDevice(request(app).post('/api/device/return-machine-key/verified'), stranger).send({ key_version: 1 })).status).toBe(404);
    expect((await asAdmin(request(app).get('/api/return-machines'))).body[0].mismatch_reason).toBeNull();
  });

  it('rejects an unknown mismatch reason', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    expect((await asDevice(request(app).post('/api/device/return-machine-key/mismatch'), phone).send({ reason: 'because' })).status).toBe(400);
    expect((await asDevice(request(app).post('/api/device/return-machine-key/verified'), phone).send({ key_version: 'one' })).status).toBe(400);
  });
});

describe('audit and logs never carry a key', () => {
  it('writes rm_key_created, rm_key_issued, rm_key_rotated, rm_key_mismatch and rm_key_verified without key material', async () => {
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const key = (await fetchKey(phone)).body.key as string;
    await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    const key2 = (await fetchKey(phone)).body.key as string;
    await asDevice(request(app).post('/api/device/return-machine-key/mismatch'), phone).send({ reason: 'rotation_needs_reflash' });
    await asDevice(request(app).post('/api/device/return-machine-key/verified'), phone).send({ key_version: 2 });

    const rows = (await pool.query('SELECT * FROM return_machine_audit ORDER BY id')).rows;
    expect(rows.map((r) => r.event_type)).toEqual([
      'rm_key_created', 'rm_key_issued', 'rm_key_rotated', 'rm_key_issued', 'rm_key_mismatch', 'rm_key_verified',
    ]);
    expect(rows[0].actor).toBe('admin:boss');
    expect(rows[1].actor).toBe('device:dev-1');
    expect(rows[1].key_version).toBe(1);
    expect(rows[3].key_version).toBe(2);

    // The audit table holds no key: neither version, in base64 or hex, appears in any column.
    const dump = JSON.stringify(rows);
    for (const k of [key, key2]) {
      expect(dump).not.toContain(k);
      expect(dump).not.toContain(Buffer.from(k, 'base64').toString('hex'));
    }

    // The admin audit endpoint returns the same rows, still without a key.
    const viaApi = await asAdmin(request(app).get('/api/return-machines/TOMS-RM-3C48/audit'));
    expect(viaApi.body).toHaveLength(6);
    expect(JSON.stringify(viaApi.body)).not.toContain(key);
  });

  it('never reaches the phone event log, the audit_events view, or console output', async () => {
    const spies = [vi.spyOn(console, 'log'), vi.spyOn(console, 'error'), vi.spyOn(console, 'warn'), vi.spyOn(console, 'info')];
    await register();
    const phone = await enrollDevice(pool, 'dev-1', 'v1');
    const key = (await fetchKey(phone)).body.key as string;
    await asAdmin(request(app).post('/api/return-machines/TOMS-RM-3C48/rotate'));
    await fetchKey(phone);

    expect((await pool.query("SELECT 1 FROM events WHERE event_type LIKE 'rm\\_key%'")).rowCount).toBe(0);
    expect((await pool.query('SELECT 1 FROM audit_events WHERE payload::text LIKE $1', [`%${key}%`])).rowCount).toBe(0);
    for (const spy of spies) {
      expect(JSON.stringify(spy.mock.calls)).not.toContain(key);
    }
  });
});
