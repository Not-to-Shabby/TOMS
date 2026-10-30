-- TOMS v2: a history of every change to a card, so "who did this and why" can be answered later.

ALTER TABLE cards ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

CREATE TABLE card_events (
    id              bigserial PRIMARY KEY,
    card_uuid       uuid        NOT NULL REFERENCES cards (card_uuid),
    -- created, enrolled, paired, unpaired, replaced, status_changed, edited, vehicle_changed
    action          text        NOT NULL,
    from_status     text,
    to_status       text,
    from_nfc_uid    text,
    to_nfc_uid      text,
    reason          text,
    admin_username  text        NOT NULL,
    -- Before and after values that have no column of their own (label, notes, vehicle).
    details         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX card_events_card_idx ON card_events (card_uuid, at DESC, id DESC);
