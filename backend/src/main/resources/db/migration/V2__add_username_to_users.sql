ALTER TABLE users
    ADD COLUMN username VARCHAR(80) NOT NULL AFTER id,
    ADD UNIQUE INDEX uk_users_username (username);
