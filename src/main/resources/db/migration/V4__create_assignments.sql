-- FR-DR02: driver-vehicle assignments. A row is never overwritten: changing the driver closes the open row
-- (unassigned_at = now) and inserts a new one, so the history is kept (BR-05).
CREATE TABLE vehicle_driver_assignments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vehicle_id    BIGINT      NOT NULL,
    driver_id     BIGINT      NOT NULL,
    assigned_at   TIMESTAMPTZ NOT NULL,
    unassigned_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(255),
    CONSTRAINT fk_assignments_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_assignments_driver FOREIGN KEY (driver_id) REFERENCES drivers (id) ON DELETE RESTRICT,
    CONSTRAINT ck_assignments_period CHECK (unassigned_at IS NULL OR unassigned_at >= assigned_at)
);

-- BR-06: only one CURRENT assignment per vehicle and per driver (guideline 10.3). PostgreSQL partial unique
-- indexes - the safety net behind the checks in AssignmentService.
CREATE UNIQUE INDEX uq_assignment_open_vehicle
    ON vehicle_driver_assignments (vehicle_id) WHERE unassigned_at IS NULL;
CREATE UNIQUE INDEX uq_assignment_open_driver
    ON vehicle_driver_assignments (driver_id) WHERE unassigned_at IS NULL;

-- Assignment history per vehicle / per driver, newest first.
CREATE INDEX idx_assignments_vehicle_assigned_at ON vehicle_driver_assignments (vehicle_id, assigned_at DESC);
CREATE INDEX idx_assignments_driver_assigned_at ON vehicle_driver_assignments (driver_id, assigned_at DESC);
