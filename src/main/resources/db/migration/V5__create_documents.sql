-- FR-D01 / FR-D02: vehicle documents.
-- stored_files holds the file bytes when app.storage.provider=database (default). It lives in PostgreSQL, so
-- uploads survive Render redeploys. With app.storage.provider=supabase this table stays empty.
CREATE TABLE stored_files (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_key  VARCHAR(100) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size    BIGINT       NOT NULL,
    data         BYTEA        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(255),
    CONSTRAINT uk_stored_files_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_stored_files_file_size CHECK (file_size >= 0)
);

-- Document metadata. storage_key is a random UUID - never the user's file name (security, guideline 13);
-- file_name is the sanitized original name, kept only for display and Content-Disposition.
-- storage_key has no foreign key to stored_files because the bytes may live in external storage
-- (Supabase); DocumentService deletes the stored file together with the document.
CREATE TABLE vehicle_documents (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_id      BIGINT       NOT NULL,
    document_type   VARCHAR(30)  NOT NULL,
    document_number VARCHAR(60),
    issue_date      DATE,
    expiry_date     DATE         NOT NULL,
    storage_key     VARCHAR(100) NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    file_size       BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    CONSTRAINT fk_documents_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id) ON DELETE RESTRICT,
    CONSTRAINT uk_vehicle_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_vehicle_documents_type CHECK (document_type IN
        ('REGISTRATION', 'TAX_TOKEN', 'INSURANCE', 'FITNESS_CERTIFICATE', 'ROUTE_PERMIT', 'OTHER')),
    -- BR-07: expiry on or after issue date.
    CONSTRAINT ck_vehicle_documents_dates CHECK (issue_date IS NULL OR expiry_date >= issue_date),
    CONSTRAINT ck_vehicle_documents_file_size CHECK (file_size > 0)
);

-- Expiring / expired lists and dashboard counts (FR-D02, FR-DB01).
CREATE INDEX idx_vehicle_documents_expiry_date ON vehicle_documents (expiry_date);
CREATE INDEX idx_vehicle_documents_vehicle ON vehicle_documents (vehicle_id, expiry_date);
