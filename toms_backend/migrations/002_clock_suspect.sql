-- A phone with a wrong clock (dead RTC battery, manual change) still produced a real fare, so
-- its events are stored, not rejected. This flag marks them for review. Set at ingest when
-- occurred_at is before 2020-01-01 or more than 5 minutes after the server's receive time.
ALTER TABLE events ADD COLUMN clock_suspect boolean NOT NULL DEFAULT false;
CREATE INDEX events_clock_suspect_idx ON events (device_id) WHERE clock_suspect;
