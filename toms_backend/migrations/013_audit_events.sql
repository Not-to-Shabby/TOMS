-- TOMS v2: the research-facing slice of the events table.

-- device_status rows are the phone's periodic health heartbeat (migration 012's view extracts them),
-- not research facts. Left in the raw events table for the device health view, but kept out of the
-- audit log list, the audit CSV export and the delivery counts, so a phone that heartbeats every
-- 5 minutes can never inflate the RQ4 delivery numbers or crowd trips out of the row-limited export.
-- One view instead of a WHERE clause per endpoint, so no consumer can forget the exclusion. A new
-- column on events reaches this view automatically; a renamed one needs this view recreated (same
-- rule as the trip_status and trip_lines views).
CREATE VIEW audit_events AS
SELECT * FROM events
WHERE event_type <> 'device_status';
