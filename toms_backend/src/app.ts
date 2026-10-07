import argon2 from 'argon2';
import cors from 'cors';
import express, { type NextFunction, type Request, type Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';
import { envelopeSchema, ingestEvents } from './ingest';

export interface AppDeps {
  pool: Pool;
  /** Pushes a live update to dashboard clients. A no-op in tests. */
  emit?: (event: string, payload: unknown) => void;
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

export function createApp({ pool, emit = () => undefined }: AppDeps) {
  const app = express();
  app.use(cors());
  app.use(express.json({ limit: '1mb' }));

  app.get('/health', async (_req, res) => {
    await pool.query('SELECT 1');
    res.json({ status: 'ok', timestamp: new Date() });
  });

  // --- Event ingest -------------------------------------------------------------------------
  // Open until the auth step adds device credentials. Do not expose it to the internet before then.

  app.post('/api/events', async (req, res) => {
    const envelope = envelopeSchema.safeParse(req.body);
    if (!envelope.success) {
      return void res.status(400).json({
        error: 'Invalid request',
        issues: envelope.error.issues.slice(0, 5).map((i) => ({ path: i.path.join('.'), message: i.message })),
      });
    }
    res.json(await ingestEvents(pool, envelope.data.events, envelope.data.delivery_channel));
  });

  // --- Routes -------------------------------------------------------------------------------

  app.get('/api/routes', async (_req, res) => {
    const { rows } = await pool.query(
      'SELECT id, name, company_id, base_fare, per_km_fare FROM routes ORDER BY id',
    );
    res.json(rows);
  });

  app.post('/api/routes', async (req, res) => {
    const body = parse(z.object({ name: z.string().trim().min(1), company_id: optionalText }), req.body, res);
    if (!body) return;
    const { rows } = await pool.query(
      'INSERT INTO routes (name, company_id) VALUES ($1, COALESCE($2, \'company_1\')) RETURNING id, name, company_id, base_fare, per_km_fare',
      [body.name, body.company_id ?? null],
    );
    res.status(201).json(rows[0]);
  });

  app.delete('/api/routes/:id', async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    await pool.query('DELETE FROM routes WHERE id = $1', [routeId]);
    res.json({ status: 'success' });
  });

  app.post('/api/routes/:id/fare', async (req, res) => {
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

  app.get('/api/routes/:id/paths', async (req, res) => {
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

  app.post('/api/routes/:id/paths', async (req, res) => {
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

  app.post('/api/routes/:id/paths/:pathId/stops', async (req, res) => {
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

  app.delete('/api/routes/:id/paths/:pathId', async (req, res) => {
    const pathId = parse(id, req.params.pathId, res);
    if (!pathId) return;
    await pool.query('DELETE FROM route_paths WHERE id = $1', [pathId]);
    res.json({ status: 'success' });
  });

  // Legacy per-route stop list, kept because the route builder still calls it.
  app.get('/api/routes/:id/stops', async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    const { rows } = await pool.query(
      'SELECT id, name, lat, lon, radius_m FROM route_stops WHERE route_id = $1 ORDER BY stop_order',
      [routeId],
    );
    res.json(rows);
  });

  app.post('/api/routes/:id/stops', async (req, res) => {
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

  app.get('/api/routes/:id/schedules', async (req, res) => {
    const routeId = parse(id, req.params.id, res);
    if (!routeId) return;
    res.json((await pool.query('SELECT * FROM route_schedules WHERE route_id = $1', [routeId])).rows);
  });

  app.post('/api/routes/:id/schedules', async (req, res) => {
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

  app.post('/api/conductors', async (req, res) => {
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

  app.delete('/api/conductors/:id', async (req, res) => {
    const conductorId = parse(id, req.params.id, res);
    if (!conductorId) return;
    await pool.query('DELETE FROM conductors WHERE id = $1', [conductorId]);
    res.json({ status: 'success' });
  });

  // --- Vehicles -----------------------------------------------------------------------------

  app.get('/api/vehicles', async (_req, res) => {
    const { rows } = await pool.query(
      `SELECT v.*, r.name AS assigned_route_name, c.name AS assigned_conductor_name
       FROM vehicles v
       LEFT JOIN routes r ON v.assigned_route_id = r.id
       LEFT JOIN conductors c ON v.assigned_conductor_id = c.id
       ORDER BY v.id`,
    );
    res.json(rows);
  });

  app.post('/api/vehicles', async (req, res) => {
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

  app.put('/api/vehicles/:id', async (req, res) => {
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

  app.delete('/api/vehicles/:id', async (req, res) => {
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
