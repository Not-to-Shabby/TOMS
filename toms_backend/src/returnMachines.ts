import type { Express, Request, Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';
import type { Auth } from './auth';
import type { KeyVault } from './keyVault';
import { RateLimiter } from './rateLimit';

/**
 * How long the previous key stays issuable after a rotation. The phone needs it to keep verifying the
 * machine's messages until the new key has been written to the machine and the machine's next signed
 * message verifies; an hour covers a shift's worth of reconnects without keeping a retired key around.
 */
export const GRACE_WINDOW_MS = 60 * 60 * 1000;

/** Key fetches per phone per minute. A real phone asks once per connection; a loop asking more is a bug or an attack. */
const KEY_FETCHES_PER_MINUTE = 6;

const machineBody = z.object({
  machine_id: z.string().trim().regex(/^[A-Za-z0-9_:.-]{3,64}$/, 'letters, digits, dash, underscore, colon, dot; 3 to 64'),
  vehicle_id: z.string().trim().min(1).max(64),
});

const mismatchBody = z.object({
  reason: z.enum(['machine_has_other_key', 'rotation_needs_reflash', 'no_signed_messages']),
});

const verifiedBody = z.object({ key_version: z.number().int().min(1) });

/** What the dashboard sees of a machine. Deliberately has no key field, not even an empty one. */
function publicRow(r: Record<string, unknown>) {
  return {
    id: Number(r.id),
    machine_id: r.machine_id,
    vehicle_id: r.vehicle_id,
    created_at: r.created_at,
    key_created_at: r.key_created_at,
    rotated_at: r.rotated_at,
    key_version: r.key_version,
    last_issued_at: r.last_issued_at,
    last_issued_device: r.last_issued_device,
    last_issued_version: r.last_issued_version,
    mismatch_since: r.mismatch_since,
    mismatch_reason: r.mismatch_reason,
    in_grace: r.previous_valid_until ? new Date(r.previous_valid_until as string).getTime() > Date.now() : false,
  };
}

const PUBLIC_COLUMNS = `id, machine_id, vehicle_id, created_at, key_created_at, rotated_at, key_version,
  last_issued_at, last_issued_device, last_issued_version, mismatch_since, mismatch_reason, previous_valid_until`;

async function audit(
  pool: Pool,
  machineId: string,
  eventType: string,
  keyVersion: number | null,
  actor: string,
  detail: string | null = null,
) {
  // The row never carries key material: only who, what, and which version.
  await pool.query(
    'INSERT INTO return_machine_audit (machine_id, event_type, key_version, actor, detail) VALUES ($1, $2, $3, $4, $5)',
    [machineId, eventType, keyVersion, actor, detail],
  );
}

export function registerReturnMachineRoutes(
  app: Express,
  {
    pool,
    admin,
    auth,
    vault,
    fetchLimiter = new RateLimiter(KEY_FETCHES_PER_MINUTE, 60_000),
  }: { pool: Pool; admin: import('express').RequestHandler; auth: Auth; vault?: KeyVault; fetchLimiter?: RateLimiter },
) {
  const noVault = (res: Response) =>
    void res.status(503).json({
      error: 'Return Machine keys are not configured on this server (RM_KEY_MASTER_KEY is not set). Nothing is stored or served.',
    });

  // --- Admin: list, register, rotate -------------------------------------------------------------

  app.get('/api/return-machines', admin, async (_req: Request, res: Response) => {
    const { rows } = await pool.query(`SELECT ${PUBLIC_COLUMNS} FROM return_machines ORDER BY machine_id`);
    res.json(rows.map(publicRow));
  });

  app.get('/api/return-machines/:machineId/audit', admin, async (req: Request, res: Response) => {
    const { rows } = await pool.query(
      `SELECT at, event_type, key_version, actor, detail FROM return_machine_audit
       WHERE machine_id = $1 ORDER BY at DESC, id DESC LIMIT 100`,
      [String(req.params.machineId)],
    );
    res.json(rows);
  });

  app.post('/api/return-machines', admin, async (req: Request, res: Response) => {
    if (!vault) return noVault(res);
    const parsed = machineBody.safeParse(req.body);
    if (!parsed.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: parsed.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }
    const { machine_id, vehicle_id } = parsed.data;
    const key = vault.newKey();
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      // The sealed blob binds the row id, which the INSERT does not know yet: take the id first.
      const idRow = await client.query("SELECT nextval(pg_get_serial_sequence('return_machines', 'id')) AS id");
      const rowId = String(idRow.rows[0].id);
      await client.query(
        `INSERT INTO return_machines (id, machine_id, vehicle_id, key_enc) VALUES ($1, $2, $3, $4)`,
        [rowId, machine_id, vehicle_id, vault.seal(key, rowId)],
      );
      await client.query('COMMIT');
      key.fill(0);
      await audit(pool, machine_id, 'rm_key_created', 1, `admin:${res.locals.auth.username}`);
      const { rows } = await pool.query(`SELECT ${PUBLIC_COLUMNS} FROM return_machines WHERE id = $1`, [rowId]);
      // The key is not in this response, now or ever: a phone fetches it with its device token.
      res.status(201).json(publicRow(rows[0]));
    } catch (err) {
      await client.query('ROLLBACK').catch(() => undefined);
      const code = (err as { code?: string }).code;
      if (code === '23505') {
        const detail = (err as { constraint?: string }).constraint ?? '';
        return void res.status(409).json({
          error: detail.includes('vehicle')
            ? 'That vehicle already has a Return Machine registered'
            : 'A Return Machine with that id already exists',
        });
      }
      if (code === '23503') return void res.status(400).json({ error: 'Unknown vehicle' });
      throw err;
    } finally {
      key.fill(0);
      client.release();
    }
  });

  app.post('/api/return-machines/:machineId/rotate', admin, async (req: Request, res: Response) => {
    if (!vault) return noVault(res);
    const machineId = String(req.params.machineId);
    const newKey = vault.newKey();
    try {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const cur = await client.query('SELECT id, key_enc FROM return_machines WHERE machine_id = $1 FOR UPDATE', [machineId]);
        if (cur.rows.length === 0) {
          await client.query('ROLLBACK');
          return void res.status(404).json({ error: 'No such Return Machine' });
        }
        const rowId = String(cur.rows[0].id);
        // The old key stays issuable for one grace window; a second rotation inside it replaces the grace
        // key with the one just retired, so at most one retired key is ever held.
        await client.query(
          `UPDATE return_machines
           SET previous_key_enc = key_enc, previous_valid_until = now() + ($2 || ' milliseconds')::interval,
               key_enc = $3, key_version = key_version + 1, key_created_at = now(), rotated_at = now(),
               mismatch_since = NULL, mismatch_reason = NULL
           WHERE machine_id = $1`,
          [machineId, String(GRACE_WINDOW_MS), vault.seal(newKey, rowId)],
        );
        await client.query('COMMIT');
      } catch (err) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw err;
      } finally {
        client.release();
      }
    } finally {
      newKey.fill(0);
    }
    const { rows } = await pool.query(`SELECT ${PUBLIC_COLUMNS} FROM return_machines WHERE machine_id = $1`, [machineId]);
    await audit(pool, machineId, 'rm_key_rotated', rows[0].key_version as number, `admin:${res.locals.auth.username}`);
    res.json(publicRow(rows[0]));
  });

  // --- Phone: fetch the key for its own vehicle's machine -----------------------------------------

  async function ownMachine(deviceId: string) {
    return (
      await pool.query(
        `SELECT m.* FROM return_machines m JOIN devices d ON d.vehicle_id = m.vehicle_id WHERE d.device_id = $1`,
        [deviceId],
      )
    ).rows[0] as Record<string, unknown> | undefined;
  }

  /**
   * GET /api/device/return-machine-key - the Return Machine of the vehicle this phone is enrolled on, with its key.
   * Only the phone enrolled for that vehicle gets a key: the vehicle comes from the device record behind the token,
   * never from a parameter, so asking for another vehicle's machine is not expressible. The response is not cacheable.
   */
  app.get('/api/device/return-machine-key', auth.requireDevice, async (_req: Request, res: Response) => {
    if (!vault) return noVault(res);
    const deviceId = res.locals.deviceId as string;

    const wait = fetchLimiter.hit(deviceId);
    if (wait > 0) {
      res.setHeader('Retry-After', String(wait));
      return void res.status(429).json({ error: 'Too many key requests. Try again shortly.' });
    }

    const machine = await ownMachine(deviceId);
    if (!machine) return void res.status(404).json({ error: 'No Return Machine is registered for this phone\'s vehicle.' });

    const rowId = String(machine.id);
    const key = vault.open(machine.key_enc as Buffer, rowId);
    if (!key) {
      // The sealed blob would not open: wrong master key, or a tampered row. Say so without detail.
      console.error('return machine key could not be opened for machine row', rowId);
      return void res.status(500).json({ error: 'The Return Machine key could not be read. Ask an administrator.' });
    }

    let previous: Buffer | null = null;
    const prevUntil = machine.previous_valid_until ? new Date(machine.previous_valid_until as string).getTime() : 0;
    if (machine.previous_key_enc && prevUntil > Date.now()) {
      previous = vault.open(machine.previous_key_enc as Buffer, rowId);
    }

    await pool.query(
      `UPDATE return_machines SET last_issued_at = now(), last_issued_device = $2, last_issued_version = key_version
       WHERE id = $1`,
      [machine.id, deviceId],
    );
    await audit(pool, machine.machine_id as string, 'rm_key_issued', machine.key_version as number, `device:${deviceId}`);

    res.setHeader('Cache-Control', 'no-store');
    res.json({
      machine_id: machine.machine_id,
      key_version: machine.key_version,
      key: key.toString('base64'),
      // Present only inside the grace window after a rotation.
      previous_key: previous ? previous.toString('base64') : null,
      previous_valid_until: previous ? machine.previous_valid_until : null,
    });
    key.fill(0);
    previous?.fill(0);
  });

  // --- Phone: report what it saw -----------------------------------------------------------------

  /** The machine rejected or lost the key (a reset leaves it without one): flag it for the dashboard, never silently. */
  app.post('/api/device/return-machine-key/mismatch', auth.requireDevice, async (req: Request, res: Response) => {
    const deviceId = res.locals.deviceId as string;
    const parsed = mismatchBody.safeParse(req.body);
    if (!parsed.success) return void res.status(400).json({ error: 'Invalid request' });
    const machine = await ownMachine(deviceId);
    if (!machine) return void res.status(404).json({ error: 'No Return Machine is registered for this phone\'s vehicle.' });

    // Keeps the first time it was seen; a repeat only refreshes the reason.
    await pool.query(
      `UPDATE return_machines SET mismatch_since = COALESCE(mismatch_since, now()), mismatch_reason = $2 WHERE id = $1`,
      [machine.id, parsed.data.reason],
    );
    await audit(pool, machine.machine_id as string, 'rm_key_mismatch', machine.key_version as number, `device:${deviceId}`, parsed.data.reason);
    res.json({ status: 'flagged' });
  });

  /** A signed message verified under key_version: the loop is closed. Clears any mismatch flag set for an older version. */
  app.post('/api/device/return-machine-key/verified', auth.requireDevice, async (req: Request, res: Response) => {
    const deviceId = res.locals.deviceId as string;
    const parsed = verifiedBody.safeParse(req.body);
    if (!parsed.success) return void res.status(400).json({ error: 'Invalid request' });
    const machine = await ownMachine(deviceId);
    if (!machine) return void res.status(404).json({ error: 'No Return Machine is registered for this phone\'s vehicle.' });

    // Only the current version clears a mismatch. A phone still on an older key must not hide a real problem.
    if (parsed.data.key_version === (machine.key_version as number)) {
      await pool.query('UPDATE return_machines SET mismatch_since = NULL, mismatch_reason = NULL WHERE id = $1', [machine.id]);
      await audit(pool, machine.machine_id as string, 'rm_key_verified', parsed.data.key_version, `device:${deviceId}`);
    }
    res.json({ status: 'ok' });
  });
}
