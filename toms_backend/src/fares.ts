import fs from 'fs';
import path from 'path';
import type { Express, Request, RequestHandler, Response } from 'express';
import multer from 'multer';
import type { Pool } from 'pg';
import { z } from 'zod';

export const FARES_UPLOAD_DIR = path.join(process.cwd(), 'uploads', 'fares');

// Ensure upload directory exists
fs.mkdirSync(FARES_UPLOAD_DIR, { recursive: true });

const storage = multer.diskStorage({
  destination: (_req, _file, cb) => {
    cb(null, FARES_UPLOAD_DIR);
  },
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname).toLowerCase();
    const safeExt = ['.jpg', '.jpeg', '.png', '.webp', '.pdf'].includes(ext) ? ext : '.bin';
    const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}${safeExt}`;
    cb(null, `fare-${unique}`);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: 10 * 1024 * 1024 }, // 10 MB limit
  fileFilter: (_req, file, cb) => {
    const allowed = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];
    if (allowed.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error('Invalid file type. Only JPEG, PNG, WebP, and PDF documents are allowed.'));
    }
  },
});

const fareMatrixSchema = z.object({
  route_id: z.coerce.number().int().positive().nullish(),
  base_fare: z.coerce.number().min(0).max(100_000),
  base_distance_km: z.coerce.number().min(0.1).max(1000).default(4.0),
  per_km_fare: z.coerce.number().min(0).max(10_000),
  rounding_step_cents: z.coerce.number().int().min(1).max(10_000).default(100),
  discounts: z
    .string()
    .optional()
    .transform((s) => (s ? JSON.parse(s) : undefined))
    .or(z.record(z.string(), z.number()))
    .default({ student: 20, pwd: 20, senior: 20 }),
  order_reference: z.string().trim().min(1).max(200).nullish(),
  effective_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).default(() => new Date().toISOString().slice(0, 10)),
});

export function registerFareRoutes(
  app: Express,
  { pool, admin }: { pool: Pool; admin: RequestHandler },
) {
  // POST /api/fares - Admin sets verified fare matrix and uploads official LTFRB document scan
  app.post(
    '/api/fares',
    admin,
    upload.single('document'),
    async (req: Request, res: Response) => {
      const parseRes = fareMatrixSchema.safeParse(req.body);
      if (!parseRes.success) {
        // If file was uploaded but validation failed, clean up the file
        if (req.file) fs.unlink(req.file.path, () => undefined);
        return void res.status(400).json({
          error: 'Invalid fare matrix parameters',
          issues: parseRes.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
        });
      }

      const {
        route_id,
        base_fare,
        base_distance_km,
        per_km_fare,
        rounding_step_cents,
        discounts,
        order_reference,
        effective_date,
      } = parseRes.data;

      const documentUrl = req.file ? `/uploads/fares/${req.file.filename}` : null;

      const client = await pool.connect();
      try {
        await client.query('BEGIN');

        // Deactivate previous active matrices for this route (or default matrix)
        if (route_id) {
          await client.query('UPDATE fare_matrices SET is_active = false WHERE route_id = $1', [route_id]);
          // Also keep routes table legacy base_fare and per_km_fare aligned for backwards compatibility
          await client.query('UPDATE routes SET base_fare = $1, per_km_fare = $2 WHERE id = $3', [
            base_fare,
            per_km_fare,
            route_id,
          ]);
        } else {
          await client.query('UPDATE fare_matrices SET is_active = false WHERE route_id IS NULL');
        }

        const { rows } = await client.query(
          `INSERT INTO fare_matrices (
             route_id, base_fare, base_distance_km, per_km_fare, rounding_step_cents,
             discounts, order_reference, effective_date, document_url, is_active
           ) VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $8, $9, true)
           RETURNING *`,
          [
            route_id ?? null,
            base_fare,
            base_distance_km,
            per_km_fare,
            rounding_step_cents,
            JSON.stringify(discounts),
            order_reference ?? null,
            effective_date,
            documentUrl,
          ],
        );

        await client.query('COMMIT');
        res.status(201).json(rows[0]);
      } catch (err) {
        await client.query('ROLLBACK');
        if (req.file) fs.unlink(req.file.path, () => undefined);
        throw err;
      } finally {
        client.release();
      }
    },
  );

  // GET /api/fares/active and /api/fares/active/:route_id - Get current active fare matrix and LTFRB document URL
  app.get(['/api/fares/active', '/api/fares/active/:route_id'], async (req: Request, res: Response) => {
    const rawId = Array.isArray(req.params.route_id) ? req.params.route_id[0] : req.params.route_id;
    const routeId = rawId ? parseInt(rawId, 10) : null;
    let query: string;
    let params: unknown[];

    if (routeId && !isNaN(routeId)) {
      query = `SELECT * FROM fare_matrices
               WHERE (route_id = $1 OR route_id IS NULL) AND is_active = true
               ORDER BY (route_id IS NOT NULL) DESC, created_at DESC
               LIMIT 1`;
      params = [routeId];
    } else {
      query = `SELECT * FROM fare_matrices
               WHERE is_active = true
               ORDER BY created_at DESC`;
      params = [];
    }

    const { rows } = await pool.query(query, params);
    if (rows.length === 0) {
      // Fallback default matrix matching Philippine LTFRB baseline
      return void res.json({
        base_fare: 15.0,
        base_distance_km: 4.0,
        per_km_fare: 2.5,
        rounding_step_cents: 100,
        discounts: { student: 20, pwd: 20, senior: 20 },
        order_reference: null,
        document_url: null,
        is_default_fallback: true,
      });
    }

    res.json(routeId ? rows[0] : rows);
  });

  // GET /api/fares/history and /api/fares/history/:route_id - Admin audits past fare matrices
  app.get(['/api/fares/history', '/api/fares/history/:route_id'], admin, async (req: Request, res: Response) => {
    const rawId = Array.isArray(req.params.route_id) ? req.params.route_id[0] : req.params.route_id;
    const routeId = rawId ? parseInt(rawId, 10) : null;
    let query = 'SELECT * FROM fare_matrices';
    const params: unknown[] = [];
    if (routeId && !isNaN(routeId)) {
      query += ' WHERE route_id = $1';
      params.push(routeId);
    }
    query += ' ORDER BY created_at DESC';

    const { rows } = await pool.query(query, params);
    res.json(rows);
  });
}
