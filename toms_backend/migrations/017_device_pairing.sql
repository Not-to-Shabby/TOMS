-- TOMS v2: one-time pairing codes, so a phone enrols itself and nobody reads out a Device ID or carries a token.

-- An administrator issues a code for one vehicle (dashboard "Add phone"). The phone sends the code together
-- with its own Device ID to POST /api/devices/pair and gets its device token back directly in that response.
--
-- A code has two parts, shown to people as ABCD-EFGH-JKMN:
--   code_id (4 characters)  public. It is how the server finds the row, and it is what lets a wrong guess be
--                           counted against THIS code: a guess that names no code could never be counted, and
--                           without a count a code could not lock after a few wrong tries.
--   secret  (8 characters)  never stored. Only a keyed hash of it (HMAC-SHA256 under a key derived from the
--                           server's JWT secret, over the code_id and the secret) is kept. The secret is short
--                           (39 bits), so a plain hash could be brute-forced from a copy of this table in minutes;
--                           with the key outside the database, a copy of the table alone cannot be. Someone who
--                           also holds the server's JWT secret could, but a code only lives 10 minutes.
-- Both parts use a 30-symbol alphabet without the look-alikes 0 O 1 I L U.
CREATE TABLE pairing_codes (
    id             bigserial PRIMARY KEY,
    code_id        text NOT NULL UNIQUE CHECK (code_id ~ '^[2-9A-HJKMNP-TV-Z]{4}$'),
    secret_hash    text NOT NULL CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    -- The vehicle comes from the code, never from the phone. A deleted vehicle takes its open codes with it.
    vehicle_id     text NOT NULL REFERENCES vehicles (id) ON DELETE CASCADE,
    -- Optional name for the phone, set by the administrator, copied to devices.label when the code is used.
    label          text CHECK (label IS NULL OR length(label) BETWEEN 1 AND 100),
    -- The administrator's username when the code was issued.
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    -- Wrong secrets tried against this code. The code locks when this reaches the limit and stays locked.
    attempts       integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    locked_at      timestamptz,
    cancelled_at   timestamptz,
    used_at        timestamptz,
    used_device_id text,
    CONSTRAINT pairing_codes_used_pair CHECK ((used_at IS NULL) = (used_device_id IS NULL))
);

-- Who issued, cancelled, locked or used a code. Holds only the public half of a code: never the secret, a hash
-- of it, or a device token. Kept apart from the phone-uploaded event log, whose rows all carry phone-side fields.
-- No foreign keys on purpose, so the history outlives a deleted vehicle.
CREATE TABLE device_pairing_audit (
    id         bigserial PRIMARY KEY,
    at         timestamptz NOT NULL DEFAULT now(),
    event_type text NOT NULL CHECK (event_type IN
               ('pairing_code_issued', 'pairing_code_cancelled', 'pairing_code_locked', 'device_paired')),
    code_id    text,
    vehicle_id text,
    device_id  text,
    -- 'admin:<username>', 'device:<device id>' or 'system'.
    actor      text NOT NULL,
    detail     text
);
CREATE INDEX device_pairing_audit_at_idx ON device_pairing_audit (at DESC);
