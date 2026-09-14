-- Public pixel attribution keeps its original values; only the column name changes.
ALTER TABLE pixels RENAME COLUMN author_uuid TO author_id;

ALTER TABLE users
    ADD COLUMN author_id VARCHAR(255),
    ADD COLUMN pin_hash VARCHAR(255),
    ADD COLUMN pin_backfilled BOOLEAN;
