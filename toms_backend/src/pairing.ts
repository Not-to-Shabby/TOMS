import crypto from 'crypto';
import type { Express, Request, RequestHandler, Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';
import { newDeviceCredential } from './auth';
import { RateLimiter } from './rateLimit';

/**
 * One-time pairing codes: a phone enrols itself, so nobody reads out a Device ID or carries a token.
 *
 * An administrator issues a code for one vehicle. The phone sends the code with its own Device ID and gets its
 * device token back in that response, once. What is kept, and what is not:
 *  - the second half of a code is never stored; only a keyed hash of it is (HMAC-SHA256 under a key derived from
 *    the server's JWT secret), so a copy of the table cannot be used to work a code out offline;
 *  - the vehicle comes from the code, never from the phone;
 *  - nothing here logs, audits or lists a code or a token after the response that issues it.
 *
 * The device list (GET /api/devices) is here too, because the dashboard's Phones page needs it next to the codes.
 * See documentation/device_pairing.md for the contract the dashboard and the app build on.
 */

/** Digits 2-9 and the letters except I, L, O, U: no 0/O or 1/I/L look-alikes, and no U to confuse with V. 30 symbols. */
export const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ';
/** The public part of a code. It finds the row, and it is what lets a wrong guess be counted against that code. */
export const CODE_ID_LENGTH = 4;
/** The secret part. 30^8 is about 6.6e11, 39 bits. */
export const CODE_SECRET_LENGTH = 8;
export const CODE_TTL_MINUTES = 10;
/** A code locks, for good, when this many wrong secrets were tried against it. */
export const MAX_WRONG_ATTEMPTS = 5;
/** Codes that can still be used at once. Caps what a blind guess can hit, and what a careless click can leave lying around. */
export const MAX_OPEN_CODES = 10;
/** Pairing attempts per address per minute. A real phone pairs once. */
const PAIR_ATTEMPTS_PER_MINUTE = 10;
/** Next to the migration lock (727274), so the two can never be mistaken for each other. */
const PAIRING_ISSUE_LOCK_ID = 727275;

const DEVICE_ID = /^[A-Za-z0-9_-]{3,64}$/;
const REFUSED = 'That pairing code did not work. Check it, or ask your administrator for a new one.';

const OPEN_SQL = 'used_at IS NULL AND cancelled_at IS NULL AND locked_at IS NULL AND expires_at > now()';
const STATUS_SQL = `CASE
  WHEN used_at IS NOT NULL THEN 'used'
  WHEN cancelled_at IS NOT NULL THEN 'cancelled'
  WHEN locked_at IS NOT NULL THEN 'locked'
  WHEN expires_at <= now() THEN 'expired'
  ELSE 'open' END`;

const issueBody = z.object({
  vehicle_id: z.string().trim().min(1).max(64),
  label: z.string().trim().min(1).max(100).nullish(),
});

/** Strict on purpose: a phone that sends a vehicle (or anything else we do not read) is told, not quietly ignored. */
const pairBody = z.strictObject({
  code: z.string().min(1).max(64),
  device_id: z.string().trim().regex(DEVICE_ID, 'letters, digits, dash, underscore; 3 to 64'),
});

function randomSymbols(n: number): string {
  let out = '';
  for (let i = 0; i < n; i++) out += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  return out;
}

/** A code as people read it: ABCD-EFGH-JKMN. The first group is the public id. */
export function formatCode(codeId: string, secret: string): string {
  return `${codeId}-${secret.slice(0, 4)}-${secret.slice(4)}`;
}

/** Accepts the code as typed (any case, with or without dashes or spaces). Null when it cannot be a code. */
export function parseCode(input: string): { codeId: string; secret: string } | null {
  const clean = input.toUpperCase().replace(/[\s-]/g, '');
  if (!new RegExp(`^[${CODE_ALPHABET}]{${CODE_ID_LENGTH + CODE_SECRET_LENGTH}}$`).test(clean)) return null;
  return { codeId: clean.slice(0, CODE_ID_LENGTH), secret: clean.slice(CODE_ID_LENGTH) };
}

/** What the dashboard turns into a QR code. Not a web link on purpose: nothing secret can reach a web server's log. */
export function enrolPayload(serverUrl: string, code: string): string {
  return `toms-enrol://v1?server=${encodeURIComponent(serverUrl)}&code=${code}`;
}

/** Derived, not the JWT secret itself, so a leak of this key says nothing about login tokens. */
function pairingKey(jwtSecret: string): Buffer {
  return crypto.createHmac('sha256', jwtSecret).update('toms-pairing-code-v1').digest();
}

/** Bound to the code id, so a stored hash cannot be moved to another row. */
function hashSecret(key: Buffer, codeId: string, secret: string): string {
  return crypto.createHmac('sha256', key).update(`${codeId}:${secret}`).digest('hex');
}

function sameHash(aHex: string, bHex: string): boolean {
  const a = Buffer.from(aHex, 'hex');
  const b = Buffer.from(bHex, 'hex');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/** Anything with a query method: the pool, or a client inside a transaction. */
interface Queryable {
  query(text: string, values?: unknown[]): Promise<unknown>;
}

/** Who did what to which code or phone. Never a secret, a hash of one, or a token: only the public half of a code. */
async function audit(
  db: Queryable,
  eventType: 'pairing_code_issued' | 'pairing_code_cancelled' | 'pairing_code_locked' | 'device_paired',
  fields: { codeId: string | null; vehicleId: string | null; deviceId?: string | null; actor: string; detail?: string | null },
) {
  await db.query(
    'INSERT INTO device_pairing_audit (event_type, code_id, vehicle_id, device_id, actor, detail) VALUES ($1, $2, $3, $4, $5, $6)',
    [eventType, fields.codeId, fields.vehicleId, fields.deviceId ?? null, fields.actor, fields.detail ?? null],
  );
}

export function registerPairingRoutes(
  app: Express,
  {
    pool,
    admin,
    jwtSecret,
    pairLimiter = new RateLimiter(PAIR_ATTEMPTS_PER_MINUTE, 60_000),
  }: { pool: Pool; admin: RequestHandler; jwtSecret: string; pairLimiter?: RateLimiter },
) {
  const key = pairingKey(jwtSecret);
  const invalid = (res: Response, issues: { path: string; message: string }[]) =>
    void res.status(400).json({ error: 'Invalid request', issues });

  // --- Admin: issue, list, cancel ---------------------------------------------------------------

  app.post('/api/pairing-codes', admin, async (req: Request, res: Response) => {
    const parsed = issueBody.safeParse(req.body);
    if (!parsed.success) {
      return invalid(res, parsed.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })));
    }
    const { vehicle_id, label } = parsed.data;
    const username = res.locals.auth.username as string;

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      // One issuer at a time, so the cap on open codes is exact even when two administrators click together.
      await client.query('SELECT pg_advisory_xact_lock($1)', [PAIRING_ISSUE_LOCK_ID]);
      const open = await client.query(`SELECT count(*)::int AS n FROM pairing_codes WHERE ${OPEN_SQL}`);
      if (open.rows[0].n >= MAX_OPEN_CODES) {
        await client.query('ROLLBACK');
        return void res.status(409).json({
          error: `There are already ${MAX_OPEN_CODES} open pairing codes. Cancel one, or wait for one to expire.`,
        });
      }

      let issued: { id: string; created_at: string; expires_at: string; codeId: string; secret: string } | undefined;
      // A clash on the public id is possible in principle (30^4 of them) and just means: draw again.
      for (let attempt = 0; attempt < 5 && !issued; attempt++) {
        const codeId = randomSymbols(CODE_ID_LENGTH);
        const secret = randomSymbols(CODE_SECRET_LENGTH);
        const ins = await client.query(
          `INSERT INTO pairing_codes (code_id, secret_hash, vehicle_id, label, created_by, expires_at)
           VALUES ($1, $2, $3, $4, $5, now() + make_interval(mins => $6))
           ON CONFLICT (code_id) DO NOTHING
           RETURNING id, created_at, expires_at`,
          [codeId, hashSecret(key, codeId, secret), vehicle_id, label ?? null, username, CODE_TTL_MINUTES],
        );
        if (ins.rows[0]) issued = { ...ins.rows[0], codeId, secret };
      }
      if (!issued) throw new Error('could not find a free pairing code id');

      await audit(client, 'pairing_code_issued', { codeId: issued.codeId, vehicleId: vehicle_id, actor: `admin:${username}` });
      await client.query('COMMIT');

      const code = formatCode(issued.codeId, issued.secret);
      const serverUrl = process.env.PUBLIC_BASE_URL?.trim().replace(/\/+$/, '') || null;
      // The only time the code is visible: only its hash is stored, so it cannot be shown again.
      res.setHeader('Cache-Control', 'no-store');
      res.status(201).json({
        id: Number(issued.id),
        code,
        code_id: issued.codeId,
        vehicle_id,
        label: label ?? null,
        created_by: username,
        created_at: issued.created_at,
        expires_at: issued.expires_at,
        expires_in_seconds: CODE_TTL_MINUTES * 60,
        qr_payload: serverUrl ? enrolPayload(serverUrl, code) : null,
      });
    } catch (err) {
      await client.query('ROLLBACK').catch(() => undefined);
      if ((err as { code?: string }).code === '23503') return void res.status(400).json({ error: 'Unknown vehicle' });
      throw err;
    } finally {
      client.release();
    }
  });

  app.get('/api/pairing-codes', admin, async (_req: Request, res: Response) => {
    // Deliberately no secret_hash, and no way to read a code back: only its public id.
    const { rows } = await pool.query(
      `SELECT id, code_id, vehicle_id, label, created_by, created_at, expires_at, attempts,
              used_at, used_device_id, ${STATUS_SQL} AS status
         FROM pairing_codes ORDER BY created_at DESC, id DESC LIMIT 50`,
    );
    res.json(rows.map((r) => ({ ...r, id: Number(r.id) })));
  });

  app.delete('/api/pairing-codes/:id', admin, async (req: Request, res: Response) => {
    const id = z.coerce.number().int().positive().safeParse(req.params.id);
    if (!id.success) return void res.status(404).json({ error: 'Pairing code not found, or it is already used or cancelled' });
    const { rows } = await pool.query(
      `UPDATE pairing_codes SET cancelled_at = now()
        WHERE id = $1 AND used_at IS NULL AND cancelled_at IS NULL
        RETURNING code_id, vehicle_id`,
      [id.data],
    );
    if (!rows[0]) return void res.status(404).json({ error: 'Pairing code not found, or it is already used or cancelled' });
    await audit(pool, 'pairing_code_cancelled', {
      codeId: rows[0].code_id,
      vehicleId: rows[0].vehicle_id,
      actor: `admin:${res.locals.auth.username}`,
    });
    res.json({ status: 'cancelled' });
  });

  app.get('/api/pairing-audit', admin, async (_req: Request, res: Response) => {
    const { rows } = await pool.query(
      `SELECT at, event_type, code_id, vehicle_id, device_id, actor, detail
         FROM device_pairing_audit ORDER BY at DESC, id DESC LIMIT 100`,
    );
    res.json(rows);
  });

  // --- Admin: the phones --------------------------------------------------------------------------

  app.get('/api/devices', admin, async (_req: Request, res: Response) => {
    // No token_hash, not even as a flag: whether a credential exists is `revoked` and nothing more.
    const { rows } = await pool.query(
      `SELECT d.device_id, d.vehicle_id, d.label, d.enrolled_at, d.revoked_at,
              d.revoked_at IS NOT NULL AS revoked,
              (SELECT max(e.received_at) FROM events e WHERE e.device_id = d.device_id) AS last_seen
         FROM devices d ORDER BY d.enrolled_at DESC, d.device_id`,
    );
    res.json(rows);
  });

  // --- The phone: trade a code for a token ---------------------------------------------------------

  app.post('/api/devices/pair', async (req: Request, res: Response) => {
    res.setHeader('Cache-Control', 'no-store');
    const wait = pairLimiter.hit(req.ip ?? 'unknown');
    if (wait > 0) {
      res.setHeader('Retry-After', String(wait));
      return void res.status(429).json({ error: 'Too many attempts. Wait a minute, then try again.' });
    }

    const parsed = pairBody.safeParse(req.body);
    if (!parsed.success) {
      return invalid(res, parsed.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })));
    }
    const code = parseCode(parsed.data.code);
    if (!code) {
      return invalid(res, [{ path: 'code', message: 'A pairing code is 12 letters and digits, shown as ABCD-EFGH-JKMN' }]);
    }
    const deviceId = parsed.data.device_id;
    const refuse = () => void res.status(403).json({ error: REFUSED });

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      // The row stays locked until this transaction ends, so two requests for one code are handled one after
      // the other: the attempt count cannot be beaten by guessing in parallel, and a code cannot be used twice.
      const found = await client.query(
        `SELECT id, secret_hash, vehicle_id, label,
                (${OPEN_SQL}) AS usable
           FROM pairing_codes WHERE code_id = $1 FOR UPDATE`,
        [code.codeId],
      );
      const row = found.rows[0];
      // The hash is worked out and compared whether or not the row was found, so the comparison itself does not
      // depend on that. (The lookup before it does, as with any database read; the answer sent is the same.)
      const matches = sameHash(hashSecret(key, code.codeId, code.secret), row?.secret_hash ?? '0'.repeat(64));
      // Wrong id, used, cancelled, locked, expired: one answer for all, so a stranger learns nothing about which.
      if (!row || !row.usable) {
        await client.query('ROLLBACK');
        return refuse();
      }

      if (!matches) {
        const counted = await client.query(
          `UPDATE pairing_codes
              SET attempts = attempts + 1,
                  locked_at = CASE WHEN attempts + 1 >= $2 THEN now() ELSE NULL END
            WHERE id = $1
            RETURNING locked_at IS NOT NULL AS locked`,
          [row.id, MAX_WRONG_ATTEMPTS],
        );
        if (counted.rows[0].locked) {
          await audit(client, 'pairing_code_locked', {
            codeId: code.codeId,
            vehicleId: row.vehicle_id,
            actor: 'system',
            detail: `${MAX_WRONG_ATTEMPTS} wrong tries`,
          });
        }
        await client.query('COMMIT'); // the count must survive the refusal
        return refuse();
      }

      const { token, tokenHash } = newDeviceCredential(deviceId);
      try {
        await client.query('INSERT INTO devices (device_id, vehicle_id, label, token_hash) VALUES ($1, $2, $3, $4)', [
          deviceId,
          row.vehicle_id,
          row.label,
          tokenHash,
        ]);
      } catch (err) {
        await client.query('ROLLBACK');
        const pgCode = (err as { code?: string }).code;
        // The ID is taken, including a revoked one: a revoked ID stays revoked. The code is not used up.
        if (pgCode === '23505') {
          return void res.status(409).json({
            error: 'This phone is already enrolled, or its ID was revoked. Ask your administrator for help.',
          });
        }
        if (pgCode === '23503') return refuse(); // the vehicle was deleted a moment ago
        throw err;
      }
      await client.query('UPDATE pairing_codes SET used_at = now(), used_device_id = $2 WHERE id = $1', [row.id, deviceId]);
      await audit(client, 'device_paired', {
        codeId: code.codeId,
        vehicleId: row.vehicle_id,
        deviceId,
        actor: `device:${deviceId}`,
      });
      await client.query('COMMIT');
      res.status(201).json({ device_id: deviceId, token, vehicle_id: row.vehicle_id });
    } catch (err) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw err;
    } finally {
      client.release();
    }
  });
}
