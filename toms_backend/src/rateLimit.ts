/**
 * Counts hits per key in fixed windows. In memory: a restart clears it, which is fine for a single-server pilot.
 */
export class RateLimiter {
  private entries = new Map<string, { count: number; first: number }>();

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Records one hit. Returns 0 when the hit is allowed, otherwise the seconds until the window resets. */
  hit(key: string): number {
    const t = this.now();
    let e = this.entries.get(key);
    if (!e || t - e.first >= this.windowMs) {
      // Bounded under a flood of addresses. Drop only the oldest entry, as in LoginLimiter.
      if (!e && this.entries.size >= 50_000) {
        const oldest = this.entries.keys().next().value;
        if (oldest !== undefined) this.entries.delete(oldest);
      }
      e = { count: 0, first: t };
      this.entries.set(key, e);
    }
    e.count += 1;
    if (e.count <= this.max) return 0;
    return Math.max(1, Math.ceil((e.first + this.windowMs - t) / 1000));
  }
}
