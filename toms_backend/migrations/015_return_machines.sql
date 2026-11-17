-- TOMS v2: the Return Machine registry and its per-machine signing keys.

-- The Return Machine signs its card_read, heartbeat and rm_ack notifies with an 8-byte HMAC-SHA256
-- trailer once it has a key (firmware/PROTOCOL.md section 6). The server issues that key so a
-- replaced phone can fetch it again (a Keystore-bound key cannot be moved between phones). The key
-- is random (32 bytes from the server's CSPRNG) and stored here only encrypted (AES-256-GCM under
-- the server's RM_KEY_MASTER_KEY); it never appears in any other table, response or log.
--
-- `key_version` counts keys: it starts at 1 and every rotation adds one. A rotated key keeps the
-- previous one valid for a short grace window (`previous_*`), because the machine may still hold the
-- old key until the phone has re-provisioned it.
CREATE TABLE return_machines (
    id                  bigserial PRIMARY KEY,
    -- Admin-chosen label for the physical machine, e.g. its TOMS-RM-xxxx name. Unique.
    machine_id          text NOT NULL UNIQUE CHECK (machine_id ~ '^[A-Za-z0-9_:.-]{3,64}$'),
    vehicle_id          text NOT NULL REFERENCES vehicles (id) ON DELETE CASCADE,
    created_at          timestamptz NOT NULL DEFAULT now(),
    -- When the current key was generated: at registration, or at the last rotation.
    key_created_at      timestamptz NOT NULL DEFAULT now(),
    rotated_at          timestamptz,
    key_version         integer NOT NULL DEFAULT 1 CHECK (key_version >= 1),
    -- AES-256-GCM(iv || ciphertext || tag) of the 32-byte key. Never selected by list endpoints.
    key_enc             bytea NOT NULL,
    previous_key_enc    bytea,
    previous_valid_until timestamptz,
    -- Audit-style bookkeeping, not the key: the last time a phone fetched it, and which version.
    last_issued_at      timestamptz,
    last_issued_device  text,
    last_issued_version integer,
    -- The phone reported that the machine does not accept the key (machine reset or re-flashed, or a
    -- rotation the firmware cannot apply). Cleared when a signed message verifies under the current key.
    mismatch_since      timestamptz,
    mismatch_reason     text CHECK (mismatch_reason IS NULL OR mismatch_reason IN
                        ('machine_has_other_key', 'rotation_needs_reflash', 'no_signed_messages')),
    CONSTRAINT return_machines_previous_pair CHECK ((previous_key_enc IS NULL) = (previous_valid_until IS NULL))
);
-- One machine per vehicle at a time keeps "the key for this phone's vehicle" unambiguous.
CREATE UNIQUE INDEX return_machines_vehicle_idx ON return_machines (vehicle_id);

-- Who touched a machine's key, and when. Never holds a key, a hash of one, or a fragment of one: only
-- the action, the machine, the key version and who did it. This is the trail the dashboard and the
-- researcher read ("rm_key_issued" is the phone fetching the key, not a phone-uploaded event, so it lives
-- here and not in the append-only phone event log, whose rows all carry phone-side fields).
CREATE TABLE return_machine_audit (
    id           bigserial PRIMARY KEY,
    at           timestamptz NOT NULL DEFAULT now(),
    machine_id   text NOT NULL,
    event_type   text NOT NULL CHECK (event_type IN
                 ('rm_key_created', 'rm_key_issued', 'rm_key_rotated', 'rm_key_mismatch', 'rm_key_verified')),
    key_version  integer,
    -- 'admin:<username>' for dashboard actions, 'device:<device_id>' for a phone.
    actor        text NOT NULL,
    detail       text
);
CREATE INDEX return_machine_audit_machine_idx ON return_machine_audit (machine_id, at DESC);
