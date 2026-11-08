-- TOMS v2: the phone's periodic device_status telemetry, queryable for the delivery-reliability research.

-- The phone writes a device_status event at shift start, every 5 minutes during a shift, and at shift end
-- (see toms_android/.../telemetry/DeviceStatusReporter.kt and documentation/research_queries.md). Ingest
-- validates the payload strictly and links nothing to trips: the event carries no passenger data. This view
-- extracts the fields so the researcher can query them without touching JSON.
CREATE OR REPLACE VIEW device_status AS
SELECT
    t.id,
    t.device_id,
    t.vehicle_id,
    t.local_seq,
    t.occurred_at,
    t.effective_at,
    t.received_at,
    t.delivery_channel,
    t.clock_suspect,
    (t.payload->>'outboxPending')::int            AS outbox_pending,
    (t.payload->>'outboxParked')::int             AS outbox_parked,
    (t.payload->>'uploadAttempts')::bigint        AS upload_attempts,
    (t.payload->>'uploadFailures')::bigint        AS upload_failures,
    (t.payload->>'lastUploadAtMillis')::bigint    AS last_upload_at,
    (t.payload->>'batteryPct')::int               AS battery_pct,
    (t.payload->>'charging')::boolean             AS charging,
    (t.payload->>'gpsFixAgeSeconds')::int         AS gps_fix_age_seconds,
    (t.payload->>'gpsAccuracyMeters')::float8     AS gps_accuracy_meters,
    t.payload->>'network'                         AS network,
    t.payload->>'rmLinkState'                     AS rm_link_state,
    t.payload->>'appVersion'                      AS app_version,
    t.payload->>'shiftId'                         AS shift_id
FROM events t
WHERE t.event_type = 'device_status';

CREATE INDEX events_device_status_idx ON events (device_id, effective_at DESC) WHERE event_type = 'device_status';
