import { randomUUID } from 'crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { createTestDb, enrollDevice, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;
let app: ReturnType<typeof createApp>;
let deviceToken: string;
let admin: string;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
  app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
  const { createAdmin } = await import('../src/admins');
  await createAdmin(pool, 'boss', 'correct horse battery');
  const login = await request(app).post('/api/admin/login').send({ username: 'boss', password: 'correct horse battery' });
  admin = login.body.token;
});
afterAll(async () => drop());
beforeEach(async () => {
  await pool.query('TRUNCATE events, devices, vehicles RESTART IDENTITY CASCADE');
  deviceToken = await enrollDevice(pool, 'dev-1');
});

const MACHINE = 'AA:BB:CC:DD:EE:FF';

function rmEvent(type: string, localSeq: number, payload: Record<string, unknown>) {
  return {
    event_id: randomUUID(),
    device_id: 'dev-1',
    local_seq: localSeq,
    type,
    created_at_millis: Date.UTC(2026, 10, 16, 1, 0, 0) + localSeq,
    payload,
  };
}

async function upload(events: unknown[]) {
  const res = await request(app).post('/api/events').set('Authorization', `Bearer ${deviceToken}`).send({ events });
  if (res.body.rejected.length) throw new Error('rejected: ' + JSON.stringify(res.body.rejected));
  return res.body;
}

async function rmHealthRows(): Promise<Record<string, unknown>[]> {
  const { rows } = await pool.query('SELECT * FROM rm_health ORDER BY device_id');
  return rows;
}

describe('Return Machine health events', () => {
  it('accepts the three rm_* events and the rm_health view counts them per device', async () => {
    const body = await upload([
      rmEvent('rm_stored_read', 1, { machineId: MACHINE, readSeq: 41, buffered: 10, atMillis: Date.UTC(2026, 10, 16, 0, 30) }),
      rmEvent('rm_stored_read', 2, { machineId: MACHINE, readSeq: 42, buffered: 10, atMillis: Date.UTC(2026, 10, 16, 0, 31) }),
      rmEvent('rm_unverified', 3, { machineId: MACHINE, checkSeq: 7, delayMs: 1500, reason: 'timeout' }),
      rmEvent('rm_lost_gap', 4, { machineId: MACHINE, lastSeq: 40, readSeq: 41, gap: 12 }),
    ]);
    expect(body.accepted).toHaveLength(4);

    const rows = await rmHealthRows();
    expect(rows).toHaveLength(1);
    expect(rows[0].device_id).toBe('dev-1');
    expect(rows[0].stored_reads).toBe(2);
    expect(rows[0].unverified_reads).toBe(1);
    expect(rows[0].lost_gaps).toBe(1);
    expect(rows[0].last_rm_event).not.toBeNull();
  });

  it('rejects unknown fields, a bad reason, a zero gap and a missing machine id', async () => {
    const res = await request(app)
      .post('/api/events')
      .set('Authorization', `Bearer ${deviceToken}`)
      .send({
        events: [
          rmEvent('rm_stored_read', 1, { machineId: MACHINE, readSeq: 1, buffered: 1, atMillis: 0, uid: '6FF1AD39' }),
          rmEvent('rm_unverified', 2, { machineId: MACHINE, checkSeq: 1, delayMs: 10, reason: 'maybe' }),
          rmEvent('rm_lost_gap', 3, { machineId: MACHINE, lastSeq: 1, readSeq: 2, gap: 0 }),
          rmEvent('rm_unverified', 4, { checkSeq: 1, delayMs: 10, reason: 'timeout' }),
        ],
      });
    expect(res.body.accepted).toHaveLength(0);
    expect(res.body.rejected).toHaveLength(4);
    expect(await rmHealthRows()).toHaveLength(0);
  });

  it('keeps rm_* events out of the audit log, the CSV and the delivery counts, like device_status', async () => {
    await upload([
      rmEvent('rm_stored_read', 1, { machineId: MACHINE, readSeq: 41, buffered: 3, atMillis: 1 }),
      rmEvent('rm_unverified', 2, { machineId: MACHINE, checkSeq: 7, delayMs: 1500, reason: 'disconnected' }),
    ]);

    const audit = await request(app).get('/api/audit/logs').set('Authorization', `Bearer ${admin}`);
    expect(audit.body.metadata.total).toBe(0);

    const csv = await request(app).get('/api/export/audit').set('Authorization', `Bearer ${admin}`);
    expect(csv.text).not.toContain('rm_stored_read');
    expect(csv.text).not.toContain('rm_unverified');

    const delivery = await request(app).get('/api/delivery/summary').set('Authorization', `Bearer ${admin}`);
    expect(delivery.body.channels).toHaveLength(0);
    expect(delivery.body.devices[0].events_24h).toBe(0);
  });

  it('keeps working for a phone that never sends rm_* events', async () => {
    const body = await upload([
      rmEvent('heartbeat_placeholder_never_a_real_type', 1, {}),
    ]);
    // Unknown types are stored as they arrived, exactly as before this change.
    expect(body.accepted).toHaveLength(1);
    expect(await rmHealthRows()).toHaveLength(0);
  });
});
