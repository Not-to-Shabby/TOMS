-- TOMS v2: Return Machine health events (rm_stored_read, rm_unverified, rm_lost_gap).

-- These are machine health, not research facts: the phone records them when the machine flushed
-- stored reads after the phone was away, when a paid check went unverified, and when a lost card's
-- read_seq skipped ahead because the machine's log overflowed. Like device_status they stay out of
-- audit_events (recreated here with the same column list, as CREATE OR REPLACE requires), so the
-- audit log, the CSV export and the delivery counts never see them. The rm_health view below is
-- what the dashboard reads instead: simple per-device counts.
CREATE OR REPLACE VIEW audit_events AS
SELECT * FROM events
WHERE event_type <> 'device_status' AND event_type NOT LIKE 'rm\_%';

CREATE VIEW rm_health AS
SELECT device_id,
       count(*) FILTER (WHERE event_type = 'rm_stored_read') AS stored_reads,
       count(*) FILTER (WHERE event_type = 'rm_unverified')  AS unverified_reads,
       count(*) FILTER (WHERE event_type = 'rm_lost_gap')    AS lost_gaps,
       max(occurred_at)                                       AS last_rm_event
FROM events
WHERE event_type LIKE 'rm\_%'
GROUP BY device_id;
