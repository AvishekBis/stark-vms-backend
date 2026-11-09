-- v1.1 (ADR 0023): optimistic locking on vehicles. New migration only - V1 and V2 are never edited.
-- JPA @Version column: existing rows start at 0; Hibernate increments it on every change and adds
-- "WHERE version = ?" to each UPDATE, so a lost race cannot overwrite a newer save.
ALTER TABLE vehicles ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
