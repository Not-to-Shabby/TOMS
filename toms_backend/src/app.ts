import argon2 from 'argon2';
import cors from 'cors';
import express, { type NextFunction, type Request, type Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';
import { LoginLimiter, makeAuth, newDeviceCredential, verifyPassword } from './auth';
import { fleetStatus, registerDashboardRoutes } from './dashboard';
import { envelopeSchema, ingestEvents } from './ingest';

export interface AppDeps {
  pool: Pool;
  jwtSecret: string;
  /** Browser origins allowed to call the API. Empty means no cross-origin browser access. */
  corsOrigins?: string[];
  /** Pushes a live update to dashboard clients. A no-op in tests. */
  emit?: (event: string, payload: unknown) => void;
  loginLimiter?: LoginLimiter;
  /** IANA zone used for "today" in reports. Defaults to the Philippines. */
  reportTimezone?: string;
}

const id = z.coerce.number().int().positive();
const optionalText = z.string().trim().min(1).nullish();

function parse<T>(schema: z.ZodType<T>, data: unknown, res: Response): T | undefined {
  const result = schema.safeParse(data);
  if (!result.success) {
    res.status(400).json({
      error: 'Invalid request',
      issues: result.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
    });
    return undefined;
  }
  return result.data;
}

const stopBody = z.object({
  name: z.string().trim().min(1),
  lat: z.number().min(-90).max(90),
  lon: z.number().min(-180).max(180),
  radius_m: z.number().int().positive().optional(),
});

export function createApp({ pool, jwtSecret, corsOrigins = [], emit = () => undefined, loginLimiter, reportTimezone = 'Asia/Manila' }: AppDeps) {
  const app = express();
  const auth = makeAuth({ pool, jwtSecret });
  const limiter = loginLimiter ?? new LoginLimiter();
  const admin = auth.requireRole('admin');

  app.disable('x-powered-by');
  app.use(
    cors({
      origin: corsOrigins.length ? corsOrigins : false,
      allowedHeaders: ['Authorization', 'Content-Type'],
    }),
  );
  app.use(express.json({ limit: '1mb' }));

  app.get('/health', async (_req, res) => {
    await pool.query('SELECT 1');
    res.json({ status: 'ok', timestamp: new Date() });
  });

  // --- Sign in ------------------------------------------------------------------------------

  const loginBody = z.object({ username: z.string().trim().min(1).max(64), password: z.string().min(1).max(200) });

  function loginHandler(role: 'admin' | 'conductor') {
    const table = role === 'admin' ? 'admins' : 'conductors';
    return async (req: Request, res: Response) => {
      const body = parse(loginBody, req.body, res);
      if (!body) return;

      const key = `${role}:${body.username.toLowerCase()}`;
      const wait = limiter.blockedFor(key);
      if (wait > 0) {
        res.setHeader('Retry-After', String(Math.ceil(wait / 1000)));
        return void res.status(429).json({ error: 'Too many failed attempts. Try again later.' });
      }

      const columns = role === 'admin' ? 'id, username, password_hash' : 'id, username, password_hash, name';
      const { rows } = await pool.query(`SELECT ${columns} FROM ${table} WHERE username = $1`, [body.username]);
      const user = rows[0];
      const ok = await verifyPassword(user?.password_hash, body.password);
      if (!user || !ok) {
        limiter.fail(key);
        return void res.status(401).json({ error: 'Invalid username or password' });
      }
      limiter.reset(key);

      const token = auth.signToken(role, user.id, user.username);
      res.json({ token, user: { id: user.id, username: user.username, name: user.name ?? user.username, role } });
    };
  }

  app.post('/api/admin/login', loginHandler('admin'));
  app.post('/api/conductors/login', loginHandler('conductor'));

  // --- Device enrollment (admin) ------------------------------------------------------------

  app.post('/api/devices', admin, async (req, res) => {
    const body = parse(
      z.object({
        device_id: z.string().trim().regex(/^[A-Za-z0-9_-]{3,64}$/, 'letters, digits, dash, underscore; 3 to 64'),
        vehicle_id: z.string().trim().min(1).max(64).nullish(),
        label: z.string().trim().min(1).max(100).nullish(),
      }),
      req.body,
      res,
    );
    if (!body) return;
    const { token, tokenHash } = newDeviceCredential(body.device_id);
    try {
      await pool.query(
        'INSERT INTO devices (device_id, vehicle_id, label, token_hash) VALUES ($1, $2, $3, $4)',
        [body.device_id, body.vehicle_id ?? null, body.label ?? null, tokenHash],
      );
    } catch (err) {
      const code = (err as { code?: string }).code;
      if (code === '23505') return void res.status(409).json({ error: 'Device already enrolled' });
      if (code === '23503') return void res.status(400).json({ error: 'Unknown vehicle' });
      throw err;
    }
    // The only time the credential is visible. Only its hash is stored.
    res.status(201).json({ device_id: body.device_id, token });
  });

  app.delete('/api/devices/:deviceId', admin, async (req, res) => {
    const r = await pool.query(
      'UPDATE devices SET revoked_at = now() WHERE device_id = $1 AND revoked_at IS NULL',
      [req.params.deviceId],
    );
    if (r.rowCount === 0) return void res.status(404).json({ error: 'Device not found or already revoked' });
    res.json({ status: 'revoked' });
  });

  // --- Event ingest (phones) ----------------------------------------------------------------

  app.post('/api/events', auth.requireDevice, async (req, res) => {
    const envelope = envelopeSchema.safeParse(req.body);
    if (!envelope.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: envelope.error.issues.slice(0, 5).map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }
    const deviceId = res.locals.deviceId as string;
    // Only an event naming a different device is an impersonation attempt. Items with no
    // device_id, or that are not objects, fail validation one by one and do not sink the batch.
    const foreign = envelope.data.events.some((e) => {
      const claimed = (e as { device_id?: unknown } | null)?.device_id;
      return typeof claimed === 'string' && claimed !== deviceId;
    });
    if (foreign) {
      return void res.status(403).json({ error: 'Events must carry the authenticated device_id' });
    }
    const result = await ingestEvents(pool, envelope.data.events, envelope.data.delivery_channel);

    // Tell open dashboards before answering, so the push is never left running after the response.
    // The events are already committed, so a failure here is logged and must not fail the phone's request.
    if (result.accepted.length > 0) {
      try {
        const { rows } = await pool.query(
          'SELECT DISTINCT vehicle_id FROM events WHERE event_id = ANY($1::uuid[]) AND vehicle_id IS NOT NULL',
          [result.accepted],
        );
        for (const r of rows) {
          const [status] = await fleetStatus(pool, reportTimezone, r.vehicle_id as string);
          if (status) emit('fleet_update', status);
        }
        // One line per event for the Live Feed. A card-state change carries its trip's fare and party size,
        // so "paid" can say how much. At most 20 per request, so a phone catching up on a long offline
        // backlog does not flood the feed.
        const feed = await pool.query(
          `SELECT e.event_type, e.vehicle_id, e.card_state, e.effective_at,
                  COALESCE(t.passenger_count, e.passenger_count)            AS passenger_count,
                  COALESCE(t.fare_centavos, e.fare_centavos)                AS fare_centavos,
                  COALESCE(t.declared_destination_stop_id, e.declared_destination_stop_id) AS destination_stop_id
           FROM events e LEFT JOIN trip_status t ON t.trip_id = e.trip_id
           WHERE e.event_id = ANY($1::uuid[]) AND e.event_type IN ('trip_created', 'card_state_changed')
           ORDER BY e.device_id, e.local_seq DESC
           LIMIT 20`,
          [result.accepted],
        );
        for (const row of feed.rows.reverse()) emit('new_event', row);
        emit('events_ingested', { device_id: deviceId, accepted: result.accepted.length });
      } catch (err) {
        console.error('live update failed', err);
      }
    }
    res.json(result);
  });

  registerDashboardRoutes(app, { pool, admin, tz: reportTimezone });

  // --- Routes -------------------------------------------------------------------------------

  app.get('/api/routes', admin, async (_req, res) => {
    const { rows } = await pool.query(
      'SELECT id, name, company_id, base_fare, per_km_fare FROM routes ORDER BY id',
    );
    res.json(rows);
  });

  app.post('/api/routes', admin, async (req, res) => {
    const body = parse(z.object({ name: z.string().trim().min(1), company_id: optionalText }), req.body, res);
    if (!body) return;
    const { rows } = await pool.query(
      'INSERT INTO routes (name, company_id) VALUES ($1, COALESCE($2, \'company_1\')) RETURNING id, name, company_id, base_fare, per_km_fare',
      [body.name, body.company_id ?? null],
    );
    res.status(201).json(rows[0]);
  });

  app.delete('/api/routes/:id', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    await pool.query('DELETE FROM routes WHERE id = $1', [routeId]);
    res.json({ status: 'success' });
  });

  app.post('/api/routes/:id/fare', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    const body = parse(
      z.object({ base_fare: z.number().min(0).max(100000), per_km_fare: z.number().min(0).max(100000) }),
      req.body,
      res,
    );
    if (!routeId || !body) return;
    const r = await pool.query('UPDATE routes SET base_fare = $1, per_km_fare = $2 WHERE id = $3', [
      body.base_fare,
      body.per_km_fare,
      routeId,
    ]);
    if (r.rowCount === 0) return void res.status(404).json({ error: 'Route not found' });
    res.json({ status: 'success' });
  });

  // --- Paths and stops ----------------------------------------------------------------------

  app.get('/api/routes/:id/paths', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    const paths = (
      await pool.query('SELECT id, name, color FROM route_paths WHERE route_id = $1 ORDER BY id', [routeId])
    ).rows;
    if (paths.length === 0) return void res.json([]);

    const schedules = (await pool.query('SELECT * FROM route_schedules WHERE route_id = $1', [routeId])).rows;
    const stops = (
      await pool.query(
        `SELECT id, path_id, name, lat, lon, radius_m FROM route_stops
         WHERE path_id = ANY($1::int[]) ORDER BY path_id, stop_order`,
        [paths.map((p) => p.id)],
      )
    ).rows;

    res.json(
      paths.map((p) => ({
        ...p,
        schedule: schedules.find((s) => s.path_id === p.id) ?? null,
        stops: stops
          .filter((s) => s.path_id === p.id)
          .map((s) => ({ id: s.id, name: s.name, lat: s.lat, lon: s.lon, radius_m: s.radius_m })),
      })),
    );
  });

  app.post('/api/routes/:id/paths', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    const body = parse(z.object({ name: optionalText, color: optionalText }), req.body, res);
    if (!routeId || !body) return;
    const { rows } = await pool.query(
      `INSERT INTO route_paths (route_id, name, color)
       VALUES ($1, COALESCE($2, 'Alternative'), COALESCE($3, '#2ed573'))
       RETURNING id, route_id, name, color`,
      [routeId, body.name ?? null, body.color ?? null],
    );
    res.status(201).json({ ...rows[0], stops: [] });
  });

  app.post('/api/routes/:id/paths/:pathId/stops', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    const pathId = parse(id, req.params.pathId, res);
    const stops = parse(z.array(stopBody).max(500), req.body, res);
    if (!routeId || !pathId || !stops) return;

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('DELETE FROM route_stops WHERE path_id = $1', [pathId]);
      for (const [index, s] of stops.entries()) {
        await client.query(
          'INSERT INTO route_stops (route_id, path_id, name, lat, lon, stop_order, radius_m) VALUES ($1,$2,$3,$4,$5,$6,$7)',
          [routeId, pathId, s.name, s.lat, s.lon, index, s.radius_m ?? 100],
        );
      }
      await client.query('COMMIT');
      res.json({ status: 'success' });
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
  });

  app.delete('/api/routes/:id/paths/:pathId', admin, async (req, res) => {
    const pathId = parse(id, req.params.pathId, res);
    if (!pathId) return;
    await pool.query('DELETE FROM route_paths WHERE id = $1', [pathId]);
    res.json({ status: 'success' });
  });

  // Legacy per-route stop list, kept because the route builder still calls it.
  app.get('/api/routes/:id/stops', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    const { rows } = await pool.query(
      'SELECT id, name, lat, lon, radius_m FROM route_stops WHERE route_id = $1 ORDER BY stop_order',
      [routeId],
    );
    res.json(rows);
  });

  app.post('/api/routes/:id/stops', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    const stops = parse(z.array(stopBody).max(500), req.body, res);
    if (!routeId || !stops) return;
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('DELETE FROM route_stops WHERE route_id = $1', [routeId]);
      for (const [index, s] of stops.entries()) {
        await client.query(
          'INSERT INTO route_stops (route_id, name, lat, lon, stop_order, radius_m) VALUES ($1,$2,$3,$4,$5,$6)',
          [routeId, s.name, s.lat, s.lon, index, s.radius_m ?? 100],
        );
      }
      await client.query('COMMIT');
      res.json({ status: 'success', message: 'Route stops updated successfully' });
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
  });

  // --- Schedules ----------------------------------------------------------------------------

  app.get('/api/routes/:id/schedules', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    res.json((await pool.query('SELECT * FROM route_schedules WHERE route_id = $1', [routeId])).rows);
  });

  app.post('/api/routes/:id/schedules', admin, async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    const schedules = parse(
      z.array(
        z.object({
          path_id: z.number().int().positive(),
          active_days: z.string().min(1),
          start_time: z.string().min(1),
          end_time: z.string().min(1),
        }),
      ),
      req.body,
      res,
    );
    if (!routeId || !schedules) return;
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('DELETE FROM route_schedules WHERE route_id = $1', [routeId]);
      for (const s of schedules) {
        await client.query(
          'INSERT INTO route_schedules (route_id, path_id, active_days, start_time, end_time) VALUES ($1,$2,$3,$4,$5)',
          [routeId, s.path_id, s.active_days, s.start_time, s.end_time],
        );
      }
      await client.query('COMMIT');
      res.json({ status: 'success' });
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
  });

  // --- Conductors (login and protection arrive with the auth step) -------------------------

  app.post('/api/conductors', admin, async (req, res) => {
    const body = parse(
      z.object({
        username: z.string().trim().min(3).max(64),
        password: z.string().min(8).max(200),
        name: z.string().trim().min(1),
        contact_number: optionalText,
        address: optionalText,
        assigned_vehicle: optionalText,
      }),
      req.body,
      res,
    );
    if (!body) return;

    const hash = await argon2.hash(body.password, { type: argon2.argon2id });
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const { rows } = await client.query(
        `INSERT INTO conductors (username, password_hash, name, contact_number, address)
         VALUES ($1,$2,$3,$4,$5) RETURNING id, username, name, contact_number, address, company_id`,
        [body.username, hash, body.name, body.contact_number ?? null, body.address ?? null],
      );
      if (body.assigned_vehicle) {
        await client.query('UPDATE vehicles SET assigned_conductor_id = $1 WHERE id = $2', [
          rows[0].id,
          body.assigned_vehicle,
        ]);
      }
      await client.query('COMMIT');
      res.status(201).json({ ...rows[0], assigned_vehicle: body.assigned_vehicle ?? null });
    } catch (err) {
      await client.query('ROLLBACK');
      if ((err as { code?: string }).code === '23505') {
        return void res.status(400).json({ error: 'Username already exists' });
      }
      throw err;
    } finally {
      client.release();
    }
  });

  app.delete('/api/conductors/:id', admin, async (req, res) => {
    const conductorId = parse(id, req.params.id, res);
    if (!conductorId) return;
    await pool.query('DELETE FROM conductors WHERE id = $1', [conductorId]);
    res.json({ status: 'success' });
  });

  // --- Vehicles -----------------------------------------------------------------------------

  app.get('/api/vehicles', admin, async (_req, res) => {
    const { rows } = await pool.query(
      `SELECT v.*, r.name AS assigned_route_name, c.name AS assigned_conductor_name
       FROM vehicles v
       LEFT JOIN routes r ON v.assigned_route_id = r.id
       LEFT JOIN conductors c ON v.assigned_conductor_id = c.id
       ORDER BY v.id`,
    );
    res.json(rows);
  });

  app.post('/api/vehicles', admin, async (req, res) => {
    const body = parse(
      z.object({
        id: z.string().trim().min(1).max(64),
        plate_number: z.string().trim().min(1).max(32),
        max_capacity: z.number().int().positive().max(500).optional(),
        status: z.string().trim().min(1).optional(),
      }),
      req.body,
      res,
    );
    if (!body) return;
    try {
      const { rows } = await pool.query(
        `INSERT INTO vehicles (id, plate_number, max_capacity, status)
         VALUES ($1, $2, COALESCE($3, 20), COALESCE($4, 'Active'))
         RETURNING id, plate_number, max_capacity, status`,
        [body.id, body.plate_number, body.max_capacity ?? null, body.status ?? null],
      );
      res.status(201).json(rows[0]);
    } catch (err) {
      if ((err as { code?: string }).code === '23505') {
        return void res.status(409).json({ error: 'Vehicle id already exists' });
      }
      throw err;
    }
  });

  app.put('/api/vehicles/:id', admin, async (req, res) => {
    const vehicleId = req.params.id;
    const body = parse(
      z.object({
        plate_number: z.string().trim().min(1).optional(),
        max_capacity: z.number().int().positive().max(500).optional(),
        status: z.string().trim().min(1).optional(),
        assigned_route_id: z.number().int().positive().nullish(),
        assigned_conductor_id: z.number().int().positive().nullish(),
      }),
      req.body,
      res,
    );
    if (!body) return;

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      if (body.assigned_conductor_id) {
        // A conductor drives one vehicle at a time.
        await client.query(
          'UPDATE vehicles SET assigned_conductor_id = NULL WHERE assigned_conductor_id = $1 AND id <> $2',
          [body.assigned_conductor_id, vehicleId],
        );
      }
      const r = await client.query(
        `UPDATE vehicles SET
           plate_number = COALESCE($1, plate_number),
           max_capacity = COALESCE($2, max_capacity),
           status = COALESCE($3, status),
           assigned_route_id = $4,
           assigned_conductor_id = $5
         WHERE id = $6`,
        [
          body.plate_number ?? null,
          body.max_capacity ?? null,
          body.status ?? null,
          body.assigned_route_id ?? null,
          body.assigned_conductor_id ?? null,
          vehicleId,
        ],
      );
      if (r.rowCount === 0) {
        await client.query('ROLLBACK');
        return void res.status(404).json({ error: 'Vehicle not found' });
      }
      await client.query('COMMIT');
      emit('dispatch_updated', { vehicle_id: vehicleId });
      res.json({ status: 'success' });
    } catch (err) {
      await client.query('ROLLBACK');
      throw err;
    } finally {
      client.release();
    }
  });

  app.delete('/api/vehicles/:id', admin, async (req, res) => {
    await pool.query('DELETE FROM vehicles WHERE id = $1', [req.params.id]);
    res.json({ status: 'success' });
  });

  // Express 5 forwards rejected async handlers here. Details stay in the log, not the response.
  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    const status = (err as { status?: unknown })?.status;
    if (typeof status === 'number' && status >= 400 && status < 500) {
      return void res
        .status(status)
        .json({ error: status === 413 ? 'Request body too large' : 'Invalid request body' });
    }
    console.error(err);
    res.status(500).json({ error: 'Internal server error' });
  });

  return app;
}
