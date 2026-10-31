import { describe, expect, it } from 'vitest';
import { RateLimiter } from '../src/rateLimit';

describe('rate limiter', () => {
  it('allows the first hits, then refuses with the seconds until the window resets', () => {
    let t = 1_000;
    const l = new RateLimiter(2, 10_000, () => t);
    expect(l.hit('a')).toBe(0);
    expect(l.hit('a')).toBe(0);
    const wait = l.hit('a');
    expect(wait).toBeGreaterThan(0);
    expect(wait).toBeLessThanOrEqual(10);
    t += 10_000;
    expect(l.hit('a')).toBe(0);
  });

  it('counts each key on its own', () => {
    const l = new RateLimiter(1, 10_000);
    expect(l.hit('a')).toBe(0);
    expect(l.hit('b')).toBe(0);
    expect(l.hit('a')).toBeGreaterThan(0);
  });
});
