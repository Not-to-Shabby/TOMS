-- TOMS v2 schema. Replaces the SQLite prototype (slave_uid / slot_number events).
-- Migrations are append-only: never edit an applied file, add a new one.

CREATE TABLE routes (
    id          serial PRIMARY KEY,
    name        text NOT NULL,
    company_id  text NOT NULL DEFAULT 'company_1',
    -- Placeholder fare columns kept so the route builder keeps working. They are
    -- not verified fares; versioned fare settings replace them in Phase 1.6.
    base_fare   numeric(10,2) NOT NULL DEFAULT 15.00 CHECK (base_fare >= 0),
    per_km_fare numeric(10,2) NOT NULL DEFAULT 2.50 CHECK (per_km_fare >= 0)
);

CREATE TABLE route_paths (
    id       serial PRIMARY KEY,
    route_id integer NOT NULL REFERENCES routes (id) ON DELETE CASCADE,
    name     text NOT NULL DEFAULT 'Primary',
    color    text NOT NULL DEFAULT '#00d2ff'
);

CREATE TABLE route_stops (
    id         serial PRIMARY KEY,
    route_id   integer NOT NULL REFERENCES routes (id) ON DELETE CASCADE,
    path_id    integer REFERENCES route_paths (id) ON DELETE CASCADE,
    name       text NOT NULL,
    lat        double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon        double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    stop_order integer NOT NULL,
    radius_m   integer NOT NULL DEFAULT 100 CHECK (radius_m > 0)
);
CREATE INDEX route_stops_path_idx ON route_stops (path_id, stop_order);

CREATE TABLE route_schedules (
    id          serial PRIMARY KEY,
    route_id    integer NOT NULL REFERENCES routes (id) ON DELETE CASCADE,
    path_id     integer NOT NULL REFERENCES route_paths (id) ON DELETE CASCADE,
    active_days text NOT NULL,
    start_time  text NOT NULL,
    end_time    text NOT NULL
);

CREATE TABLE conductors (
    id             serial PRIMARY KEY,
    username       text NOT NULL UNIQUE,
    password_hash  text NOT NULL,
    name           text NOT NULL,
    contact_number text,
    address        text,
    company_id     text NOT NULL DEFAULT 'company_1'
);

CREATE TABLE vehicles (
    id                    text PRIMARY KEY,
    plate_number          text NOT NULL,
    max_capacity          integer NOT NULL DEFAULT 20 CHECK (max_capacity > 0),
    status                text NOT NULL DEFAULT 'Active',
    assigned_route_id     integer REFERENCES routes (id) ON DELETE SET NULL,
    assigned_conductor_id integer REFERENCES conductors (id) ON DELETE SET NULL
);

-- A phone enrolled to a vehicle. Credentials are added with the auth step.
CREATE TABLE devices (
    device_id   text PRIMARY KEY,
    vehicle_id  text REFERENCES vehicles (id) ON DELETE SET NULL,
    label       text,
    enrolled_at timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);

-- Append-only event log. Corrections are new events, never updates.
-- event_id is generated on the phone and is the idempotency key.
CREATE TABLE events (
    id               bigserial PRIMARY KEY,
    event_id         uuid NOT NULL,
    device_id        text NOT NULL,
    -- Per-device counter on the phone. Not unique: it restarts if the phone's
    -- local database is recreated while the device_id survives.
    local_seq        bigint NOT NULL CHECK (local_seq >= 0),
    event_type       text NOT NULL,
    vehicle_id       text,
    terminal_id      text,
    -- occurred_at is the phone's clock (UTC). received_at is the server's audit time.
    occurred_at      timestamptz NOT NULL,
    received_at      timestamptz NOT NULL DEFAULT now(),
    delivery_channel text NOT NULL DEFAULT 'data_a'
                     CHECK (delivery_channel IN ('data_a', 'data_b', 'sms', 'late_sync')),

    trip_id                       uuid,
    card_uuid                     uuid,
    nfc_uid                       text CHECK (nfc_uid IS NULL OR nfc_uid ~ '^[0-9A-F]{8,20}$'),
    card_state                    text CHECK (card_state IS NULL OR card_state IN
                                  ('AVAILABLE', 'ASSIGNED_UNPAID', 'ASSIGNED_PAID',
                                   'RETURNED', 'RETURNED_UNPAID', 'LOST')),
    boarding_stop_id              text,
    declared_destination_stop_id  text,
    actual_destination_stop_id    text,
    discount_category_id          text,
    computed_fare_centavos        integer CHECK (computed_fare_centavos >= 0),
    fare_centavos                 integer CHECK (fare_centavos >= 0),
    discount_centavos             integer CHECK (discount_centavos >= 0),
    fare_version                  integer,
    override_reason               text,

    -- GPS fix in force when the event happened. All three of lat, lon and fix time
    -- are present or all absent; accuracy may be unknown.
    gps_lat        double precision CHECK (gps_lat BETWEEN -90 AND 90),
    gps_lon        double precision CHECK (gps_lon BETWEEN -180 AND 180),
    gps_accuracy_m real CHECK (gps_accuracy_m >= 0),
    gps_fix_at     timestamptz,

    payload jsonb NOT NULL,

    CONSTRAINT events_event_id_key UNIQUE (event_id),
    CONSTRAINT events_gps_all_or_none CHECK (
        (gps_lat IS NULL) = (gps_lon IS NULL) AND (gps_lat IS NULL) = (gps_fix_at IS NULL)
        AND (gps_accuracy_m IS NULL OR gps_lat IS NOT NULL)
    ),
    CONSTRAINT events_trip_created_fields CHECK (
        event_type <> 'trip_created' OR (
            trip_id IS NOT NULL AND card_uuid IS NOT NULL AND nfc_uid IS NOT NULL
            AND boarding_stop_id IS NOT NULL AND declared_destination_stop_id IS NOT NULL
            AND computed_fare_centavos IS NOT NULL AND fare_centavos IS NOT NULL
            AND discount_centavos IS NOT NULL AND fare_version IS NOT NULL
        )
    ),
    CONSTRAINT events_card_state_fields CHECK (
        event_type <> 'card_state_changed' OR (card_uuid IS NOT NULL AND nfc_uid IS NOT NULL AND card_state IS NOT NULL)
    )
);
CREATE INDEX events_vehicle_time_idx ON events (vehicle_id, occurred_at DESC);
CREATE INDEX events_device_seq_idx   ON events (device_id, local_seq);
CREATE INDEX events_trip_idx         ON events (trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX events_type_time_idx    ON events (event_type, occurred_at DESC);

-- One row per trip with its current card state and whether the fare was collected.
-- "paid" stays true after the card is released, because release writes AVAILABLE.
CREATE VIEW trip_status AS
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
    ) AS paid
FROM events t
LEFT JOIN LATERAL (
    SELECT s.card_state
    FROM events s
    WHERE s.trip_id = t.trip_id AND s.event_type = 'card_state_changed'
    ORDER BY s.occurred_at DESC, s.local_seq DESC, s.id DESC
    LIMIT 1
) last_state ON true
WHERE t.event_type = 'trip_created';
