-- FR-DR01: drivers.
-- phone is stored normalized (e.g. 01711000001) and is UNIQUE. license_number keeps the value as entered
-- (trimmed); license_key is its normalized form (trim, uppercase, no spaces/hyphens) and is UNIQUE.
-- Both are also checked in DriverService; these constraints are the safety net for races.
CREATE TABLE drivers (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name      VARCHAR(100) NOT NULL,
    phone          VARCHAR(20)  NOT NULL,
    license_number VARCHAR(40)  NOT NULL,
    license_key    VARCHAR(40)  NOT NULL,
    license_expiry DATE         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(255),
    CONSTRAINT uk_drivers_phone UNIQUE (phone),
    CONSTRAINT uk_drivers_license_key UNIQUE (license_key),
    CONSTRAINT ck_drivers_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- Default list order (fullName asc) and licence-expiry counts on the dashboard (FR-X02).
CREATE INDEX idx_drivers_full_name ON drivers (full_name);
CREATE INDEX idx_drivers_license_expiry ON drivers (license_expiry);
