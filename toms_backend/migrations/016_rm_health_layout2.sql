-- TOMS v2: Return Machine health counts, with the layout-2 facts (firmware/PROTOCOL.md section 7).

-- Layout 2 lets the machine say two things the phone could only guess: how each paid check ended (the card_read outcome byte)
-- and how many stored reads / late paid replies it counted itself (the heartbeat's dropped_reads and late_replies). The phone
-- records them as rm_unverified (source "outcome"), rm_lost_gap (source "counter", gap = the increase) and rm_late_replies
-- (count = the increase). Layout 1 phones keep sending the inferred forms (source "inferred" / "seq_jump").
--
-- Why this view reads the payload: one rm_lost_gap or rm_late_replies event carries an INCREASE of N, so the machine's loss is
-- the sum of the numbers, not the number of events. The exact (machine-reported) and the inferred (phone-guessed) counts are
-- kept in separate columns so they are never added together. The view keeps the columns of 014 in the same order and appends
-- the new ones last, as CREATE OR REPLACE VIEW requires.
--
-- Like device_status, these stay out of audit_events (014 already excludes event types starting rm_), so the audit log, the CSV
-- export and the delivery counts never see them.
CREATE OR REPLACE VIEW rm_health AS
SELECT device_id,
       count(*) FILTER (WHERE event_type = 'rm_stored_read') AS stored_reads,
       count(*) FILTER (WHERE event_type = 'rm_unverified')  AS unverified_reads,
       count(*) FILTER (WHERE event_type = 'rm_lost_gap')    AS lost_gaps,
       max(occurred_at)                                       AS last_rm_event,
       -- the machine's own count of unverified reads, and the phone's inference, never summed together
       count(*) FILTER (WHERE event_type = 'rm_unverified' AND payload->>'source' = 'outcome') AS unverified_reported,
       count(*) FILTER (WHERE event_type = 'rm_unverified' AND COALESCE(payload->>'source', 'inferred') = 'inferred') AS unverified_inferred,
       -- stored reads lost to a full flash log: the sum of the machine's counter increases (exact), and of the phone's guesses
       COALESCE(sum((payload->>'gap')::bigint) FILTER (WHERE event_type = 'rm_lost_gap' AND payload->>'source' = 'counter'), 0) AS lost_reads_reported,
       COALESCE(sum((payload->>'gap')::bigint) FILTER (WHERE event_type = 'rm_lost_gap' AND COALESCE(payload->>'source', 'seq_jump') = 'seq_jump'), 0) AS lost_reads_inferred,
       -- paid replies that arrived after the machine gave up waiting: the sum of the increases
       COALESCE(sum((payload->>'count')::bigint) FILTER (WHERE event_type = 'rm_late_replies'), 0) AS late_replies
FROM events
WHERE event_type LIKE 'rm\_%'
GROUP BY device_id;
