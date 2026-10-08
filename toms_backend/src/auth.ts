import argon2 from 'argon2';
import crypto from 'crypto';
import type { NextFunction, Request, RequestHandler, Response } from 'express';
import jwt from 'jsonwebtoken';
import type { Pool } from 'pg';

export type Role = 'admin' | 'conductor';

export interface AuthInfo {
  role: Role;
  id: number;
  username: string;
}

const ISSUER = 'toms-backend';
const AUDIENCE = 'toms-clients';
const LIFETIME: Record<Role, string> = { admin: '8h', conductor: '16h' };
const TABLE: Record<Role, 'admins' | 'conductors'> = { admin: 'admins', conductor: 'conductors' };

export const MIN_SECRET_LENGTH = 32;

export function assertJwtSecret(secret: string | undefined): string {
  if (!secret || secret.length < MIN_SECRET_LENGTH) {
    throw new Error(`JWT_SECRET must be at least ${MIN_SECRET_LENGTH} characters. Generate one with: openssl rand -hex 32`);
  }
  if (/CHANGE_ME/i.test(secret)) {
    throw new Error('JWT_SECRET still has the placeholder from .env.example. Generate a real one with: openssl rand -hex 32');
  }
  return secret;
}

export function hashDeviceSecret(secret: string): string {
  return crypto.createHash('sha256').update(secret).digest('hex');
}

/** A new device credential. The plaintext is shown once at enrollment and never stored. */
export function newDeviceCredential(deviceId: string): { token: string; tokenHash: string } {
  const secret = crypto.randomBytes(32).toString('base64url');
  return { token: `${deviceId}.${secret}`, tokenHash: hashDeviceSecret(secret) };
}

let dummyHash: Promise<string> | undefined;

/**
 * Verifies a password. For an unknown user it still does the same amount of hashing work, so
 * response time does not reveal which usernames exist.
 */
export async function verifyPassword(storedHash: string | undefined, password: string): Promise<boolean> {
  if (!storedHash) {
    dummyHash ??= argon2.hash('timing-equalizer-not-a-real-password', { type: argon2.argon2id });
    await argon2.verify(await dummyHash, password).catch(() => false);
    return false;
  }
  return argon2.verify(storedHash, password).catch(() => false);
}

/** Counts failed logins per key and blocks further tries for a while. In memory: resets on restart. */
export class LoginLimiter {
  private entries = new Map<string, { count: number; first: number }>();

  constructor(
    private readonly max = 5,
    private readonly windowMs = 15 * 60 * 1000,
    private readonly now: () => number = Date.now,
  ) {}

  /** Milliseconds until another attempt is allowed, or 0 when the key is not blocked. */
  blockedFor(key: string): number {
    const e = this.entries.get(key);
    if (!e) return 0;
    const age = this.now() - e.first;
    if (age >= this.windowMs) {
      this.entries.delete(key);
      return 0;
    }
    return e.count >= this.max ? this.windowMs - age : 0;
  }

  fail(key: string): void {
    const t = this.now();
    const e = this.entries.get(key);
    if (!e || t - e.first >= this.windowMs) {
      if (this.entries.size >= 10_000) this.entries.clear(); // bound memory under a flood of keys
      this.entries.set(key, { count: 1, first: t });
    } else {
      e.count += 1;
    }
  }

  reset(key: string): void {
    this.entries.delete(key);
  }
}

function bearer(req: Request): string | undefined {
  const h = req.headers.authorization;
  if (typeof h !== 'string') return undefined;
  const m = /^Bearer ([^\s]+)$/.exec(h);
  return m?.[1];
}

export function makeAuth({ pool, jwtSecret }: { pool: Pool; jwtSecret: string }) {
  assertJwtSecret(jwtSecret);

  function signToken(role: Role, id: number, username: string): string {
    return jwt.sign({ role, username }, jwtSecret, {
      algorithm: 'HS256',
      subject: String(id),
      issuer: ISSUER,
      audience: AUDIENCE,
      expiresIn: LIFETIME[role] as jwt.SignOptions['expiresIn'],
    });
  }

  /** Valid signature, not expired, and the account still exists. Returns null otherwise. */
  async function authenticate(token: string | undefined): Promise<AuthInfo | null> {
    if (!token) return null;
    let claims: jwt.JwtPayload;
    try {
      const decoded = jwt.verify(token, jwtSecret, { algorithms: ['HS256'], issuer: ISSUER, audience: AUDIENCE });
      if (typeof decoded === 'string') return null;
      claims = decoded;
    } catch {
      return null;
    }
    const id = Number(claims.sub);
    if ((claims.role !== 'admin' && claims.role !== 'conductor') || !Number.isInteger(id) || id <= 0) return null;
    const role: Role = claims.role;

    const { rows } = await pool.query(`SELECT username FROM ${TABLE[role]} WHERE id = $1`, [id]);
    if (rows.length === 0) return null; // deleted account: its old tokens stop working at once
    return { role, id, username: rows[0].username as string };
  }

  function requireRole(...allowed: Role[]): RequestHandler {
    return async (req: Request, res: Response, next: NextFunction) => {
      const info = await authenticate(bearer(req));
      if (!info) return void res.status(401).json({ error: 'Authentication required' });
      if (!allowed.includes(info.role)) return void res.status(403).json({ error: 'Not allowed for this account' });
      res.locals.auth = info;
      next();
    };
  }

  /** Phone credential "<device_id>.<secret>". Sets res.locals.deviceId to the authenticated device. */
  const requireDevice: RequestHandler = async (req, res, next) => {
    const deny = () => void res.status(401).json({ error: 'Device authentication required' });
    const token = bearer(req);
    const dot = token?.indexOf('.') ?? -1;
    if (!token || dot <= 0) return deny();

    const deviceId = token.slice(0, dot);
    const secret = token.slice(dot + 1);
    const { rows } = await pool.query(
      'SELECT token_hash, revoked_at FROM devices WHERE device_id = $1',
      [deviceId],
    );
    const stored = rows[0]?.token_hash as string | null | undefined;
    const given = Buffer.from(hashDeviceSecret(secret), 'hex');
    const expected = Buffer.from(stored ?? '0'.repeat(64), 'hex');
    const matches = given.length === expected.length && crypto.timingSafeEqual(given, expected);

    if (!stored || !matches || rows[0].revoked_at) return deny();
    res.locals.deviceId = deviceId;
    next();
  };

  return { signToken, authenticate, requireRole, requireDevice };
}

export type Auth = ReturnType<typeof makeAuth>;
