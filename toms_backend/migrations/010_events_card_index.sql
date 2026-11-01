-- TOMS v2: finding a card's trips and when it was last used must not scan every event.

CREATE INDEX events_card_time_idx ON events (card_uuid, effective_at DESC) WHERE card_uuid IS NOT NULL;
