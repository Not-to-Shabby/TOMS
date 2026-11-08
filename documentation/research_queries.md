# Research queries — delivery reliability (RQ4)

Queries against the production `events` table for research question 4 (data delivery reliability under
limited connectivity). Run them with `psql` on the server, for example:

```
docker compose -f deploy/docker-compose.prod.yml exec -T db psql -U toms -d toms
```

Replace `:from` and `:to` with the study window, for example `'2026-11-09'::timestamptz` and
`'2026-11-16'::timestamptz`. Everything here is read-only.

## Definitions

- **occurred_at** — when the phone says the event happened (the phone's clock, UTC).
- **received_at** — when the server stored the event (the server's clock, UTC).
- **clock_suspect** — set by ingest when the phone's timestamp is implausible (before 2020 or more than
  5 minutes in the future). Delay figures exclude these rows, because a wrong phone clock makes the
  delay meaningless.
- **delivery_channel** — how the event claims to have been delivered (`data_a` today; `data_b`, `sms`
  and `late_sync` exist for later uplink work).
- **local_seq** — the phone's per-device counter. It is monotonic but restarts if the phone's local
  database is recreated, so gaps are only meaningful within one continuous run.

## Records delivered per channel and delivery delay

Delay is `received_at − occurred_at`; rows where the phone clock is suspect are excluded. Numbers are
per device and channel within the study window.

```sql
SELECT
    device_id,
    delivery_channel,
    COUNT(*)                                                AS events_delivered,
    ROUND(AVG(EXTRACT(EPOCH FROM (received_at - occurred_at)))::numeric, 1) AS avg_delay_seconds,
    MAX(EXTRACT(EPOCH FROM (received_at - occurred_at)))    AS max_delay_seconds,
    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (received_at - occurred_at))) AS median_delay_seconds
FROM events
WHERE occurred_at BETWEEN :from AND :to
  AND NOT clock_suspect
GROUP BY device_id, delivery_channel
ORDER BY device_id, delivery_channel;
```

## Records missing by sequence gap

Events that never left the phone have no row at all, so they show up as gaps in `local_seq`:

```sql
SELECT
    device_id,
    MIN(local_seq)                    AS first_seq,
    MAX(local_seq)                    AS last_seq,
    COUNT(*)                          AS events_present,
    MAX(local_seq) - MIN(local_seq) + 1 - COUNT(*) AS events_missing
FROM events
WHERE occurred_at BETWEEN :from AND :to
GROUP BY device_id
ORDER BY device_id;
```

A non-zero `events_missing` means events were lost between the phone and the server (never uploaded, or
the phone's local database was recreated — `local_seq` restarts in that case, which splits one run into
two sequences).

## What the phone knew but the server never saw

`device_status` (written at shift start, every 5 minutes during a shift, and at shift end) records the
outbox state from the phone's point of view: `outbox_pending` is what the phone was still holding, and
`local_seq` is where its sequence stood. Comparing it with the gaps above separates "the event never
left the phone" from "the phone restarted":

```sql
SELECT
    occurred_at,
    device_id,
    outbox_pending,
    outbox_parked,
    upload_attempts,
    upload_failures,
    local_seq,
    battery_pct,
    charging,
    network,
    rm_link_state
FROM device_status
WHERE occurred_at BETWEEN :from AND :to
ORDER BY device_id, occurred_at;
```

A run where `outbox_pending` grows and stays high, while the gap query shows missing events, is a dead
zone: the phone kept counting passengers but could not deliver. A run where `local_seq` drops back to a
low number is a recreated local database, not lost events.
