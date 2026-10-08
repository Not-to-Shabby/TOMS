import type { Express, Request, Response } from 'express';
import type { Pool } from 'pg';
import { z } from 'zod';

const uuidSchema = z.string().uuid();

export function registerReceiptRoutes(app: Express, { pool }: { pool: Pool }) {
  // Public static QR handler: /r/:card_uuid or /api/r/:card_uuid
  // When a passenger scans the QR code printed on their reusable card, it opens /r/:card_uuid
  const handleCardQr = async (req: Request, res: Response) => {
    const cardUuidParam = req.params.card_uuid;
    const parseRes = uuidSchema.safeParse(cardUuidParam);
    if (!parseRes.success) {
      if (req.accepts('html')) {
        return void res.redirect('/receipt/invalid');
      }
      return void res.status(400).json({ error: 'Invalid card UUID format' });
    }

    const cardUuid = parseRes.data;

    // Look up the latest trip associated with this card
    const { rows } = await pool.query(
      `SELECT trip_id, receipt_token, current_state, paid, effective_at
       FROM trip_status
       WHERE card_uuid = $1::uuid
       ORDER BY effective_at DESC
       LIMIT 1`,
      [cardUuid],
    );

    if (rows.length === 0) {
      if (req.accepts('html')) {
        return void res.redirect(`/receipt/inactive?card=${encodeURIComponent(cardUuid)}`);
      }
      return void res.status(404).json({
        active: false,
        message: 'No trip found for this card.',
        card_uuid: cardUuid,
      });
    }

    const trip = rows[0];
    const redirectUrl = `/receipt/${trip.receipt_token}`;

    if (req.accepts('html')) {
      return void res.redirect(redirectUrl);
    }

    res.json({
      active: true,
      card_uuid: cardUuid,
      receipt_token: trip.receipt_token,
      current_state: trip.current_state,
      paid: trip.paid,
      redirect_url: redirectUrl,
    });
  };

  app.get('/r/:card_uuid', handleCardQr);
  app.get('/api/r/:card_uuid', handleCardQr);

  // GET /api/receipt/:receipt_token - Fetch full digital receipt for a specific trip
  app.get('/api/receipt/:receipt_token', async (req: Request, res: Response) => {
    const parseRes = uuidSchema.safeParse(req.params.receipt_token);
    if (!parseRes.success) {
      return void res.status(400).json({ error: 'Invalid receipt token format' });
    }
    const receiptToken = parseRes.data;

    const { rows } = await pool.query(
      `SELECT
         t.trip_id,
         t.receipt_token,
         t.card_uuid,
         t.nfc_uid,
         t.boarding_stop_id,
         t.declared_destination_stop_id,
         t.actual_destination_stop_id,
         t.discount_category_id,
         t.computed_fare_centavos,
         t.fare_centavos,
         t.discount_centavos,
         t.fare_version,
         t.override_reason,
         t.current_state,
         t.paid,
         t.effective_at,
         t.passenger_count,
         v.id AS vehicle_id,
         v.plate_number AS vehicle_plate,
         rt.name AS route_name,
         c.name AS conductor_name,
         e.passengers,
         fm.order_reference,
         fm.document_url AS ltfrb_document_url,
         fm.base_fare AS matrix_base_fare,
         fm.per_km_fare AS matrix_per_km_fare,
         fm.discounts AS matrix_discounts
       FROM trip_status t
       JOIN events e ON e.event_id = t.trip_event_id
       LEFT JOIN vehicles v ON v.id = t.vehicle_id
       LEFT JOIN routes rt ON rt.id = v.assigned_route_id
       LEFT JOIN conductors c ON c.id = v.assigned_conductor_id
       LEFT JOIN LATERAL (
         SELECT * FROM fare_matrices
         WHERE (route_id = v.assigned_route_id OR route_id IS NULL) AND is_active = true
         ORDER BY (route_id IS NOT NULL) DESC, created_at DESC
         LIMIT 1
       ) fm ON true
       WHERE t.receipt_token = $1::uuid`,
      [receiptToken],
    );

    if (rows.length === 0) {
      return void res.status(404).json({ error: 'Receipt not found' });
    }

    const r = rows[0];
    const destination = r.actual_destination_stop_id || r.declared_destination_stop_id;

    res.json({
      receipt_token: r.receipt_token,
      trip_id: r.trip_id,
      status: r.paid ? 'PAID' : 'UNPAID',
      card_state: r.current_state,
      boarding_stop: r.boarding_stop_id,
      destination_stop: destination,
      declared_destination: r.declared_destination_stop_id,
      is_overridden: !!r.actual_destination_stop_id && r.actual_destination_stop_id !== r.declared_destination_stop_id,
      override_reason: r.override_reason,
      fare_cents: r.fare_centavos,
      fare_pesos: (r.fare_centavos / 100).toFixed(2),
      discount_cents: r.discount_centavos,
      discount_pesos: (r.discount_centavos / 100).toFixed(2),
      passenger_count: r.passenger_count || 1,
      passengers: Array.isArray(r.passengers) ? r.passengers : [],
      vehicle: {
        id: r.vehicle_id ?? 'Unknown',
        plate: r.vehicle_plate ?? 'N/A',
      },
      route_name: r.route_name ?? 'Transit Route',
      conductor_name: r.conductor_name ?? 'Assigned Conductor',
      timestamp: r.effective_at,
      ltfrb: {
        order_reference: r.order_reference ?? 'LTFRB Mandated Fare Matrix',
        document_url: r.ltfrb_document_url ?? null,
        base_fare: r.matrix_base_fare != null ? Number(r.matrix_base_fare) : 15.0,
        per_km_fare: r.matrix_per_km_fare != null ? Number(r.matrix_per_km_fare) : 2.5,
        discounts: r.matrix_discounts ?? { student: 20, pwd: 20, senior: 20 },
      },
    });
  });
}
