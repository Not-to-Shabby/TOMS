-- TOMS v2: Cards can be generated and printed before an NFC chip is paired to them.
-- An unpaired card has no nfc_uid yet; pairing fills it in once, from the admin's phone.

ALTER TABLE cards ALTER COLUMN nfc_uid DROP NOT NULL;

ALTER TABLE cards DROP CONSTRAINT IF EXISTS cards_status_check;
ALTER TABLE cards ADD CONSTRAINT cards_status_check
    CHECK (status IN ('unpaired', 'active', 'suspended', 'retired', 'lost'));

ALTER TABLE cards ADD COLUMN serial text UNIQUE;

ALTER TABLE cards ADD CONSTRAINT cards_active_has_uid
    CHECK (status NOT IN ('active', 'suspended') OR nfc_uid IS NOT NULL);
