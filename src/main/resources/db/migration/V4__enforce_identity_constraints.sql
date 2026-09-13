ALTER TABLE users
    ALTER COLUMN author_id SET NOT NULL,
    ALTER COLUMN pin_hash SET NOT NULL,
    ALTER COLUMN pin_backfilled SET NOT NULL,
    ALTER COLUMN pin_backfilled SET DEFAULT FALSE;

-- These structured PostgreSQL names are the identity service's conflict contract.
ALTER TABLE users RENAME CONSTRAINT users_pkey TO pk_users_uuid;
ALTER TABLE users RENAME CONSTRAINT users_username_key TO uk_users_username;
ALTER TABLE users ADD CONSTRAINT uk_users_author_id UNIQUE (author_id);
