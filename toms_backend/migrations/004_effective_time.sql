-- The time an event should be reported under. The phone's clock is trusted unless ingest flagged it
-- (clock_suspect); then the server's receive time is used, so a phone with a dead clock battery does
-- not make its day's sales vanish from "today" or land in 1970.
ALTER TABLE events ADD COLUMN effective_at timestamptz
    GENERATED ALWAYS AS (CASE WHEN clock_suspect THEN received_at ELSE occurred_at END) STORED;
CREATE INDEX events_effective_idx ON events (effective_at DESC);

-- Same columns as before, in the same order, then the new ones (CREATE OR REPLACE requires that).
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
    ) AS paid,
    t.effective_at,
    t.received_at,
    t.delivery_channel,
    t.clock_suspect
FROM events t
LEFT JOIN LATERAL (
    SELECT s.card_state
    FROM events s
    WHERE s.trip_id = t.trip_id AND s.event_type = 'card_state_changed'
    ORDER BY s.occurred_at DESC, s.local_seq DESC, s.id DESC
    LIMIT 1
) last_state ON true
WHERE t.event_type = 'trip_created';
