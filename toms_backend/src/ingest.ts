import type { Pool, PoolClient } from 'pg';
import { z } from 'zod';

export const CHANNELS = ['data_a', 'data_b', 'sms', 'late_sync'] as const;
export type Channel = (typeof CHANNELS)[number];

export const MAX_BATCH = 500;

const MAX_MILLIS = 4_102_444_800_000; // 2100-01-01
const MIN_PLAUSIBLE_MILLIS = Date.UTC(2020, 0, 1);
const FUTURE_TOLERANCE_MS = 5 * 60 * 1000;

export const envelopeSchema = z.object({
  events: z.array(z.unknown()).min(1).max(MAX_BATCH),
  delivery_channel: z.enum(CHANNELS).default('data_a'),
});

// What the phone's outbox sends for each row (see TripRepository on the Android side).
const itemSchema = z.object({
  event_id: z.guid(),
  device_id: z.string().trim().min(1).max(64),
  local_seq: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  type: z.string().regex(/^[a-z][a-z0-9_]{0,39}$/),
  created_at_millis: z.number().int().min(0).max(MAX_MILLIS),
  payload: z.record(z.string(), z.unknown()),
});

const nfcUid = z
  .string()
  .transform((s) => s.replace(/[:\s-]/g, '').toUpperCase())
  .pipe(z.string().regex(/^[0-9A-F]{8,20}$/, 'must be 8 to 20 hex digits'));

const money = z.number().int().min(0).max(10_000_000);
const shortText = z.string().min(1).max(64);
const millis = z.number().int().min(0).max(MAX_MILLIS);

const tripCreatedPayload = z
  .object({
    tripId: z.guid(),
    cardUuid: z.guid(),
    nfcUid,
    boardingStopId: shortText,
    declaredDestinationStopId: shortText,
    actualDestinationStopId: shortText.nullish(),
    discountCategoryId: shortText.nullish(),
    computedFareCentavos: money,
    fareCentavos: money,
    discountCentavos: money,
    fareVersion: z.number().int().min(0).max(1_000_000),
    overrideReason: z.string().max(500).nullish(),
    gpsLat: z.number().min(-90).max(90).nullish(),
    gpsLon: z.number().min(-180).max(180).nullish(),
    gpsAccuracyMeters: z.number().min(0).max(100_000).nullish(),
    gpsFixAtMillis: millis.nullish(),
  })
  .refine(
    (p) => {
      const present = [p.gpsLat, p.gpsLon, p.gpsFixAtMillis].map((v) => v != null);
      const allOrNone = present.every(Boolean) || present.every((x) => !x);
      return allOrNone && (p.gpsAccuracyMeters == null || present[0]);
    },
    { message: 'GPS fix needs latitude, longitude and fix time together' },
  );

const cardStatePayload = z.object({
  nfcUid,
  cardUuid: z.guid(),
  tripId: z.guid().nullish(),
  state: z.enum(['AVAILABLE', 'ASSIGNED_UNPAID', 'ASSIGNED_PAID', 'RETURNED', 'RETURNED_UNPAID', 'LOST']),
});

export interface Rejection {
  index: number;
  event_id: string | null;
  reason: string;
}

export interface IngestResult {
  /** Newly stored by this request. */
  accepted: string[];
  /** Already stored with identical content. The server has them, so the phone should treat them as delivered. */
  duplicates: string[];
  /** Not stored and never will be as sent. The phone must not retry them unchanged. */
  rejected: Rejection[];
}

type Columns = Record<string, unknown>;

interface Prepared {
  index: number;
  eventId: string;
  deviceId: string;
  eventType: string;
  payload: Record<string, unknown>;
  columns: Columns;
}

function describe(error: z.ZodError): string {
  return error.issues
    .slice(0, 3)
    .map((i) => (i.path.length ? `${i.path.join('.')}: ${i.message}` : i.message))
    .join('; ');
}

function prepare(raw: unknown, index: number, channel: Channel, nowMs: number): Prepared | Rejection {
  const rawId = (raw as { event_id?: unknown } | null)?.event_id;
  const eventIdForReport = typeof rawId === 'string' ? rawId.slice(0, 64) : null;
  const fail = (reason: string): Rejection => ({ index, event_id: eventIdForReport, reason });

  const item = itemSchema.safeParse(raw);
  if (!item.success) return fail(describe(item.error));
  const e = item.data;

  const occurredMs = e.created_at_millis;
  const columns: Columns = {
    event_id: e.event_id,
    device_id: e.device_id,
    local_seq: e.local_seq,
    event_type: e.type,
    occurred_at: new Date(occurredMs).toISOString(),
    clock_suspect: occurredMs < MIN_PLAUSIBLE_MILLIS || occurredMs > nowMs + FUTURE_TOLERANCE_MS,
    delivery_channel: channel,
  };

  if (e.type === 'trip_created') {
    const p = tripCreatedPayload.safeParse(e.payload);
    if (!p.success) return fail(describe(p.error));
    const t = p.data;
    Object.assign(columns, {
      trip_id: t.tripId,
      card_uuid: t.cardUuid,
      nfc_uid: t.nfcUid,
      boarding_stop_id: t.boardingStopId,
      declared_destination_stop_id: t.declaredDestinationStopId,
      actual_destination_stop_id: t.actualDestinationStopId ?? null,
      discount_category_id: t.discountCategoryId ?? null,
      computed_fare_centavos: t.computedFareCentavos,
      fare_centavos: t.fareCentavos,
      discount_centavos: t.discountCentavos,
      fare_version: t.fareVersion,
      override_reason: t.overrideReason ?? null,
      gps_lat: t.gpsLat ?? null,
      gps_lon: t.gpsLon ?? null,
      gps_accuracy_m: t.gpsAccuracyMeters ?? null,
      gps_fix_at: t.gpsFixAtMillis == null ? null : new Date(t.gpsFixAtMillis).toISOString(),
    });
  } else if (e.type === 'card_state_changed') {
    const p = cardStatePayload.safeParse(e.payload);
    if (!p.success) return fail(describe(p.error));
    Object.assign(columns, {
      trip_id: p.data.tripId ?? null,
      card_uuid: p.data.cardUuid,
      nfc_uid: p.data.nfcUid,
      card_state: p.data.state,
    });
  }
  // Any other type is kept as it arrived in the payload column, so a newer app version
  // never loses data against an older server.

  return { index, eventId: e.event_id, deviceId: e.device_id, eventType: e.type, payload: e.payload, columns };
}

type Outcome = 'accepted' | 'duplicate' | 'conflict';

async function insertOne(client: PoolClient, row: Prepared): Promise<Outcome> {
  const names = [...Object.keys(row.columns), 'payload'];
  const values = [...Object.values(row.columns), JSON.stringify(row.payload)];
  const deviceParam = names.indexOf('device_id') + 1;
  const placeholders = names.map((n, i) => (n === 'payload' ? `$${i + 1}::jsonb` : `$${i + 1}`));

  // The vehicle comes from device enrollment, never from the phone's own claim.
  const inserted = await client.query(
    `INSERT INTO events (${names.join(', ')}, vehicle_id)
     VALUES (${placeholders.join(', ')},
             (SELECT vehicle_id FROM devices WHERE device_id = $${deviceParam} AND revoked_at IS NULL))
     ON CONFLICT (event_id) DO NOTHING
     RETURNING event_id`,
    values,
  );
  if (inserted.rowCount === 1) return 'accepted';

  // Same event_id already stored. Identical content is a harmless retry; anything else means an
  // id was reused, which must not be silently acknowledged.
  const same = await client.query(
    `SELECT 1 FROM events
     WHERE event_id = $1 AND device_id = $2 AND event_type = $3 AND payload = $4::jsonb`,
    [row.eventId, row.deviceId, row.eventType, JSON.stringify(row.payload)],
  );
  return same.rowCount === 1 ? 'duplicate' : 'conflict';
}

/** Integrity (23) and data (22) errors are about one bad row. Anything else is infrastructure. */
function isRowError(err: unknown): boolean {
  const code = (err as { code?: unknown })?.code;
  return typeof code === 'string' && (code.startsWith('22') || code.startsWith('23'));
}

export async function ingestEvents(
  pool: Pool,
  rawEvents: unknown[],
  channel: Channel = 'data_a',
  nowMs: number = Date.now(),
): Promise<IngestResult> {
  const accepted: { index: number; id: string }[] = [];
  const duplicates: { index: number; id: string }[] = [];
  const rejected: Rejection[] = [];

  const valid: Prepared[] = [];
  rawEvents.forEach((raw, index) => {
    const prepared = prepare(raw, index, channel, nowMs);
    if ('reason' in prepared) rejected.push(prepared);
    else valid.push(prepared);
  });

  if (valid.length > 0) {
    // A fixed insert order stops two overlapping batches that share events from deadlocking.
    const ordered = [...valid].sort((a, b) => (a.eventId < b.eventId ? -1 : a.eventId > b.eventId ? 1 : 0));

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      for (const row of ordered) {
        await client.query('SAVEPOINT ev');
        try {
          const outcome = await insertOne(client, row);
          await client.query('RELEASE SAVEPOINT ev');
          if (outcome === 'accepted') accepted.push({ index: row.index, id: row.eventId });
          else if (outcome === 'duplicate') duplicates.push({ index: row.index, id: row.eventId });
          else {
            rejected.push({
              index: row.index,
              event_id: row.eventId,
              reason: 'event_id already used with different content',
            });
          }
        } catch (err) {
          if (!isRowError(err)) throw err;
          await client.query('ROLLBACK TO SAVEPOINT ev');
          rejected.push({
            index: row.index,
            event_id: row.eventId,
            reason: `database rejected the event (code ${(err as { code: string }).code})`,
          });
        }
      }
      await client.query('COMMIT');
    } catch (err) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw err;
    } finally {
      client.release();
    }
  }

  const byIndex = (a: { index: number }, b: { index: number }) => a.index - b.index;
  return {
    accepted: accepted.sort(byIndex).map((x) => x.id),
    duplicates: duplicates.sort(byIndex).map((x) => x.id),
    rejected: rejected.sort(byIndex),
  };
}
