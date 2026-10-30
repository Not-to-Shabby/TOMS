import type { Express, Request, RequestHandler, Response } from 'express';
import type { Pool, PoolClient } from 'pg';
import { z } from 'zod';
import type { Auth } from './auth';
import { CARD_STATUSES, NEEDS_REASON, WARNS_OPEN_TRIPS, checkMove, type CardStatus } from './cardLifecycle';
import { EXPORT_ROW_LIMIT, OPEN_STATES, OPEN_TRIP_WINDOW_HOURS, csvText } from './dashboard';

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

const reasonSchema = z.string().trim().min(1).max(300);

const updateCardSchema = z.object({
  // null or an empty string clears the field; leaving it out keeps what is there.
  label: z.string().trim().max(100).nullish(),
  notes: z.string().trim().max(500).nullish(),
  status: z.enum(CARD_STATUSES).optional(),
  vehicle_id: z.string().trim().min(1).max(64).nullable().optional(),
  reason: reasonSchema.nullish(),
  /** The admin has seen the open-trip warning and wants to go ahead. */
  confirm: z.boolean().optional(),
});

const statusChangeSchema = z.object({
  reason: reasonSchema.nullish(),
  confirm: z.boolean().optional(),
});

export const BULK_LIMIT = 500;

/** Which cards a bulk change or lookup applies to: an explicit list, or everything matching the registry filters. */
const targetSchema = z.object({
  card_uuids: z.array(z.string().uuid()).min(1).max(BULK_LIMIT).optional(),
  filter: z
    .object({
      search: z.string().trim().max(100).optional(),
      status: z.enum(CARD_STATUSES).optional(),
      vehicle_id: z.string().trim().optional(),
    })
    .optional(),
});

const bulkSchema = targetSchema
  .extend({
    action: z.enum(['status', 'vehicle']),
    status: z.enum(CARD_STATUSES).optional(),
    /** For action "vehicle": a vehicle id, or null for the spare pool. */
    vehicle_id: z.string().trim().min(1).max(64).nullable().optional(),
    reason: reasonSchema.nullish(),
    confirm: z.boolean().optional(),
  })
  .refine((b) => !!b.card_uuids !== !!b.filter, { message: 'Give either card_uuids or filter, not both and not neither.' })
  .refine((b) => b.action !== 'status' || !!b.status, { message: 'status is required for a status change.' })
  .refine((b) => b.action !== 'vehicle' || 'vehicle_id' in b, { message: 'vehicle_id is required for a vehicle change (null for the spare pool).' });

const lookupSchema = targetSchema.refine((b) => !!b.card_uuids !== !!b.filter, {
  message: 'Give either card_uuids or filter, not both and not neither.',
});

const exportQuery = z.object({
  search: z.string().trim().max(100).default(''),
  status: z.enum(CARD_STATUSES).optional(),
  vehicle_id: z.string().trim().optional(),
});

const cardsQuery = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  search: z.string().trim().max(100).default(''),
  status: z.enum(['unpaired', 'active', 'suspended', 'retired', 'lost']).optional(),
  vehicle_id: z.string().trim().optional(),
});

export type CardAction = 'enrolled' | 'created' | 'paired' | 'unpaired' | 'replaced' | 'status_changed' | 'edited' | 'vehicle_changed';

export interface CardEventInput {
  action: CardAction;
  fromStatus?: string | null;
  toStatus?: string | null;
  fromNfcUid?: string | null;
  toNfcUid?: string | null;
  reason?: string | null;
  details?: Record<string, unknown>;
}

/** Writes one history row. Call it on the same client as the change so both succeed or neither does. */
export async function recordCardEvent(client: Pool | PoolClient, cardUuid: string, admin: string, e: CardEventInput) {
  await client.query(
    `INSERT INTO card_events (card_uuid, action, from_status, to_status, from_nfc_uid, to_nfc_uid, reason, admin_username, details)
     VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)`,
    [
      cardUuid,
      e.action,
      e.fromStatus ?? null,
      e.toStatus ?? null,
      e.fromNfcUid ?? null,
      e.toNfcUid ?? null,
      e.reason ?? null,
      admin,
      JSON.stringify(e.details ?? {}),
    ],
  );
}

/** Runs fn in a transaction. A thrown error rolls everything back, including the history rows. */
export async function inTransaction<T>(pool: Pool, fn: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const out = await fn(client);
    await client.query('COMMIT');
    return out;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw err;
  } finally {
    client.release();
  }
}

/** A deliberate refusal that becomes an HTTP answer. */
export class CardError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

const CARD_COLUMNS = 'card_uuid, nfc_uid, serial, label, status, enrolled_at, notes, vehicle_id, updated_at';

/** Trips still open on this card: the passenger may still be aboard with it. */
export async function openTripCount(client: Pool | PoolClient, cardUuid: string): Promise<number> {
  const { rows } = await client.query(
    `SELECT count(*)::int AS n FROM trip_status
     WHERE card_uuid = $1::uuid AND current_state IN ${OPEN_STATES}
       AND effective_at > now() - interval '${OPEN_TRIP_WINDOW_HOURS} hours'`,
    [cardUuid],
  );
  return rows[0].n as number;
}

export interface StatusChange {
  to: CardStatus;
  reason?: string | null;
  confirm?: boolean;
}

/**
 * The single place a card's status changes. Checks the move is allowed, asks for a reason when one is
 * needed, warns about open trips unless confirmed, then writes the change and its history together.
 * Returns the updated card. Throws CardError for anything the admin has to fix first.
 */
export async function changeStatus(client: PoolClient, cardUuid: string, who: string, change: StatusChange) {
  const { rows } = await client.query(`SELECT ${CARD_COLUMNS} FROM cards WHERE card_uuid = $1::uuid FOR UPDATE`, [cardUuid]);
  const card = rows[0];
  if (!card) throw new CardError(404, 'Card not found');
  const from = card.status as CardStatus;

  if (from === change.to) return card;
  const move = checkMove(from, change.to, card.nfc_uid !== null);
  if (!move.ok) throw new CardError(409, move.error);

  const reason = change.reason?.trim() || null;
  if (NEEDS_REASON.includes(change.to) && !reason) {
    throw new CardError(400, `Say why this card is being marked ${change.to}.`, { needs_reason: true });
  }
  if (WARNS_OPEN_TRIPS.includes(change.to) && !change.confirm) {
    const open = await openTripCount(client, cardUuid);
    if (open > 0) {
      throw new CardError(409, `This card has ${open} open trip${open === 1 ? '' : 's'}. Confirm to continue.`, { open_trips: open });
    }
  }

  const keepChip = change.to !== 'unpaired';
  const updated = await client.query(
    `UPDATE cards SET status = $2, nfc_uid = CASE WHEN $3::boolean THEN nfc_uid ELSE NULL END, updated_at = now()
     WHERE card_uuid = $1::uuid RETURNING ${CARD_COLUMNS}`,
    [cardUuid, change.to, keepChip],
  );
  await recordCardEvent(client, cardUuid, who, {
    action: 'status_changed',
    fromStatus: from,
    toStatus: change.to,
    fromNfcUid: card.nfc_uid,
    toNfcUid: updated.rows[0].nfc_uid,
    reason,
  });
  return updated.rows[0];
}

/** Turns a CardError into its HTTP answer; anything else is a real fault and is rethrown. */
function sendCardError(res: Response, err: unknown): void {
  if (err instanceof CardError) {
    return void res.status(err.status).json({ error: err.message, ...err.extra });
  }
  throw err;
}

type CardFilter = { search?: string; status?: string; vehicle_id?: string };

/** The WHERE clause the list, the export and bulk-by-filter all share, so they can never disagree. */
function cardWhere(f: CardFilter): { sql: string; params: unknown[] } {
  const clauses: string[] = ['1=1'];
  const params: unknown[] = [];
  if (f.search) {
    params.push(`%${f.search.replace(/[\\%_]/g, '\\$&')}%`);
    const p = `$${params.length}`;
    clauses.push(`(c.nfc_uid ILIKE ${p} OR c.serial ILIKE ${p} OR c.card_uuid::text ILIKE ${p} OR c.label ILIKE ${p} OR c.vehicle_id ILIKE ${p} OR v.plate_number ILIKE ${p})`);
  }
  if (f.status) {
    params.push(f.status);
    clauses.push(`c.status = $${params.length}`);
  }
  if (f.vehicle_id) {
    if (f.vehicle_id.toLowerCase() === 'unassigned') {
      clauses.push('c.vehicle_id IS NULL');
    } else {
      params.push(f.vehicle_id);
      clauses.push(`c.vehicle_id = $${params.length}`);
    }
  }
  return { sql: clauses.join(' AND '), params };
}

const CARD_ORDER = `ORDER BY (c.vehicle_id IS NOT NULL) DESC, c.vehicle_id ASC, regexp_replace(c.serial, '[0-9]+$', '') ASC NULLS LAST,
                (substring(c.serial from '[0-9]+$'))::numeric ASC NULLS LAST, c.serial ASC, c.enrolled_at DESC`;

const adminName = (res: Response): string => (res.locals.auth as { username: string }).username;

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
      const card = await inTransaction(pool, async (client) => {
        const { rows } = await client.query(
          `INSERT INTO cards (nfc_uid, card_uuid, label, notes, vehicle_id, serial)
           VALUES ($1, COALESCE($2::uuid, gen_random_uuid()), $3, $4, $5, $6)
           RETURNING ${CARD_COLUMNS}`,
          [nfc_uid, card_uuid ?? null, label ?? null, notes ?? null, vehicle_id ?? null, serial ?? null],
        );
        await recordCardEvent(client, rows[0].card_uuid, adminName(res), {
          action: 'enrolled',
          toStatus: rows[0].status,
          toNfcUid: nfc_uid,
          details: { vehicle_id: vehicle_id ?? null, label: label ?? null },
        });
        return rows[0];
      });
      res.status(201).json(card);
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
      const rows = await inTransaction(pool, async (client) => {
        const made = await client.query(
          `INSERT INTO cards (serial, label, status, vehicle_id)
           SELECT s, s, 'unpaired', $2 FROM unnest($1::text[]) WITH ORDINALITY AS t(s, n)
           ORDER BY n
           RETURNING card_uuid, serial, label, status, vehicle_id`,
          [serials, vehicle_id ?? null],
        );
        await client.query(
          `INSERT INTO card_events (card_uuid, action, to_status, admin_username, details)
           SELECT x, 'created', 'unpaired', $2, jsonb_build_object('batch_size', $3::int)
           FROM unnest($1::uuid[]) AS x`,
          [made.rows.map((r) => r.card_uuid), adminName(res), made.rows.length],
        );
        return made.rows;
      });
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
      const paired = await inTransaction(pool, async (client) => {
        const { rows } = await client.query(
          `UPDATE cards SET nfc_uid = $1, status = 'active', updated_at = now()
           WHERE card_uuid = $2::uuid AND status = 'unpaired' AND nfc_uid IS NULL
           RETURNING ${CARD_COLUMNS}`,
          [body.data.nfc_uid, uuid.data],
        );
        if (rows.length === 1) {
          await recordCardEvent(client, uuid.data, adminName(res), {
            action: 'paired',
            fromStatus: 'unpaired',
            toStatus: 'active',
            toNfcUid: body.data.nfc_uid,
          });
        }
        return rows[0] ?? null;
      });
      if (paired) return void res.json(paired);
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

  // POST /api/cards/:card_uuid/replace - Admin swaps the chip on a card that already has one (chip lost or broken),
  // keeping the printed QR, serial and history. One step, so the card is never left without a chip.
  app.post('/api/cards/:card_uuid/replace', admin, async (req: Request, res: Response) => {
    const uuid = z.string().uuid().safeParse(req.params.card_uuid);
    const body = pairSchema.extend({ reason: reasonSchema.nullish(), confirm: z.boolean().optional() }).safeParse(req.body);
    if (!uuid.success || !body.success) return void res.status(400).json({ error: 'Invalid request' });
    const newUid = body.data.nfc_uid;

    try {
      const card = await inTransaction(pool, async (client) => {
        const { rows } = await client.query(`SELECT ${CARD_COLUMNS} FROM cards WHERE card_uuid = $1::uuid FOR UPDATE`, [uuid.data]);
        const before = rows[0];
        if (!before) throw new CardError(404, 'Card not found');
        if (before.nfc_uid === null) throw new CardError(409, 'This card has no chip yet. Pair one instead of replacing.');
        if (before.status === 'retired') throw new CardError(409, 'A retired card cannot get a new chip. Restore it first.');
        if (before.nfc_uid === newUid) throw new CardError(409, 'That chip is already the one on this card.');
        if (!body.data.confirm) {
          const open = await openTripCount(client, uuid.data);
          if (open > 0) throw new CardError(409, `This card has ${open} open trip${open === 1 ? '' : 's'}. Confirm to continue.`, { open_trips: open });
        }
        // A lost card that gets a new chip is back in service. A suspended one stays suspended on purpose.
        const status = before.status === 'lost' ? 'active' : before.status;
        const updated = await client.query(
          `UPDATE cards SET nfc_uid = $2, status = $3, updated_at = now() WHERE card_uuid = $1::uuid RETURNING ${CARD_COLUMNS}`,
          [uuid.data, newUid, status],
        );
        await recordCardEvent(client, uuid.data, adminName(res), {
          action: 'replaced',
          fromStatus: before.status,
          toStatus: status,
          fromNfcUid: before.nfc_uid,
          toNfcUid: newUid,
          reason: body.data.reason?.trim() || null,
        });
        return updated.rows[0];
      });
      res.json(card);
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        return void res.status(409).json({ error: 'That NFC card is already paired to another card.' });
      }
      sendCardError(res, err);
    }
  });

  // POST /api/cards/:card_uuid/unpair - Admin clears a wrong pairing so the card can be paired again
  app.post('/api/cards/:card_uuid/unpair', admin, async (req: Request, res: Response) => {
    const uuid = z.string().uuid().safeParse(req.params.card_uuid);
    if (!uuid.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const body = statusChangeSchema.safeParse(req.body ?? {});
    if (!body.success) return void res.status(400).json({ error: 'Invalid request' });
    try {
      const card = await inTransaction(pool, async (client) => {
        const { rows } = await client.query('SELECT status, nfc_uid FROM cards WHERE card_uuid = $1::uuid FOR UPDATE', [uuid.data]);
        if (rows.length === 0 || rows[0].nfc_uid === null) throw new CardError(404, 'Card not found or not paired');
        if (!body.data.confirm) {
          const open = await openTripCount(client, uuid.data);
          if (open > 0) throw new CardError(409, `This card has ${open} open trip${open === 1 ? '' : 's'}. Confirm to continue.`, { open_trips: open });
        }
        const updated = await client.query(
          `UPDATE cards SET nfc_uid = NULL, status = 'unpaired', updated_at = now()
           WHERE card_uuid = $1::uuid RETURNING ${CARD_COLUMNS}`,
          [uuid.data],
        );
        await recordCardEvent(client, uuid.data, adminName(res), {
          action: 'unpaired',
          fromStatus: rows[0].status,
          toStatus: 'unpaired',
          fromNfcUid: rows[0].nfc_uid,
          reason: body.data.reason?.trim() || null,
        });
        return updated.rows[0];
      });
      res.json(card);
    } catch (err) {
      sendCardError(res, err);
    }
  });

  // GET /api/cards - Admin lists cards, with search, status filtering, and vehicle filtering
  app.get('/api/cards', admin, async (req: Request, res: Response) => {
    const q = cardsQuery.safeParse(req.query);
    if (!q.success) {
      return void res.status(400).json({ error: 'Invalid query parameters' });
    }
    const { page, limit, search, status, vehicle_id } = q.data;

    const where = cardWhere({ search, status, vehicle_id });
    const params = where.params;

    const whereSql = where.sql;
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
       ${CARD_ORDER}
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

  /** Resolves a target (list or filter) to card ids, in registry order. Throws CardError past the bulk limit. */
  async function resolveTargets(client: Pool | PoolClient, t: z.infer<typeof targetSchema>): Promise<string[]> {
    if (t.card_uuids) {
      const { rows } = await client.query(
        `SELECT c.card_uuid FROM cards c LEFT JOIN vehicles v ON v.id = c.vehicle_id
         WHERE c.card_uuid = ANY($1::uuid[]) ${CARD_ORDER}`,
        [[...new Set(t.card_uuids)]],
      );
      return rows.map((r) => r.card_uuid as string);
    }
    const where = cardWhere(t.filter ?? {});
    const { rows } = await client.query(
      `SELECT c.card_uuid FROM cards c LEFT JOIN vehicles v ON v.id = c.vehicle_id
       WHERE ${where.sql} ${CARD_ORDER} LIMIT ${BULK_LIMIT + 1}`,
      where.params,
    );
    if (rows.length > BULK_LIMIT) {
      throw new CardError(400, `That matches more than ${BULK_LIMIT} cards. Narrow the filter and try again.`);
    }
    return rows.map((r) => r.card_uuid as string);
  }

  // POST /api/cards/bulk - Admin changes the status or vehicle of many cards at once. All or nothing:
  // if any one card cannot make the move, nothing changes and the answer says which cards were the problem.
  app.post('/api/cards/bulk', admin, async (req: Request, res: Response) => {
    const parsed = bulkSchema.safeParse(req.body);
    if (!parsed.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: parsed.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }
    const b = parsed.data;
    const who = adminName(res);
    try {
      const result = await inTransaction(pool, async (client) => {
        const ids = await resolveTargets(client, b);
        if (ids.length === 0) throw new CardError(404, 'No cards matched.');

        const problems: { card_uuid: string; serial: string | null; error: string; open_trips?: number }[] = [];
        const done: string[] = [];
        for (const id of ids) {
          // Each card runs in its own savepoint, so one refusal does not abort the transaction and we can
          // collect every problem before rolling the whole thing back.
          await client.query('SAVEPOINT one_card');
          try {
            if (b.action === 'status') {
              await changeStatus(client, id, who, { to: b.status as CardStatus, reason: b.reason, confirm: b.confirm });
            } else {
              const cur = await client.query('SELECT vehicle_id FROM cards WHERE card_uuid = $1::uuid FOR UPDATE', [id]);
              const from = cur.rows[0].vehicle_id as string | null;
              const to = b.vehicle_id ?? null;
              if (from !== to) {
                await client.query('UPDATE cards SET vehicle_id = $2, updated_at = now() WHERE card_uuid = $1::uuid', [id, to]);
                await recordCardEvent(client, id, who, { action: 'vehicle_changed', reason: b.reason?.trim() || null, details: { vehicle_id: { from, to } } });
              }
            }
            await client.query('RELEASE SAVEPOINT one_card');
            done.push(id);
          } catch (err) {
            await client.query('ROLLBACK TO SAVEPOINT one_card');
            await client.query('RELEASE SAVEPOINT one_card');
            const serial = (await client.query('SELECT serial FROM cards WHERE card_uuid = $1::uuid', [id])).rows[0]?.serial ?? null;
            if (err instanceof CardError) {
              problems.push({ card_uuid: id, serial, error: err.message, ...(typeof err.extra.open_trips === 'number' ? { open_trips: err.extra.open_trips } : {}) });
            } else if ((err as { code?: string }).code === '23503') {
              throw new CardError(400, `Vehicle "${b.vehicle_id}" does not exist.`);
            } else {
              throw err;
            }
          }
        }
        if (problems.length > 0) {
          const openTrips = problems.reduce((n, p) => n + (p.open_trips ?? 0), 0);
          throw new CardError(409, `${problems.length} of ${ids.length} cards could not be changed. Nothing was changed.`, {
            problems,
            ...(openTrips > 0 ? { open_trips: openTrips } : {}),
          });
        }
        return { changed: done.length };
      });
      res.json(result);
    } catch (err) {
      sendCardError(res, err);
    }
  });

  // POST /api/cards/lookup - Admin fetches the full cards for a list or a filter (used to reprint existing cards)
  app.post('/api/cards/lookup', admin, async (req: Request, res: Response) => {
    const parsed = lookupSchema.safeParse(req.body);
    if (!parsed.success) return void res.status(400).json({ error: 'Invalid request' });
    try {
      const ids = await resolveTargets(pool, parsed.data);
      const { rows } = ids.length
        ? await pool.query(
            `SELECT c.card_uuid, c.nfc_uid, c.serial, c.label, c.status, c.vehicle_id
             FROM cards c LEFT JOIN vehicles v ON v.id = c.vehicle_id
             WHERE c.card_uuid = ANY($1::uuid[]) ${CARD_ORDER}`,
            [ids],
          )
        : { rows: [] };
      res.json({ cards: rows, count: rows.length });
    } catch (err) {
      sendCardError(res, err);
    }
  });

  // GET /api/cards/export - The card list as a CSV, with the same filters as the registry
  app.get('/api/cards/export', admin, async (req: Request, res: Response) => {
    const q = exportQuery.safeParse(req.query);
    if (!q.success) return void res.status(400).json({ error: 'Invalid query parameters' });
    const where = cardWhere(q.data);
    const { rows } = await pool.query(
      `SELECT c.serial, c.status, c.nfc_uid, c.vehicle_id, v.plate_number, c.label, c.notes, c.card_uuid,
              c.enrolled_at, c.updated_at,
              (SELECT max(e.occurred_at) FROM events e WHERE e.card_uuid = c.card_uuid) AS last_used_at
       FROM cards c LEFT JOIN vehicles v ON v.id = c.vehicle_id
       WHERE ${where.sql} ${CARD_ORDER}
       LIMIT ${EXPORT_ROW_LIMIT + 1}`,
      where.params,
    );
    const truncated = rows.length > EXPORT_ROW_LIMIT;
    const data = truncated ? rows.slice(0, EXPORT_ROW_LIMIT) : rows;
    const iso = (d: unknown) => (d instanceof Date ? d.toISOString() : '');
    const header = ['serial', 'status', 'nfc_uid', 'vehicle', 'plate', 'label', 'notes', 'card_uuid', 'enrolled_at', 'updated_at', 'last_used_at'];
    const lines = data.map((r) =>
      [r.serial, r.status, r.nfc_uid, r.vehicle_id, r.plate_number, r.label, r.notes, r.card_uuid, iso(r.enrolled_at), iso(r.updated_at), iso(r.last_used_at)]
        .map(csvText)
        .join(','),
    );
    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', 'attachment; filename="toms_cards.csv"');
    res.setHeader('Access-Control-Expose-Headers', 'X-Export-Truncated, Content-Disposition');
    res.setHeader('X-Export-Truncated', String(truncated));
    res.send([header.join(','), ...lines].join('\r\n') + '\r\n');
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
    const { status, vehicle_id, reason, confirm } = parseRes.data;
    const body = parseRes.data as Record<string, unknown>;
    const hasLabel = 'label' in body;
    const hasNotes = 'notes' in body;
    const hasVehicleUpdate = 'vehicle_id' in body;
    // An empty box means "clear it".
    const blankToNull = (v: string | null | undefined) => (v === undefined || v === null || v.trim() === '' ? null : v.trim());
    const who = adminName(res);

    try {
      const card = await inTransaction(pool, async (client) => {
        const found = await client.query(`SELECT ${CARD_COLUMNS} FROM cards WHERE card_uuid = $1::uuid FOR UPDATE`, [cardUuid]);
        const before = found.rows[0];
        if (!before) throw new CardError(404, 'Card not found');

        // The status goes first: it can refuse (409 / needs a reason / open trips) before anything else is saved.
        if (status && status !== before.status) {
          await changeStatus(client, cardUuid, who, { to: status, reason, confirm });
        }

        const newLabel = hasLabel ? blankToNull(parseRes.data.label) : before.label;
        const newNotes = hasNotes ? blankToNull(parseRes.data.notes) : before.notes;
        const newVehicle = hasVehicleUpdate ? (vehicle_id ?? null) : before.vehicle_id;
        const edits: Record<string, unknown> = {};
        if (newLabel !== before.label) edits.label = { from: before.label, to: newLabel };
        if (newNotes !== before.notes) edits.notes = { from: before.notes, to: newNotes };

        const updated = await client.query(
          `UPDATE cards SET label = $2, notes = $3, vehicle_id = $4, updated_at = now()
           WHERE card_uuid = $1::uuid RETURNING ${CARD_COLUMNS}`,
          [cardUuid, newLabel, newNotes, newVehicle],
        );
        if (Object.keys(edits).length > 0) await recordCardEvent(client, cardUuid, who, { action: 'edited', details: edits });
        if (newVehicle !== before.vehicle_id) {
          await recordCardEvent(client, cardUuid, who, {
            action: 'vehicle_changed',
            details: { vehicle_id: { from: before.vehicle_id, to: newVehicle } },
          });
        }
        return updated.rows[0];
      });
      res.json(card);
    } catch (err) {
      const code = (err as { code?: string }).code;
      if (code === '23503') {
        return void res.status(400).json({ error: `Vehicle "${vehicle_id}" does not exist.` });
      }
      if (code === '23514') {
        return void res.status(409).json({ error: 'A card with no NFC chip paired cannot be made active or suspended. Pair it first.' });
      }
      sendCardError(res, err);
    }
  });

  // POST /api/cards/:card_uuid/status - Admin changes status through the lifecycle rules (lost, retired, restore ...)
  app.post('/api/cards/:card_uuid/status', admin, async (req: Request, res: Response) => {
    const uuid = z.string().uuid().safeParse(req.params.card_uuid);
    const body = statusChangeSchema.extend({ status: z.enum(CARD_STATUSES) }).safeParse(req.body);
    if (!uuid.success || !body.success) return void res.status(400).json({ error: 'Invalid request' });
    try {
      const card = await inTransaction(pool, (client) =>
        changeStatus(client, uuid.data, adminName(res), { to: body.data.status, reason: body.data.reason, confirm: body.data.confirm }),
      );
      res.json(card);
    } catch (err) {
      sendCardError(res, err);
    }
  });

  // GET /api/cards/:card_uuid/history - Everything that happened to a card, newest first
  app.get('/api/cards/:card_uuid/history', admin, async (req: Request, res: Response) => {
    const uuid = z.string().uuid().safeParse(req.params.card_uuid);
    if (!uuid.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const exists = await pool.query('SELECT 1 FROM cards WHERE card_uuid = $1::uuid', [uuid.data]);
    if (exists.rowCount === 0) return void res.status(404).json({ error: 'Card not found' });
    const { rows } = await pool.query(
      `SELECT id, action, from_status, to_status, from_nfc_uid, to_nfc_uid, reason, admin_username, details, at
       FROM card_events WHERE card_uuid = $1::uuid ORDER BY at DESC, id DESC LIMIT 500`,
      [uuid.data],
    );
    res.json({ events: rows });
  });

  // DELETE /api/cards/:card_uuid - Admin retires a card
  app.delete('/api/cards/:card_uuid', admin, async (req: Request, res: Response) => {
    const uuidRes = z.string().uuid().safeParse(req.params.card_uuid);
    if (!uuidRes.success) return void res.status(400).json({ error: 'Invalid card UUID' });
    const cardUuid = uuidRes.data;
    const body = statusChangeSchema.safeParse(req.body ?? {});
    if (!body.success) return void res.status(400).json({ error: 'Invalid request' });
    try {
      const current = await pool.query('SELECT status FROM cards WHERE card_uuid = $1::uuid', [cardUuid]);
      if (current.rows.length === 0) return void res.status(404).json({ error: 'Card not found or already retired' });
      if (current.rows[0].status === 'retired') return void res.status(404).json({ error: 'Card not found or already retired' });
      await inTransaction(pool, (client) =>
        changeStatus(client, cardUuid, adminName(res), {
          to: 'retired',
          // A plain DELETE has always retired a card without asking why, so keep that working but say so in the history.
          reason: body.data.reason ?? 'Retired through the API',
          confirm: body.data.confirm,
        }),
      );
      res.json({ status: 'success', message: 'Card retired' });
    } catch (err) {
      sendCardError(res, err);
    }
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
