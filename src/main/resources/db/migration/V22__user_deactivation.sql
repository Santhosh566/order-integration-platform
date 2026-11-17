-- An admin can turn a customer or driver off: they can still sign in, but the API refuses them.
-- Who did it, when and why is kept for the next admin (and cleared when they are turned back on).
ALTER TABLE app_users
    ADD COLUMN deactivated_at     TIMESTAMP(6),
    ADD COLUMN deactivated_by     VARCHAR(255),
    ADD COLUMN deactivated_reason VARCHAR(500);
