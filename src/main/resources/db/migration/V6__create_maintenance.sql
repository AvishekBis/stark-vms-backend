-- FR-M01 / FR-M02: maintenance records. Money is NUMERIC(12,2) in BDT - never float (guideline 10.2).
CREATE TABLE maintenance_records (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_id       BIGINT        NOT NULL,
    service_date     DATE          NOT NULL,
    maintenance_type VARCHAR(30)   NOT NULL,
    cost             NUMERIC(12,2) NOT NULL,
    vendor_name      VARCHAR(100),
    odometer_km      INTEGER,
    notes            VARCHAR(1000),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(255),
    CONSTRAINT fk_maintenance_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_maintenance_type CHECK (maintenance_type IN
        ('ROUTINE_SERVICE', 'OIL_CHANGE', 'TYRE', 'BRAKE', 'ENGINE', 'ELECTRICAL', 'BODY_WORK', 'INSPECTION',
         'REPAIR', 'OTHER')),
    -- BR-09: cost never negative (service date "not in the future" depends on today and is checked in the service).
    CONSTRAINT ck_maintenance_cost CHECK (cost >= 0),
    CONSTRAINT ck_maintenance_odometer CHECK (odometer_km IS NULL OR odometer_km >= 0)
);

-- Per-vehicle history, newest first (FR-M02).
CREATE INDEX idx_maintenance_vehicle_service_date ON maintenance_records (vehicle_id, service_date DESC);

-- "This month" count and cost on the dashboard (FR-DB01).
CREATE INDEX idx_maintenance_service_date ON maintenance_records (service_date);
