-- TOMS v2: a fare paid after the card came back unpaid (fare_recovered) counts as collected.

-- The phone records fare_recovered when the conductor answers "Passenger paid now" on a returned-unpaid
-- alarm. The view's `paid` flag only looked for ASSIGNED_PAID/RETURNED card states, so such a trip stayed
-- UNPAID on receipts and out of dashboard revenue forever. Recreated so `paid` also covers a fare_recovered
-- event linked to the trip, and a `recovered` flag is exposed for the receipt to show how it was settled.
-- Nothing else in the view changed.
CREATE OR REPLACE VIEW trip_status AS
SELECT
    t.trip_id,
    t.event_id                      AS trip_event_id,
    t.device_id,
    t.vehicle_id,
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
    t.gps_lat,
    t.gps_lon,
    t.occurred_at,
    COALESCE(last_state.card_state, 'ASSIGNED_UNPAID') AS current_state,
    EXISTS (
        SELECT 1 FROM events s
        WHERE s.trip_id = t.trip_id
          AND s.event_type = 'card_state_changed'
          AND s.card_state IN ('ASSIGNED_PAID', 'RETURNED')
    ) OR EXISTS (
        SELECT 1 FROM events s
        WHERE s.trip_id = t.trip_id
          AND s.event_type = 'fare_recovered'
    ) AS paid,
    t.effective_at,
    t.received_at,
    t.delivery_channel,
    t.clock_suspect,
    t.passenger_count,
    t.receipt_token,
    -- New columns go last: CREATE OR REPLACE VIEW can only append.
    EXISTS (
        SELECT 1 FROM events s
        WHERE s.trip_id = t.trip_id
          AND s.event_type = 'fare_recovered'
    ) AS recovered
FROM events t
LEFT JOIN LATERAL (
    SELECT s.card_state
    FROM events s
    WHERE s.trip_id = t.trip_id AND s.event_type = 'card_state_changed'
    ORDER BY s.occurred_at DESC, s.local_seq DESC, s.id DESC
    LIMIT 1
) last_state ON true
WHERE t.event_type = 'trip_created';
