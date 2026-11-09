# ER diagram — v1.1

The v1.1 schema, exactly as created by the Flyway migrations
`V1__create_users.sql`, `V2__create_vehicles.sql` and `V2_1__add_vehicle_version.sql` (v1.1) in
[`stark-vms-backend/src/main/resources/db/migration`](../src/main/resources/db/migration/).

```mermaid
erDiagram
    users {
        bigint id PK "GENERATED ALWAYS AS IDENTITY"
        varchar email UK "VARCHAR(255) NOT NULL, uk_users_email"
        varchar password_hash "VARCHAR(100) NOT NULL, BCrypt"
        varchar full_name "VARCHAR(100) NOT NULL"
        varchar role "VARCHAR(20) NOT NULL, ADMIN | STAFF"
        boolean enabled "NOT NULL DEFAULT TRUE"
        timestamptz created_at "NOT NULL DEFAULT now()"
        timestamptz updated_at "NOT NULL DEFAULT now()"
        varchar created_by "VARCHAR(255)"
    }
    vehicles {
        bigint id PK "GENERATED ALWAYS AS IDENTITY"
        varchar vehicle_number "VARCHAR(40) NOT NULL, as entered (trimmed)"
        varchar number_key UK "VARCHAR(40) NOT NULL, normalized, uk_vehicles_number_key"
        varchar vehicle_type "VARCHAR(30) NOT NULL, CAR | SUV | MICROBUS | BUS | TRUCK | PICKUP | MOTORCYCLE | OTHER"
        varchar brand "VARCHAR(50)"
        varchar model "VARCHAR(50)"
        smallint manufacture_year "CHECK >= 1980"
        varchar color "VARCHAR(30)"
        varchar fuel_type "VARCHAR(20), PETROL | OCTANE | DIESEL | CNG | LPG | HYBRID | ELECTRIC"
        varchar status "VARCHAR(20) NOT NULL DEFAULT ACTIVE, ACTIVE | INACTIVE | MAINTENANCE"
        timestamptz created_at "NOT NULL DEFAULT now()"
        timestamptz updated_at "NOT NULL DEFAULT now()"
        varchar created_by "VARCHAR(255)"
        bigint version "NOT NULL DEFAULT 0, optimistic locking (V2_1, ADR 0023)"
    }
```

Notes:

- There is no foreign key between the two tables in v1.0. `vehicles.created_by` holds the email of
  the user who created the row (audit, FR-X01) as text, not a reference to `users.id`.
- BR-01 (unique vehicle number): `vehicle_number` keeps the value as entered; `number_key` is the
  normalized form (trimmed, upper case, spaces and hyphens removed) and carries the `UNIQUE`
  constraint, so `DHK-1234` and `dhk 1234` cannot both exist.
- Enum-like columns are `VARCHAR` with a `CHECK` constraint (`ck_users_role`, `ck_vehicles_status`,
  `ck_vehicles_vehicle_type`, `ck_vehicles_fuel_type`, `ck_vehicles_manufacture_year`).
- Indexes: `idx_vehicles_status (status)` and `idx_vehicles_created_at (created_at DESC)` (default
  list order).
- `vehicles.version` (V2_1) is the JPA `@Version` column: Hibernate increments it on every change and adds
  `WHERE version = ?` to each `UPDATE`; the API returns it and requires it in `PUT /vehicles/{id}` (contract 6.2).
- The first ADMIN user is created at start-up from environment variables, never by a migration.
