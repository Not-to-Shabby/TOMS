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
  serial: z.string().trim().min(1).max(40).nullish(),
  label: z.string().trim().max(100).nullish(),
  notes: z.string().trim().max(500).nullish(),
  vehicle_id: z.string().trim().min(1).max(64).nullish(),
});

const batchSchema = z.object({
  count: z.number().int().min(1).max(500),
  prefix: z.string().trim().max(20).regex(/^[A-Za-z0-9_-]*$/, 'letters, digits, - and _ only').default(''),
  start: z.number().int().min(0).max(999999999).default(1),
  digits: z.number().int().min(1).max(10).default(4),
  vehicle_id: z.string().trim().min(1).max(64).nullish(),
});

const pairSchema = z.object({ nfc_uid: nfcUidSchema });

const updateCardSchema = z.object({
  label: z.string().trim().max(100).nullish(),
  notes: z.string().trim().max(500).nullish(),
  status: z.enum(['active', 'suspended', 'retired', 'lost']).optional(),
  vehicle_id: z.string().trim().min(1).max(64).nullable().optional(),
});

const cardsQuery = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  search: z.string().trim().max(100).default(''),
  status: z.enum(['unpaired', 'active', 'suspended', 'retired', 'lost']).optional(),
  vehicle_id: z.string().trim().optional(),
});

export function registerCardRoutes(
  app: Express,
  { pool, admin, auth }: { pool: Pool; admin: RequestHandler; auth: Auth },
) {
  // POST /api/cards - Admin enrolls a card, binds NFC UID with card UUID, and assigns it to a vehicle (or spare pool)
  app.post('/api/cards', admin, async (req: Request, res: Response) => {
    const parseRes = enrollCardSchema.safeParse(req.body);
    if (!parseRes.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: parseRes.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }

    const { nfc_uid, card_uuid, serial, label, notes, vehicle_id } = parseRes.data;
    try {
      const { rows } = await pool.query(
        `INSERT INTO cards (nfc_uid, card_uuid, label, notes, vehicle_id, serial)
         VALUES ($1, COALESCE($2::uuid, gen_random_uuid()), $3, $4, $5, $6)
         RETURNING card_uuid, nfc_uid, serial, label, status, enrolled_at, notes, vehicle_id`,
        [nfc_uid, card_uuid ?? null, label ?? null, notes ?? null, vehicle_id ?? null, serial ?? null],
      );
      res.status(201).json(rows[0]);
    } catch (err) {
      const code = (err as { code?: string }).code;
      if (code === '23505') {
        return void res.status(409).json({ error: 'That NFC UID, card UUID or serial is already enrolled.' });
      }
      if (code === '23503') {
        return void res.status(400).json({ error: `Vehicle "${vehicle_id}" does not exist.` });
      }
      throw err;
    }
  });

  // GET /api/config - Public base URL used when building QR links, so printed codes don't depend on the admin's address bar
  app.get('/api/config', admin, (_req: Request, res: Response) => {
    res.json({ public_base_url: process.env.PUBLIC_BASE_URL?.replace(/\/+$/, '') || null });
  });

  // POST /api/cards/batch - Admin creates N unpaired cards with random UUIDs and sequential serials, ready to print
  app.post('/api/cards/batch', admin, async (req: Request, res: Response) => {
    const parseRes = batchSchema.safeParse(req.body);
    if (!parseRes.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: parseRes.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }
    const { count, prefix, start, digits, vehicle_id } = parseRes.data;
    const serials = Array.from({ length: count }, (_, i) => `${prefix}${String(start + i).padStart(digits, '0')}`);

    try {
      const { rows } = await pool.query(
        `INSERT INTO cards (serial, label, status, vehicle_id)
         SELECT s, s, 'unpaired', $2 FROM unnest($1::text[]) WITH ORDINALITY AS t(s, n)
         ORDER BY n
         RETURNING card_uuid, serial, label, status, vehicle_id`,
        [serials, vehicle_id ?? null],
      );
      rows.sort((a, b) => serials.indexOf(a.serial) - serials.indexOf(b.serial));
      res.status(201).json({ cards: rows, count: rows.length });
    } catch (err) {
      const code = (err as { code?: string }).code;
      if (code === '23505') {
        return void res.status(409).json({ error: 'One or more of those serials already exist. Nothing was created.' });
      }
      if (code === '23503') {
        return void res.status(400).json({ error: `Vehicle "${vehicle_id}" does not exist.` });
      }
      throw err;
    }
  });

  // GET /api/cards/:card_uuid/pairing - Admin pairing screen asks whether this card still needs a chip
  app.get('/api/cards/:card_uuid/pairing', admin, async (req: Request, res: Response) => {
    const parsed = z.string().uuid().safeParse(req.params.card_uuid);
    if (!parsed.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const { rows } = await pool.query(
      'SELECT card_uuid, serial, label, status, nfc_uid, vehicle_id FROM cards WHERE card_uuid = $1::uuid',
      [parsed.data],
    );
    if (rows.length === 0) return void res.status(404).json({ error: 'Card not found' });
    res.json(rows[0]);
  });

  // POST /api/cards/:card_uuid/pair - Admin binds a tapped NFC UID to an unpaired card, exactly once
  app.post('/api/cards/:card_uuid/pair', admin, async (req: Request, res: Response) => {
    const uuid = z.string().uuid().safeParse(req.params.card_uuid);
    const body = pairSchema.safeParse(req.body);
    if (!uuid.success || !body.success) return void res.status(400).json({ error: 'Invalid request' });

    try {
      const { rows } = await pool.query(
        `UPDATE cards SET nfc_uid = $1, status = 'active'
         WHERE card_uuid = $2::uuid AND status = 'unpaired' AND nfc_uid IS NULL
         RETURNING card_uuid, nfc_uid, serial, label, status, vehicle_id`,
        [body.data.nfc_uid, uuid.data],
      );
      if (rows.length === 1) return void res.json(rows[0]);
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        return void res.status(409).json({ error: 'That NFC card is already paired to another card.' });
      }
      throw err;
    }
    const exists = await pool.query('SELECT 1 FROM cards WHERE card_uuid = $1::uuid', [uuid.data]);
    if (exists.rowCount === 0) return void res.status(404).json({ error: 'Card not found' });
    res.status(409).json({ error: 'This card is already paired. Unpair it first to pair it again.' });
  });

  // POST /api/cards/:card_uuid/unpair - Admin clears a wrong pairing so the card can be paired again
  app.post('/api/cards/:card_uuid/unpair', admin, async (req: Request, res: Response) => {
    const uuid = z.string().uuid().safeParse(req.params.card_uuid);
    if (!uuid.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const { rows } = await pool.query(
      `UPDATE cards SET nfc_uid = NULL, status = 'unpaired'
       WHERE card_uuid = $1::uuid AND nfc_uid IS NOT NULL
       RETURNING card_uuid, nfc_uid, serial, label, status, vehicle_id`,
      [uuid.data],
    );
    if (rows.length === 0) return void res.status(404).json({ error: 'Card not found or not paired' });
    res.json(rows[0]);
  });

  // GET /api/cards - Admin lists cards, with search, status filtering, and vehicle filtering
  app.get('/api/cards', admin, async (req: Request, res: Response) => {
    const q = cardsQuery.safeParse(req.query);
    if (!q.success) {
      return void res.status(400).json({ error: 'Invalid query parameters' });
    }
    const { page, limit, search, status, vehicle_id } = q.data;

    const clauses: string[] = ['1=1'];
    const params: unknown[] = [];

    if (search) {
      params.push(`%${search.replace(/[\\%_]/g, '\\$&')}%`);
      clauses.push(`(c.nfc_uid ILIKE $${params.length} OR c.serial ILIKE $${params.length} OR c.card_uuid::text ILIKE $${params.length} OR c.label ILIKE $${params.length} OR c.vehicle_id ILIKE $${params.length} OR v.plate_number ILIKE $${params.length})`);
    }
    if (status) {
      params.push(status);
      clauses.push(`c.status = $${params.length}`);
    }
    if (vehicle_id) {
      if (vehicle_id.toLowerCase() === 'unassigned') {
        clauses.push('c.vehicle_id IS NULL');
      } else {
        params.push(vehicle_id);
        clauses.push(`c.vehicle_id = $${params.length}`);
      }
    }

    const whereSql = clauses.join(' AND ');
    const countRes = await pool.query(
      `SELECT count(*) AS total
       FROM cards c
       LEFT JOIN vehicles v ON v.id = c.vehicle_id
       WHERE ${whereSql}`,
      params,
    );
    const total = Number(countRes.rows[0].total);

    params.push(limit, (page - 1) * limit);
    const { rows } = await pool.query(
      `SELECT c.card_uuid, c.nfc_uid, c.serial, c.label, c.status, c.enrolled_at, c.notes,
              c.vehicle_id, v.plate_number AS vehicle_plate
       FROM cards c
       LEFT JOIN vehicles v ON v.id = c.vehicle_id
       WHERE ${whereSql}
       ORDER BY (c.vehicle_id IS NOT NULL) DESC, c.vehicle_id ASC, regexp_replace(c.serial, '[0-9]+$', '') ASC NULLS LAST,
                (substring(c.serial from '[0-9]+$'))::numeric ASC NULLS LAST, c.serial ASC, c.enrolled_at DESC
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

  // PATCH /api/cards/:card_uuid - Admin updates card status, label, notes, or vehicle assignment
  app.patch('/api/cards/:card_uuid', admin, async (req: Request, res: Response) => {
    const uuidRes = z.string().uuid().safeParse(req.params.card_uuid);
    if (!uuidRes.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const cardUuid = uuidRes.data;
    const parseRes = updateCardSchema.safeParse(req.body);
    if (!parseRes.success) {
      return void res.status(400).json({ error: 'Invalid update payload' });
    }
    const { label, notes, status, vehicle_id } = parseRes.data;
    const hasVehicleUpdate = 'vehicle_id' in parseRes.data;

    try {
      const { rows } = await pool.query(
        `UPDATE cards SET
           label = COALESCE($1, label),
           notes = COALESCE($2, notes),
           status = COALESCE($3, status),
           vehicle_id = CASE WHEN $4::boolean THEN $5 ELSE vehicle_id END
         WHERE card_uuid = $6::uuid
         RETURNING card_uuid, nfc_uid, serial, label, status, enrolled_at, notes, vehicle_id`,
        [
          label ?? null,
          notes ?? null,
          status ?? null,
          hasVehicleUpdate,
          vehicle_id ?? null,
          cardUuid,
        ],
      );

      if (rows.length === 0) {
        return void res.status(404).json({ error: 'Card not found' });
      }
      res.json(rows[0]);
    } catch (err) {
      const code = (err as { code?: string }).code;
      if (code === '23503') {
        return void res.status(400).json({ error: `Vehicle "${vehicle_id}" does not exist.` });
      }
      if (code === '23514') {
        return void res.status(409).json({ error: 'A card with no NFC chip paired cannot be made active or suspended. Pair it first.' });
      }
      throw err;
    }
  });

  // DELETE /api/cards/:card_uuid - Admin retires a card
  app.delete('/api/cards/:card_uuid', admin, async (req: Request, res: Response) => {
    const uuidRes = z.string().uuid().safeParse(req.params.card_uuid);
    if (!uuidRes.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const cardUuid = uuidRes.data;
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
  // Automatically scopes to the authenticated device's assigned vehicle (plus spare unassigned cards),
  // or supports ?vehicle_id= parameter.
  app.get('/api/cards/approved', async (req: Request, res: Response) => {
    const authHeader = req.headers.authorization;
    let authorized = false;
    let boundVehicleId: string | null = null;

    if (authHeader?.startsWith('Bearer ')) {
      const token = authHeader.slice(7);
      if (token.includes('.')) {
        const dot = token.indexOf('.');
        const deviceId = token.slice(0, dot);
        const secret = token.slice(dot + 1);
        const { rows } = await pool.query(
          'SELECT vehicle_id, token_hash, revoked_at FROM devices WHERE device_id = $1',
          [deviceId],
        );
        const stored = rows[0]?.token_hash as string | undefined;
        if (stored && !rows[0].revoked_at) {
          const crypto = await import('crypto');
          const given = Buffer.from(crypto.createHash('sha256').update(secret).digest('hex'), 'hex');
          const expected = Buffer.from(stored, 'hex');
          if (given.length === expected.length && crypto.timingSafeEqual(given, expected)) {
            authorized = true;
            boundVehicleId = rows[0].vehicle_id ?? null;
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

    const queryVehicleId = (req.query.vehicle_id as string)?.trim() || boundVehicleId;

    let query: string;
    let params: unknown[];

    if (queryVehicleId) {
      // Return cards assigned to this vehicle OR in the spare unassigned pool (vehicle_id IS NULL)
      query = `SELECT nfc_uid, card_uuid FROM cards WHERE status = 'active' AND nfc_uid IS NOT NULL AND (vehicle_id = $1 OR vehicle_id IS NULL)`;
      params = [queryVehicleId];
    } else {
      query = `SELECT nfc_uid, card_uuid FROM cards WHERE status = 'active' AND nfc_uid IS NOT NULL`;
      params = [];
    }

    const { rows } = await pool.query<{ nfc_uid: string; card_uuid: string }>(query, params);

    const map: Record<string, string> = {};
    for (const r of rows) {
      map[r.nfc_uid] = r.card_uuid;
    }

    res.json({
      cards: map,
      count: rows.length,
      vehicle_id: queryVehicleId ?? null,
      timestamp: new Date().toISOString(),
    });
  });
}
