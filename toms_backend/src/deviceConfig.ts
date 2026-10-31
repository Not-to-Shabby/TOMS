import crypto from 'crypto';
import type { Express, Request, Response } from 'express';
import type { Pool } from 'pg';
import type { Auth } from './auth';

const DISCOUNT_NAMES: Record<string, string> = { student: 'Student', pwd: 'PWD', senior: 'Senior' };

/** numeric(10,2) pesos, as the string pg returns, to whole centavos. Two decimals at most, so this is exact. */
export function pesosToCentavos(pesos: string | number): number {
  return Math.round(Number(pesos) * 100);
}

interface ConfigDiscount {
  id: string;
  name: string;
  percent: number;
}

/**
 * A positive 31-bit integer taken from everything the phone is sent, so it changes whenever any of it changes.
 * It is an integer because the phone stores it as one and trip events carry it in `fare_version` (integer).
 */
export function configVersion(content: unknown): number {
  const digest = crypto.createHash('sha256').update(JSON.stringify(content)).digest();
  return (digest.readUInt32BE(0) & 0x7fffffff) || 1;
}

export function registerDeviceConfigRoutes(app: Express, { pool, auth }: { pool: Pool; auth: Auth }) {
  // GET /api/device/config - the phone downloads the stops, fare and discounts for its own vehicle's route.
  // Nothing is invented: with no route, stops or published fare matrix the answer is 404 and the phone keeps what it has.
  app.get('/api/device/config', auth.requireDevice, async (_req: Request, res: Response) => {
    const deviceId = res.locals.deviceId as string;

    const assigned = (
      await pool.query(
        `SELECT r.id AS route_id, r.name AS route_name
         FROM devices d
         JOIN vehicles v ON v.id = d.vehicle_id
         JOIN routes r ON r.id = v.assigned_route_id
         WHERE d.device_id = $1`,
        [deviceId],
      )
    ).rows[0];
    if (!assigned) {
      return void res.status(404).json({ error: 'This device has no vehicle with an assigned route. Assign one on the dashboard.' });
    }
    const routeId = assigned.route_id as number;

    // Several paths: the first one (lowest id) that has stops. Stops saved without a path (the older route
    // builder) are used only when no path has any.
    const stops = (
      await pool.query(
        `SELECT id, name, lat, lon, radius_m FROM route_stops
         WHERE route_id = $1
           AND path_id IS NOT DISTINCT FROM COALESCE(
                 (SELECT p.id FROM route_paths p
                  WHERE p.route_id = $1 AND EXISTS (SELECT 1 FROM route_stops s WHERE s.path_id = p.id)
                  ORDER BY p.id LIMIT 1),
                 NULL)
         ORDER BY stop_order, id`,
        [routeId],
      )
    ).rows;
    if (stops.length < 2) {
      return void res.status(404).json({ error: 'The assigned route needs at least two stops. Add them on the dashboard.' });
    }

    // The same pick as the receipt page: this route's own matrix first, else the global one.
    const matrix = (
      await pool.query(
        `SELECT * FROM fare_matrices
         WHERE (route_id = $1 OR route_id IS NULL) AND is_active = true
         ORDER BY (route_id IS NOT NULL) DESC, created_at DESC, id DESC
         LIMIT 1`,
        [routeId],
      )
    ).rows[0];
    if (!matrix) {
      return void res.status(404).json({ error: 'No active fare matrix. Publish one on the Fare & LTFRB page.' });
    }

    const discounts: ConfigDiscount[] = [];
    for (const [id, percent] of Object.entries(matrix.discounts as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b))) {
      if (typeof percent !== 'number' || !Number.isInteger(percent) || percent < 0 || percent > 100) {
        return void res.status(422).json({
          error: `The active fare matrix has an invalid discount for "${id}". A discount must be a whole percent from 0 to 100.`,
        });
      }
      discounts.push({ id, name: DISCOUNT_NAMES[id] ?? id, percent });
    }

    const body = {
      route: { id: routeId, name: assigned.route_name as string },
      stops: stops.map((s) => ({ id: String(s.id), name: s.name as string, lat: s.lat as number, lon: s.lon as number, radius_m: s.radius_m as number })),
      fare: {
        matrix_id: matrix.id as number,
        base_fare_centavos: pesosToCentavos(matrix.base_fare),
        base_distance_km: Number(matrix.base_distance_km),
        per_km_fare_centavos: pesosToCentavos(matrix.per_km_fare),
        rounding_step_cents: matrix.rounding_step_cents as number,
        discounts,
      },
    };
    res.json({ version: configVersion(body), ...body });
  });
}
