-- Dashboard administrators. There is no seeded account: create one with `npm run create-admin`.
CREATE TABLE admins (
    id            serial PRIMARY KEY,
    username      text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Device credential: the phone holds "<device_id>.<secret>"; only a SHA-256 of the secret is stored,
-- so a database leak does not reveal usable credentials. A device with no token_hash cannot sign in.
ALTER TABLE devices ADD COLUMN token_hash text;
