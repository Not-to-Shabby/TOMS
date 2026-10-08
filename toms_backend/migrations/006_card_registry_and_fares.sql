-- TOMS v2: Card registry, verified fare matrices with LTFRB document scans, and trip receipt tokens.

CREATE TABLE cards (
    card_uuid   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nfc_uid     text NOT NULL UNIQUE CHECK (nfc_uid ~ '^[0-9A-F]{8,20}$'),
    label       text,
    status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'retired', 'lost')),
    enrolled_at timestamptz NOT NULL DEFAULT now(),
    notes       text
);
CREATE INDEX cards_nfc_uid_idx ON cards (nfc_uid);
CREATE INDEX cards_status_idx ON cards (status);

CREATE TABLE fare_matrices (
    id                   serial PRIMARY KEY,
    route_id             integer REFERENCES routes (id) ON DELETE CASCADE,
    base_fare            numeric(10,2) NOT NULL CHECK (base_fare >= 0),
    base_distance_km     numeric(6,2) NOT NULL DEFAULT 4.0 CHECK (base_distance_km > 0),
    per_km_fare          numeric(10,2) NOT NULL DEFAULT 2.5 CHECK (per_km_fare >= 0),
    rounding_step_cents  integer NOT NULL DEFAULT 100 CHECK (rounding_step_cents > 0),
    discounts            jsonb NOT NULL DEFAULT '{"student":20,"pwd":20,"senior":20}'::jsonb,
    order_reference      text,
    effective_date       date NOT NULL DEFAULT CURRENT_DATE,
    document_url         text,
    is_active            boolean NOT NULL DEFAULT true,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fare_matrices_route_active_idx ON fare_matrices (route_id, is_active);

-- Add unguessable receipt_token to events for public passenger receipt links.
ALTER TABLE events ADD COLUMN receipt_token uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX events_receipt_token_idx ON events (receipt_token) WHERE event_type = 'trip_created';

-- Extend trip_status view with receipt_token
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
    t.passenger_count,
    t.receipt_token
FROM events t
LEFT JOIN LATERAL (
    SELECT s.card_state
    FROM events s
    WHERE s.trip_id = t.trip_id AND s.event_type = 'card_state_changed'
    ORDER BY s.occurred_at DESC, s.local_seq DESC, s.id DESC
    LIMIT 1
) last_state ON true
WHERE t.event_type = 'trip_created';
