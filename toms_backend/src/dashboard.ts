import type { Express, Request, RequestHandler, Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';

/** An open trip older than this is treated as abandoned, not as a passenger still aboard. */
export const OPEN_TRIP_WINDOW_HOURS = 24;
export const EXPORT_ROW_LIMIT = 50_000;
const MAX_PAGE_SIZE = 200;
const OPEN_STATES = "('ASSIGNED_UNPAID', 'ASSIGNED_PAID')";

export async function assertTimezone(pool: Pool, tz: string): Promise<string> {
  const { rowCount } = await pool.query('SELECT 1 FROM pg_timezone_names WHERE name = $1', [tz]);
  if (!rowCount) throw new Error(`REPORT_TIMEZONE "${tz}" is not a known time zone (for example Asia/Manila)`);
  return tz;
}

/**
 * Live status per vehicle, derived from trips. Occupancy is the number of cards currently out
 * (assigned, not yet returned), not a seat map. Position is the newest GPS fix the vehicle's phone
 * sent, with its age so the dashboard can show that it may be old.
 */
export async function fleetStatus(pool: Pool, tz: string, vehicleId?: string) {
  const { rows } = await pool.query(
    `SELECT
       v.id AS vehicle_id, v.plate_number, v.status, v.max_capacity,
       rt.name AS assigned_route_name,
       c.name  AS assigned_conductor_name,
       COALESCE(o.open_trips, 0)  AS occupancy_now,
       NULL::text                 AS seat_map,
       s.last_received            AS last_updated,
       g.gps_lat AS current_lat, g.gps_lon AS current_lon,
       g.gps_fix_at, g.gps_accuracy_m,
       COALESCE(r.revenue_centavos, 0) AS daily_revenue
     FROM vehicles v
     LEFT JOIN routes rt     ON rt.id = v.assigned_route_id
     LEFT JOIN conductors c  ON c.id  = v.assigned_conductor_id
     LEFT JOIN (
       SELECT vehicle_id, sum(passenger_count) AS open_trips
       FROM trip_status
       WHERE current_state IN ${OPEN_STATES}
         AND effective_at > now() - interval '${OPEN_TRIP_WINDOW_HOURS} hours'
       GROUP BY vehicle_id
     ) o ON o.vehicle_id = v.id
     LEFT JOIN (
       SELECT vehicle_id, max(received_at) AS last_received FROM events GROUP BY vehicle_id
     ) s ON s.vehicle_id = v.id
     LEFT JOIN LATERAL (
       SELECT e.gps_lat, e.gps_lon, e.gps_fix_at, e.gps_accuracy_m
       FROM events e
       WHERE e.vehicle_id = v.id AND e.gps_lat IS NOT NULL
       ORDER BY e.gps_fix_at DESC LIMIT 1
     ) g ON true
     LEFT JOIN (
       SELECT vehicle_id, sum(fare_centavos) AS revenue_centavos
       FROM trip_status
       WHERE paid AND (effective_at AT TIME ZONE $1)::date = (now() AT TIME ZONE $1)::date
       GROUP BY vehicle_id
     ) r ON r.vehicle_id = v.id
     WHERE ($2::text IS NULL OR v.id = $2)
     ORDER BY v.id`,
    [tz, vehicleId ?? null],
  );
  return rows;
}

/** Escapes LIKE wildcards so a search for "50%" finds "50%" and not everything. */
export function likePattern(term: string): string {
  return `%${term.replace(/[\\%_]/g, '\\$&')}%`;
}

/** Quotes a text cell and defuses spreadsheet formulas (a leading = + - @ tab or CR). */
export function csvText(value: unknown): string {
  if (value === null || value === undefined) return '';
  let s = String(value);
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`;
  return `"${s.replace(/"/g, '""')}"`;
}

function csvNumber(value: unknown): string {
  return value === null || value === undefined ? '' : String(value);
}

const pesos = (centavos: unknown) => (centavos == null ? '' : (Number(centavos) / 100).toFixed(2));
const iso = (d: unknown) => (d instanceof Date ? d.toISOString() : '');

const AUDIT_SEARCH_FIELDS = [
  'vehicle_id', 'device_id', 'event_type', 'nfc_uid', 'card_uuid::text',
  'boarding_stop_id', 'declared_destination_stop_id', 'discount_category_id', 'override_reason',
];

const auditQuery = z.object({
  page: z.coerce.number().int().min(1).max(1_000_000).default(1),
  limit: z.coerce.number().int().min(1).max(MAX_PAGE_SIZE).default(50),
  search: z.string().trim().max(100).default(''),
  startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}/).optional().or(z.literal('')),
  endDate: z.string().regex(/^\d{4}-\d{2}-\d{2}/).optional().or(z.literal('')),
});

function auditWhere(q: z.infer<typeof auditQuery>, tz: string) {
  const clauses: string[] = [];
  const params: unknown[] = [];
  if (q.search) {
    params.push(likePattern(q.search));
    clauses.push(`(${AUDIT_SEARCH_FIELDS.map((f) => `${f} ILIKE $${params.length}`).join(' OR ')})`);
  }
  if (q.startDate) {
    params.push(q.startDate, tz);
    clauses.push(`(effective_at AT TIME ZONE $${params.length})::date >= $${params.length - 1}::date`);
  }
  if (q.endDate) {
    params.push(q.endDate, tz);
    clauses.push(`(effective_at AT TIME ZONE $${params.length})::date <= $${params.length - 1}::date`);
  }
  return { sql: clauses.length ? `WHERE ${clauses.join(' AND ')}` : '', params };
}

const analyticsQuery = z.object({ days: z.coerce.number().int().min(1).max(365).default(7) });

function badRequest(res: Response, error: z.ZodError) {
  res.status(400).json({
    error: 'Invalid request',
    issues: error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
  });
}

export function registerDashboardRoutes(
  app: Express,
  { pool, admin, tz }: { pool: Pool; admin: RequestHandler; tz: string },
) {
  app.get('/api/fleet/status', admin, async (_req, res) => {
    res.json(await fleetStatus(pool, tz));
  });

  app.get('/api/audit/logs', admin, async (req: Request, res: Response) => {
    const q = auditQuery.safeParse(req.query);
    if (!q.success) return void badRequest(res, q.error);
    const { page, limit } = q.data;
    const where = auditWhere(q.data, tz);

    const total = Number(
      (await pool.query(`SELECT count(*) AS n FROM events ${where.sql}`, where.params)).rows[0].n,
    );
    const { rows } = await pool.query(
      `SELECT id, event_id, event_type, effective_at AS timestamp, occurred_at, received_at, clock_suspect,
              vehicle_id, device_id, delivery_channel, local_seq,
              card_uuid, nfc_uid, card_state, trip_id,
              boarding_stop_id, declared_destination_stop_id, actual_destination_stop_id,
              discount_category_id, computed_fare_centavos, fare_centavos, discount_centavos,
              fare_version, override_reason, gps_lat, gps_lon, gps_accuracy_m, gps_fix_at,
              passenger_count, passengers
       FROM events ${where.sql}
       ORDER BY effective_at DESC, id DESC
       LIMIT $${where.params.length + 1} OFFSET $${where.params.length + 2}`,
      [...where.params, limit, (page - 1) * limit],
    );
    res.json({ metadata: { total, page, limit, totalPages: Math.ceil(total / limit) }, logs: rows });
  });

  app.get('/api/analytics/revenue', admin, async (req: Request, res: Response) => {
    const q = analyticsQuery.safeParse(req.query);
    if (!q.success) return void badRequest(res, q.error);
    const days = q.data.days;
    const from = `(now() AT TIME ZONE $1)::date - $2::int`;

    // Money comes from the lines so each passenger type is counted on its own. A trip whose fare was
    // overridden still reports its charged fare in total_revenue; the per-type split uses the lines.
    const daily = await pool.query(
      `SELECT to_char((t.effective_at AT TIME ZONE $1)::date, 'YYYY-MM-DD') AS date,
              COALESCE(sum(t.fare_centavos) FILTER (WHERE t.paid), 0)                              AS total_cents,
              COALESCE(sum(t.discount_centavos) FILTER (WHERE t.paid), 0)                          AS discount_given_cents,
              count(*) FILTER (WHERE t.paid)                                                       AS total_payments,
              count(*)                                                                             AS total_trips,
              COALESCE(sum(t.passenger_count), 0)                                                  AS total_boardings
       FROM trip_status t
       WHERE (t.effective_at AT TIME ZONE $1)::date >= ${from}
       GROUP BY 1 ORDER BY 1`,
      [tz, days],
    );
    const byCategory = await pool.query(
      `SELECT to_char((effective_at AT TIME ZONE $1)::date, 'YYYY-MM-DD') AS date,
              category, sum(fare_centavos) AS cents, sum(passengers) AS people
       FROM trip_lines
       WHERE paid AND (effective_at AT TIME ZONE $1)::date >= ${from}
       GROUP BY 1, 2`,
      [tz, days],
    );

    const categories = new Map<string, Record<string, number>>();
    const people = new Map<string, Record<string, number>>();
    for (const r of byCategory.rows) {
      const money = categories.get(r.date) ?? {};
      money[r.category] = Number(r.cents) / 100;
      categories.set(r.date, money);
      const heads = people.get(r.date) ?? {};
      heads[r.category] = Number(r.people);
      people.set(r.date, heads);
    }
    res.json(
      daily.rows.map((r) => {
        const split = categories.get(r.date) ?? {};
        const regular = split.regular ?? 0;
        const totalFromLines = Object.values(split).reduce((a, b) => a + b, 0);
        return {
          date: r.date,
          total_revenue: Number(r.total_cents) / 100,
          regular_revenue: regular,
          discounted_revenue: totalFromLines - regular,
          discount_given: Number(r.discount_given_cents) / 100,
          total_payments: Number(r.total_payments),
          total_trips: Number(r.total_trips),
          total_boardings: Number(r.total_boardings),
          by_category: split,
          passengers_by_category: people.get(r.date) ?? {},
        };
      }),
    );
  });

  app.get('/api/export/audit', admin, async (_req, res) => {
    const { rows } = await pool.query(
      `SELECT effective_at, received_at, clock_suspect, event_id, event_type, vehicle_id, device_id,
              delivery_channel, card_uuid, nfc_uid, card_state, trip_id,
              boarding_stop_id, declared_destination_stop_id, actual_destination_stop_id,
              discount_category_id, computed_fare_centavos, fare_centavos, discount_centavos,
              fare_version, override_reason, gps_lat, gps_lon, gps_accuracy_m, passenger_count
       FROM events ORDER BY effective_at DESC, id DESC LIMIT $1`,
      [EXPORT_ROW_LIMIT + 1],
    );
    const truncated = rows.length > EXPORT_ROW_LIMIT;
    const data = truncated ? rows.slice(0, EXPORT_ROW_LIMIT) : rows;

    const header = [
      'Time (UTC)', 'Received (UTC)', 'Clock suspect', 'Event ID', 'Event type', 'Vehicle', 'Device',
      'Channel', 'Card UUID', 'NFC UID', 'Card state', 'Trip ID', 'Boarding stop', 'Declared destination',
      'Actual destination', 'Discount category', 'Computed fare (PHP)', 'Fare (PHP)', 'Discount (PHP)',
      'Fare version', 'Override reason', 'GPS lat', 'GPS lon', 'GPS accuracy (m)', 'Passengers',
    ].join(',');
    const lines = data.map((r) =>
      [
        csvText(iso(r.effective_at)), csvText(iso(r.received_at)), r.clock_suspect ? 'yes' : 'no',
        csvText(r.event_id), csvText(r.event_type), csvText(r.vehicle_id), csvText(r.device_id),
        csvText(r.delivery_channel), csvText(r.card_uuid), csvText(r.nfc_uid), csvText(r.card_state),
        csvText(r.trip_id), csvText(r.boarding_stop_id), csvText(r.declared_destination_stop_id),
        csvText(r.actual_destination_stop_id), csvText(r.discount_category_id),
        pesos(r.computed_fare_centavos), pesos(r.fare_centavos), pesos(r.discount_centavos),
        csvNumber(r.fare_version), csvText(r.override_reason),
        csvNumber(r.gps_lat), csvNumber(r.gps_lon), csvNumber(r.gps_accuracy_m), csvNumber(r.passenger_count),
      ].join(','),
    );

    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', 'attachment; filename="toms_audit_logs.csv"');
    res.setHeader('Access-Control-Expose-Headers', 'X-Export-Truncated, Content-Disposition');
    if (truncated) res.setHeader('X-Export-Truncated', 'true');
    res.send('\uFEFF' + [header, ...lines].join('\r\n') + '\r\n');
  });

  app.get('/api/conductors', admin, async (_req, res) => {
    const { rows } = await pool.query(
      `SELECT c.id, c.username, c.name, c.contact_number, c.address, c.company_id,
              v.id AS assigned_vehicle,
              COALESCE(s.sales_centavos, 0) / 100.0 AS total_sales_today,
              l.last_sync
       FROM conductors c
       LEFT JOIN vehicles v ON v.assigned_conductor_id = c.id
       LEFT JOIN (
         SELECT vehicle_id, sum(fare_centavos) AS sales_centavos
         FROM trip_status
         WHERE paid AND (effective_at AT TIME ZONE $1)::date = (now() AT TIME ZONE $1)::date
         GROUP BY vehicle_id
       ) s ON s.vehicle_id = v.id
       LEFT JOIN (
         SELECT vehicle_id, max(received_at) AS last_sync FROM events GROUP BY vehicle_id
       ) l ON l.vehicle_id = v.id
       ORDER BY c.id`,
      [tz],
    );
    res.json(
      rows.map((r) => ({
        ...r,
        assigned_vehicle: r.assigned_vehicle ?? 'N/A',
        total_sales_today: Number(r.total_sales_today),
        last_sync: r.last_sync ?? 'N/A',
        status: r.assigned_vehicle ? 'Active' : 'Offline',
      })),
    );
  });

  // How events are reaching the server. The server cannot see a phone's unsent backlog; that count
  // needs a heartbeat from the app (not built yet). What it can show is when each phone last
  // delivered, by which channel, and how long events waited between happening and arriving.
  app.get('/api/delivery/summary', admin, async (_req, res) => {
    const channels = await pool.query(
      `SELECT delivery_channel,
              count(*) AS events,
              max(received_at) AS last_received,
              percentile_cont(0.5)  WITHIN GROUP (ORDER BY extract(epoch FROM received_at - occurred_at)) AS lag_p50_s,
              percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM received_at - occurred_at)) AS lag_p95_s
       FROM events
       WHERE received_at > now() - interval '7 days' AND NOT clock_suspect
       GROUP BY delivery_channel ORDER BY delivery_channel`,
    );
    const devices = await pool.query(
      `SELECT d.device_id, d.vehicle_id, d.label, d.revoked_at IS NOT NULL AS revoked,
              max(e.received_at) AS last_seen,
              count(e.id) FILTER (WHERE e.received_at > now() - interval '24 hours') AS events_24h,
              count(e.id) FILTER (WHERE e.clock_suspect) AS clock_suspect_events
       FROM devices d LEFT JOIN events e ON e.device_id = d.device_id
       GROUP BY d.device_id ORDER BY last_seen DESC NULLS LAST, d.device_id`,
    );
    const num = (v: unknown) => (v == null ? null : Number(v));
    res.json({
      window_days: 7,
      channels: channels.rows.map((r) => ({
        delivery_channel: r.delivery_channel,
        events: Number(r.events),
        last_received: r.last_received,
        lag_p50_seconds: num(r.lag_p50_s),
        lag_p95_seconds: num(r.lag_p95_s),
      })),
      devices: devices.rows.map((r) => ({
        ...r,
        events_24h: Number(r.events_24h),
        clock_suspect_events: Number(r.clock_suspect_events),
      })),
    });
  });
}
