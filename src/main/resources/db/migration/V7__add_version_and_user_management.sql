-- v2.1 (ADR 0023, ADR 0024): optimistic locking and ADMIN user management.
-- New migration only - V1..V6 are never edited.

-- ADR 0023: JPA @Version column of every record that is edited with PUT. Existing rows start at 0;
-- Hibernate increments it on every change and adds "WHERE version = ?" to each UPDATE.
-- vehicles.version comes from V2_1__add_vehicle_version.sql (shared with v1.1), so it is not added here.
ALTER TABLE drivers             ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE maintenance_records ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users               ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- ADR 0024: when the password was last changed (admin reset or own change). Tokens issued before this
-- moment are rejected. NULL = never changed since the account was created. (users.created_by exists since V1.)
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ NULL;
