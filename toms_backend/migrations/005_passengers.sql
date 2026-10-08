-- One card covers a group (a family). A trip carries passenger lines; the count and the lines are
-- stored with the trip so occupancy and revenue can be worked out per passenger and per type.
--
-- passenger_count: how many people the card covers. Trips stored before this were one person each,
-- hence the default. passengers: [{categoryId, count, perPersonCentavos, fareCentavos, discountCentavos}].
ALTER TABLE events ADD COLUMN passenger_count integer NOT NULL DEFAULT 1
    CHECK (passenger_count BETWEEN 1 AND 30);
ALTER TABLE events ADD COLUMN passengers jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(passengers) = 'array');

-- The line counts must add up to passenger_count. A CHECK cannot contain a subquery, so the sum is
-- done by an immutable function. An empty passengers array is allowed for old rows and non-trip events.
CREATE FUNCTION passenger_lines_total(lines jsonb) RETURNS integer
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE(sum((l ->> 'count')::int), 0)::int FROM jsonb_array_elements(lines) AS l
$$;

ALTER TABLE events ADD CONSTRAINT events_passenger_lines_match CHECK (
    jsonb_array_length(passengers) = 0 OR passenger_count = passenger_lines_total(passengers)
);

-- Per-line rows for reporting: one row per trip and passenger type.
CREATE OR REPLACE VIEW trip_lines AS
SELECT
    t.trip_id,
    t.vehicle_id,
    t.effective_at,
    t.paid,
    COALESCE(l ->> 'categoryId', 'regular')       AS category,
    (l ->> 'count')::int                          AS passengers,
    (l ->> 'fareCentavos')::int                   AS fare_centavos,
    (l ->> 'discountCentavos')::int               AS discount_centavos
FROM trip_status t
JOIN events e ON e.event_id = t.trip_event_id
CROSS JOIN LATERAL jsonb_array_elements(
    CASE WHEN jsonb_array_length(e.passengers) = 0
         THEN jsonb_build_array(jsonb_build_object(
                'categoryId', t.discount_category_id, 'count', 1,
                'fareCentavos', t.fare_centavos, 'discountCentavos', t.discount_centavos))
         ELSE e.passengers END
) AS l;

-- trip_status gains passenger_count at the end (CREATE OR REPLACE VIEW only allows adding columns last).
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
    t.clock_suspect,
    t.passenger_count
FROM events t
LEFT JOIN LATERAL (
    SELECT s.card_state
    FROM events s
    WHERE s.trip_id = t.trip_id AND s.event_type = 'card_state_changed'
    ORDER BY s.occurred_at DESC, s.local_seq DESC, s.id DESC
    LIMIT 1
) last_state ON true
WHERE t.event_type = 'trip_created';
