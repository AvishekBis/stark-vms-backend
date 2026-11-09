-- FR-V01..FR-V05: vehicle register.
-- vehicle_number keeps the value as entered (trimmed); number_key is the normalized form
-- (trim, uppercase, no spaces/hyphens) and is UNIQUE - the safety net behind the service check (BR-01).
CREATE TABLE vehicles (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_number   VARCHAR(40)  NOT NULL,
    number_key       VARCHAR(40)  NOT NULL,
    vehicle_type     VARCHAR(30)  NOT NULL,
    brand            VARCHAR(50),
    model            VARCHAR(50),
    manufacture_year SMALLINT,
    color            VARCHAR(30),
    fuel_type        VARCHAR(20),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(255),
    CONSTRAINT uk_vehicles_number_key UNIQUE (number_key),
    CONSTRAINT ck_vehicles_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE')),
    CONSTRAINT ck_vehicles_vehicle_type CHECK (vehicle_type IN
        ('CAR', 'SUV', 'MICROBUS', 'BUS', 'TRUCK', 'PICKUP', 'MOTORCYCLE', 'OTHER')),
    CONSTRAINT ck_vehicles_fuel_type CHECK (fuel_type IS NULL OR fuel_type IN
        ('PETROL', 'OCTANE', 'DIESEL', 'CNG', 'LPG', 'HYBRID', 'ELECTRIC')),
    CONSTRAINT ck_vehicles_manufacture_year CHECK (manufacture_year IS NULL OR manufacture_year >= 1980)
);

-- Status filter and dashboard counts (FR-V09, FR-DB01).
CREATE INDEX idx_vehicles_status ON vehicles (status);

-- Default list order (createdAt desc).
CREATE INDEX idx_vehicles_created_at ON vehicles (created_at DESC);
