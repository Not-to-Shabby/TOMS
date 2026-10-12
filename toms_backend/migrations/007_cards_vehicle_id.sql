-- TOMS v2: Group cards by the vehicle they belong to (each vehicle carries its own physical card deck).
-- Cards with vehicle_id IS NULL are in the unassigned / spare pool.

ALTER TABLE cards ADD COLUMN vehicle_id text REFERENCES vehicles (id) ON DELETE SET NULL;
CREATE INDEX cards_vehicle_id_idx ON cards (vehicle_id);
