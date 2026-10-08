import type { Express, Request, RequestHandler, Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';
import type { Auth } from './auth';

export const nfcUidSchema = z
  .string()
  .transform((s) => s.replace(/[:\s-]/g, '').toUpperCase())
  .pipe(z.string().regex(/^[0-9A-F]{8,20}$/, 'must be 8 to 20 hex digits'));

const enrollCardSchema = z.object({
  nfc_uid: nfcUidSchema,
  card_uuid: z.string().uuid().optional(),
  label: z.string().trim().max(100).nullish(),
  notes: z.string().trim().max(500).nullish(),
});

const updateCardSchema = z.object({
  label: z.string().trim().max(100).nullish(),
  notes: z.string().trim().max(500).nullish(),
  status: z.enum(['active', 'suspended', 'retired', 'lost']).optional(),
});

const cardsQuery = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  search: z.string().trim().max(100).default(''),
  status: z.enum(['active', 'suspended', 'retired', 'lost']).optional(),
});

export function registerCardRoutes(
  app: Express,
  { pool, admin, auth }: { pool: Pool; admin: RequestHandler; auth: Auth },
) {
  // POST /api/cards - Admin enrolls a card and binds NFC UID with card UUID
  app.post('/api/cards', admin, async (req: Request, res: Response) => {
    const parseRes = enrollCardSchema.safeParse(req.body);
    if (!parseRes.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: parseRes.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }

    const { nfc_uid, card_uuid, label, notes } = parseRes.data;
    try {
      const { rows } = await pool.query(
        `INSERT INTO cards (nfc_uid, card_uuid, label, notes)
         VALUES ($1, COALESCE($2::uuid, gen_random_uuid()), $3, $4)
         RETURNING card_uuid, nfc_uid, label, status, enrolled_at, notes`,
        [nfc_uid, card_uuid ?? null, label ?? null, notes ?? null],
      );
      res.status(201).json(rows[0]);
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        return void res.status(409).json({ error: `Card with NFC UID ${nfc_uid} is already enrolled.` });
      }
      throw err;
    }
  });

  // GET /api/cards - Admin lists all cards with search and status filtering
  app.get('/api/cards', admin, async (req: Request, res: Response) => {
    const q = cardsQuery.safeParse(req.query);
    if (!q.success) {
      return void res.status(400).json({ error: 'Invalid query parameters' });
    }
    const { page, limit, search, status } = q.data;

    const clauses: string[] = ['1=1'];
    const params: unknown[] = [];

    if (search) {
      params.push(`%${search.replace(/[\\%_]/g, '\\$&')}%`);
      clauses.push(`(nfc_uid ILIKE $${params.length} OR card_uuid::text ILIKE $${params.length} OR label ILIKE $${params.length})`);
    }
    if (status) {
      params.push(status);
      clauses.push(`status = $${params.length}`);
    }

    const whereSql = clauses.join(' AND ');
    const countRes = await pool.query(`SELECT count(*) AS total FROM cards WHERE ${whereSql}`, params);
    const total = Number(countRes.rows[0].total);

    params.push(limit, (page - 1) * limit);
    const { rows } = await pool.query(
      `SELECT card_uuid, nfc_uid, label, status, enrolled_at, notes
       FROM cards
       WHERE ${whereSql}
       ORDER BY enrolled_at DESC
       LIMIT $${params.length - 1} OFFSET $${params.length}`,
      params,
    );

    res.json({
      cards: rows,
      metadata: {
        total,
        page,
        limit,
        totalPages: Math.ceil(total / limit) || 1,
      },
    });
  });

  // PATCH /api/cards/:card_uuid - Admin updates card status, label, or notes
  app.patch('/api/cards/:card_uuid', admin, async (req: Request, res: Response) => {
    const cardUuid = req.params.card_uuid;
    const parseRes = updateCardSchema.safeParse(req.body);
    if (!parseRes.success) {
      return void res.status(400).json({ error: 'Invalid update payload' });
    }
    const { label, notes, status } = parseRes.data;

    const { rows } = await pool.query(
      `UPDATE cards SET
         label = COALESCE($1, label),
         notes = COALESCE($2, notes),
         status = COALESCE($3, status)
       WHERE card_uuid = $4::uuid
       RETURNING card_uuid, nfc_uid, label, status, enrolled_at, notes`,
      [label ?? null, notes ?? null, status ?? null, cardUuid],
    );

    if (rows.length === 0) {
      return void res.status(404).json({ error: 'Card not found' });
    }
    res.json(rows[0]);
  });

  // DELETE /api/cards/:card_uuid - Admin retires a card
  app.delete('/api/cards/:card_uuid', admin, async (req: Request, res: Response) => {
    const cardUuid = req.params.card_uuid;
    const r = await pool.query(
      `UPDATE cards SET status = 'retired' WHERE card_uuid = $1::uuid AND status <> 'retired'`,
      [cardUuid],
    );
    if (r.rowCount === 0) {
      return void res.status(404).json({ error: 'Card not found or already retired' });
    }
    res.json({ status: 'success', message: 'Card retired' });
  });

  // GET /api/cards/approved - Conductor app / admin fetches active mapping { [nfc_uid]: card_uuid }
  // Authorized with device token or admin token; open to authenticated clients.
  app.get('/api/cards/approved', async (req: Request, res: Response) => {
    const authHeader = req.headers.authorization;
    // Verify bearer token: check device first, then admin/conductor
    let authorized = false;

    if (authHeader?.startsWith('Bearer ')) {
      const token = authHeader.slice(7);
      if (token.includes('.')) {
        // Potential device credential
        const dot = token.indexOf('.');
        const deviceId = token.slice(0, dot);
        const secret = token.slice(dot + 1);
        const { rows } = await pool.query(
          'SELECT token_hash, revoked_at FROM devices WHERE device_id = $1',
          [deviceId],
        );
        const stored = rows[0]?.token_hash as string | undefined;
        if (stored && !rows[0].revoked_at) {
          const crypto = await import('crypto');
          const given = Buffer.from(crypto.createHash('sha256').update(secret).digest('hex'), 'hex');
          const expected = Buffer.from(stored, 'hex');
          if (given.length === expected.length && crypto.timingSafeEqual(given, expected)) {
            authorized = true;
          }
        }
      }
      if (!authorized) {
        const user = await auth.authenticate(token).catch(() => null);
        if (user) authorized = true;
      }
    }

    if (!authorized) {
      return void res.status(401).json({ error: 'Authentication required' });
    }

    const { rows } = await pool.query<{ nfc_uid: string; card_uuid: string }>(
      `SELECT nfc_uid, card_uuid FROM cards WHERE status = 'active'`,
    );

    const map: Record<string, string> = {};
    for (const r of rows) {
      map[r.nfc_uid] = r.card_uuid;
    }

    res.json({
      cards: map,
      count: rows.length,
      timestamp: new Date().toISOString(),
    });
  });
}
