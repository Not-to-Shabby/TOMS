import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { Pool } from 'pg';
import { createApp } from '../src/app';
import { createTestDb, TEST_JWT_SECRET } from './helpers';

let pool: Pool;
let drop: () => Promise<void>;

beforeAll(async () => {
  ({ pool, drop } = await createTestDb());
});
afterAll(async () => drop());

describe('GET /api/health', () => {
  it('answers 200 with status ok when the database answers', async () => {
    const app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
    const res = await request(app).get('/api/health');
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: 'ok' });
  });

  it('answers 503 with no error details when the database fails', async () => {
    const failing = { query: vi.fn().mockRejectedValue(new Error('password authentication failed for user "toms"')) } as unknown as Pool;
    const app = createApp({ pool: failing, jwtSecret: TEST_JWT_SECRET });
    const res = await request(app).get('/api/health');
    expect(res.status).toBe(503);
    expect(res.body).toEqual({ status: 'error' });
    expect(res.text).not.toMatch(/password|authentication|toms/);
  });

  it('reveals no version, build, config or timestamp', async () => {
    const app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
    const res = await request(app).get('/api/health');
    expect(Object.keys(res.body)).toEqual(['status']);
    expect(res.text).not.toMatch(/version|timestamp|PUBLIC_BASE_URL|CORS|node_modules/i);
  });

  it('is not counted against the public card and receipt limit', async () => {
    const { RateLimiter } = await import('../src/rateLimit');
    const tight = createApp({ pool, jwtSecret: TEST_JWT_SECRET, publicLimiter: new RateLimiter(1, 60_000) });
    for (let i = 0; i < 5; i++) {
      expect((await request(tight).get('/api/health')).status).toBe(200);
    }
  });

  it('keeps the old /health route for existing checks', async () => {
    const app = createApp({ pool, jwtSecret: TEST_JWT_SECRET });
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
  });
});
