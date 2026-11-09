# Stark Group VMS — API Contract (Assignment 2, v2.0)

> v2.0 = everything in Sections 1–5 (unchanged from v1.0, except where Section 6 says otherwise) **plus Section 6** (Assignment 2 additions). Section 6 wins on any conflict.
>
> v2.1 = v2.0 **plus Section 7** (hardening: login throttling, optimistic locking, ADMIN user management; ADRs 0022–0024).
> Section 7 wins over Sections 1–6. It was written **before implementation** (2026-11-07) and is implemented in the
> backend since 2026-11-08 (migration `V7`); the few points settled during implementation are marked there.
> A security review on 2026-11-08 led to corrections in 7.1, 7.3.4, 7.3.5 and 7.6 (migration `V8`), each marked
> "after the security review".

This is the contract between `stark-vms-frontend` (Flutter Web) and `stark-vms-backend` (Spring Boot).
Requirement IDs refer to *Stark_Group_VMS_Project_Guideline.docx*.

All paths are relative to **`/api/v1`**. JSON is camelCase. Dates are ISO-8601 (`2026-11-15`),
timestamps are ISO-8601 with offset (`2026-11-15T10:20:30.123+06:00` or `...Z`).

Every endpoint except `POST /auth/login` needs `Authorization: Bearer <accessToken>`.

---

## 1. Auth (FR-AUTH-01, FR-AUTH-02)

### `POST /auth/login` — public
Request
```json
{ "email": "admin@stark.local", "password": "Admin@12345" }
```
200 OK
```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": { "id": 1, "email": "admin@stark.local", "fullName": "Fleet Admin", "role": "ADMIN" }
}
```
- `expiresIn` is seconds (default 3600 = 60 min, from `JWT_EXPIRATION_MINUTES`).
- Wrong email **or** wrong password **or** disabled user → **401** Problem Details with the generic
  detail `"Invalid email or password."` (never says which field was wrong).
- Missing/blank fields → 400 with field errors.
- v2.1: too many failed attempts → **429** with `Retry-After` (Section 7.1).

### `GET /auth/me` — any role
200 OK → `{ "id": 1, "email": "...", "fullName": "...", "role": "ADMIN" | "STAFF" }`

Missing / invalid / expired token on **any** protected endpoint → **401** Problem Details.
Authenticated but wrong role → **403** Problem Details.
v2.1: a token is also rejected with 401 when its user was disabled, deleted or had its password changed after the
token was issued (Section 7.3.5); `PUT /auth/me/password` is new (Section 7.3.4).

---

## 2. Vehicles (FR-V01 … FR-V06)

### Enums
| Enum | Values |
|---|---|
| `VehicleType` | `CAR`, `SUV`, `MICROBUS`, `BUS`, `TRUCK`, `PICKUP`, `MOTORCYCLE`, `OTHER` |
| `FuelType` | `PETROL`, `OCTANE`, `DIESEL`, `CNG`, `LPG`, `HYBRID`, `ELECTRIC` |
| `VehicleStatus` | `ACTIVE`, `INACTIVE`, `MAINTENANCE` |

### VehicleResponse
```json
{
  "id": 12,
  "vehicleNumber": "DHAKA METRO-GA 11-2345",
  "vehicleType": "CAR",
  "brand": "Toyota",
  "model": "Axio",
  "manufactureYear": 2019,
  "color": "White",
  "fuelType": "OCTANE",
  "status": "ACTIVE",
  "createdAt": "2026-11-06T09:15:00Z",
  "updatedAt": "2026-11-06T09:15:00Z",
  "createdBy": "admin@stark.local"
}
```
`brand`, `model`, `manufactureYear`, `color`, `fuelType` may be `null`.

### `GET /vehicles?page=0&size=20&sort=createdAt,desc&search=` — any role
Server-side pagination and sorting (built in from v1.0 so v2.0 only extends it).
- `page` 0-based, default 0. `size` default 20, **capped at 100** (size=500 → 100, TC-11).
- `sort` = `field,direction`; allowed fields: `vehicleNumber`, `manufactureYear`, `createdAt`
  (default `createdAt,desc`). Unknown sort field → 400.
- `search` (optional, v1.0): case-insensitive partial match on vehicle number.

200 OK — **PageResponse**
```json
{
  "content": [ { "id": 12, "vehicleNumber": "DHK-1234", "status": "ACTIVE", "...": "..." } ],
  "page": 0, "size": 20, "totalElements": 57, "totalPages": 3
}
```

### `GET /vehicles/{id}` — any role
200 VehicleResponse · 404 if missing.

### `POST /vehicles` — any role (ADMIN, STAFF)
Request — **CreateVehicleRequest** / **UpdateVehicleRequest** (same shape)
```json
{
  "vehicleNumber": "DHK-1234",
  "vehicleType": "CAR",
  "brand": "Toyota",
  "model": "Axio",
  "manufactureYear": 2019,
  "color": "White",
  "fuelType": "OCTANE"
}
```
Validation (BR-03), same on client and server:
| Field | Rule |
|---|---|
| `vehicleNumber` | required, not blank, max 40 chars, must contain at least one letter or digit |
| `vehicleType` | required, one of `VehicleType` |
| `brand`, `model` | optional, max 50 |
| `manufactureYear` | optional, 1980 … (current year + 1) |
| `color` | optional, max 30 |
| `fuelType` | optional, one of `FuelType` |

New vehicles get `status = ACTIVE`.
→ **201 Created**, `Location: /api/v1/vehicles/{id}`, body VehicleResponse.

### `PUT /vehicles/{id}` — any role
Same body. → 200 VehicleResponse (updatedAt changes). 404 if missing.
BR-02: the uniqueness check ignores the vehicle being edited (keeping its own number is OK, TC-04).

### `DELETE /vehicles/{id}` — **ADMIN only**
→ 204. STAFF → 403 (TC-07). 404 if missing.

### Duplicate vehicle number (FR-V05, BR-01)
Normalization: trim → uppercase → remove all whitespace and hyphens.
`DHK-1234`, `dhk 1234` and ` DHK1234 ` all normalize to `DHK1234` and collide.
Stored as `vehicle_number` (as entered, trimmed) + `number_key` (normalized, UNIQUE).

---

## 3. Error format — Problem Details (RFC 9457), everywhere
```json
{
  "type": "https://stark-vms/errors/duplicate-vehicle-number",
  "title": "Duplicate vehicle number",
  "status": 409,
  "detail": "Vehicle number DHK-1234 is already registered.",
  "instance": "/api/v1/vehicles",
  "errors": [ { "field": "vehicleNumber", "message": "Vehicle number DHK-1234 is already registered." } ]
}
```
- `errors` is present for **400 validation** errors (one entry per invalid field), for
  **409 duplicates** (so the UI can show the message on the right field) and for business-rule 409s that belong to
  one field (`driver-already-assigned` → field `driverId`; v2.1: `duplicate-email` → field `email`). Otherwise
  absent (in particular for `stale-version`, `self-modification`, `last-admin` and `too-many-login-attempts`).
- `type` values: `.../errors/validation`, `.../errors/duplicate-vehicle-number`,
  `.../errors/not-found`, `.../errors/unauthorized`, `.../errors/forbidden`,
  `.../errors/bad-request`, `.../errors/conflict`, `.../errors/internal`, plus the v2.0 types of Section 6.8
  (`vehicle-has-history`, `driver-has-history`, `duplicate-driver-phone`, `duplicate-driver-license`,
  `assignment-not-allowed`, `driver-already-assigned`, `no-current-driver`, `file-too-large`), plus the v2.1 types
  of Section 7.5 (`too-many-login-attempts`, `stale-version`, `duplicate-email`, `self-modification`, `last-admin`).
- No stack traces, ever. A `DataIntegrityViolationException` on `uk_vehicles_number_key`
  returns the same 409 as the service-level check.

| Status | When |
|---|---|
| 200 / 204 | read, update / delete |
| 201 | created (+ `Location`) |
| 400 | validation failed, malformed JSON, bad enum, bad sort (v2.1: also missing `version` in a PUT, password policy, wrong current password on `PUT /auth/me/password`) |
| 401 / 403 | missing/invalid token (v2.1: also a token of a disabled or deleted user, or issued before the user's last password change) / role not allowed |
| 404 | id not found |
| 409 | conflict with existing data: duplicate vehicle number, duplicate driver phone / licence (v2.0), business-rule conflicts (v2.0: driver already assigned, assignment not allowed, no current driver, vehicle / driver has history); v2.1: `stale-version` (record changed since it was loaded), `duplicate-email`, `self-modification`, `last-admin` |
| 413 | uploaded file larger than 5 MB (v2.0, `.../errors/file-too-large`) |
| 429 | v2.1: too many failed logins for this e-mail or from this IP (`.../errors/too-many-login-attempts`, header `Retry-After: <seconds>`); only `POST /auth/login` returns it |
| 500 | unexpected server error (`.../errors/internal`, generic detail) |
| 503 | database or file storage temporarily unreachable (`.../errors/internal`, title "Service unavailable" / "Storage unavailable"); retry later |

---

## 4. Other
- `GET /actuator/health` (no `/api/v1` prefix) — public, for Render health checks.
- Swagger UI: `/swagger-ui.html`; OpenAPI JSON: `/v3/api-docs` — public.
- CORS: only origins in `CORS_ALLOWED_ORIGINS` (comma-separated). Never `*`.
- JSON responses of 2 KB or more are gzip-compressed when the client sends `Accept-Encoding: gzip` (browsers and
  HTTP clients do this and decompress transparently); file downloads are never compressed.

## 5. Demo accounts (local profile only — non-real credentials)
| Role | Email | Password |
|---|---|---|
| ADMIN | `admin@stark.local` | `Admin@12345` |
| STAFF | `staff@stark.local` | `Staff@12345` |

In production the first ADMIN comes from `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD`
(and optional `APP_STAFF_EMAIL` / `APP_STAFF_PASSWORD`), created at start-up only if that email does not exist.

---

# 6. Assignment 2 additions (v2.0)

All rules from Sections 1–5 still apply (auth, Problem Details, PageResponse, size cap 100, `/api/v1` prefix).
"Today" always means `LocalDate.now(clock)` with zone **Asia/Dhaka** (injected `Clock`).

## 6.1 Shared: expiry status (BR-08) — used by documents (FR-D02) and driver licences (FR-X02)
`ExpiryStatus` = `VALID` | `EXPIRING_SOON` | `EXPIRED`
- `EXPIRED` if `expiryDate < today`
- `EXPIRING_SOON` if `today <= expiryDate <= today + 30 days`
- otherwise `VALID`
- Never stored — always computed. Responses also carry `daysUntilExpiry` (int, negative when expired, 0 = expires today).

## 6.2 Vehicles — changes (FR-V07, FR-V08, FR-V09, BR-10)

**VehicleResponse** gains one field:
```json
"currentDriver": { "id": 4, "fullName": "Rahim Uddin", "phone": "01711000001" }
```
(`null` when no driver is assigned.)

### `GET /vehicles?search=&status=&type=&page=&size=&sort=`
- `search` — partial, case-insensitive; matches vehicle number (raw **or** normalized key), brand, model,
  **or current driver full name**.
- `status` — optional `VehicleStatus`; `type` — optional `VehicleType`. Invalid enum → 400.
- Sort fields unchanged: `vehicleNumber`, `manufactureYear`, `createdAt`.
- Combined filters are AND-ed. Response still the PageResponse with `totalElements`.

### `PATCH /vehicles/{id}/status` — **ADMIN only** (STAFF → 403)
Request `{ "status": "MAINTENANCE" }` → 200 VehicleResponse.
- Changing to `INACTIVE` also **closes the current driver assignment** (same transaction), because only
  ACTIVE vehicles may receive a driver (BR-05). `MAINTENANCE` keeps the current driver.
- Same status as current → 200, no change.

### `DELETE /vehicles/{id}` — BR-10 now enforced
A vehicle that has any document, maintenance record or assignment (open or closed) → **409**
`type .../errors/vehicle-has-history`, title "Vehicle has history",
detail `"Vehicle DHK-1234 has documents, maintenance or driver history and cannot be deleted. Set its status to INACTIVE instead."`
Chosen approach: **hard delete only when there is no history** (no soft delete). Vehicles without history → 204 as before.

## 6.3 Drivers (FR-DR01)
`DriverStatus` = `ACTIVE` | `INACTIVE`

**DriverRequest** (create and update — same shape)
```json
{
  "fullName": "Rahim Uddin",
  "phone": "01711-000001",
  "licenseNumber": "DK0123456L00001",
  "licenseExpiry": "2027-03-31",
  "status": "ACTIVE"
}
```
| Field | Rule |
|---|---|
| `fullName` | required, not blank, max 100 |
| `phone` | required, max 20; after normalization (trim, remove spaces/hyphens/parentheses; a leading `+880` becomes `0`) must match `^0\d{10}$` (Bangladeshi mobile, e.g. `01711000001`). Stored normalized. **UNIQUE** |
| `licenseNumber` | required, max 40; normalized (trim, uppercase, remove spaces/hyphens) into `license_key` for uniqueness; displayed as entered (trimmed). **UNIQUE** |
| `licenseExpiry` | required date (may be in the past — it is then shown as EXPIRED) |
| `status` | optional, default `ACTIVE` |

**DriverResponse**
```json
{
  "id": 4, "fullName": "Rahim Uddin", "phone": "01711000001",
  "licenseNumber": "DK0123456L00001", "licenseExpiry": "2027-03-31",
  "licenseStatus": "VALID", "daysUntilLicenseExpiry": 147,
  "status": "ACTIVE",
  "currentVehicle": { "id": 12, "vehicleNumber": "DHK-1234" },
  "createdAt": "...", "updatedAt": "...", "createdBy": "admin@stark.local"
}
```
(`currentVehicle` is `null` when the driver has no current vehicle.)

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/drivers?search=&status=&available=&page=&size=&sort=` | Any | `search` partial case-insensitive on full name, phone (normalized) and licence number. `available=true` → only ACTIVE drivers with no current vehicle (for the assign dialog). Sort fields `fullName`, `licenseExpiry`, `createdAt` (default `fullName,asc`). PageResponse<DriverResponse> |
| POST | `/drivers` | Any | 201 + Location |
| GET | `/drivers/{id}` | Any | 200 / 404 |
| PUT | `/drivers/{id}` | Any | 200. Setting `status` to `INACTIVE` closes the driver's current assignment in the same transaction |
| DELETE | `/drivers/{id}` | ADMIN | 204; driver with any assignment history → 409 `.../errors/driver-has-history` (suggest INACTIVE) |

Duplicates → 409 with `errors[0].field`:
- phone → type `.../errors/duplicate-driver-phone`, field `phone`, detail `"Phone number 01711000001 is already registered to another driver."`
- licence → type `.../errors/duplicate-driver-license`, field `licenseNumber`, detail `"Licence number DK0123456L00001 is already registered to another driver."`

Both are checked in the service **and** by DB unique constraints (`uk_drivers_phone`, `uk_drivers_license_key`) mapped to the same 409
(register them as `UniqueConstraintRule` beans like the vehicle one).

## 6.4 Driver–vehicle assignment (FR-DR02, BR-05, BR-06)

**AssignmentResponse**
```json
{
  "id": 9, "vehicleId": 12, "vehicleNumber": "DHK-1234",
  "driverId": 4, "driverName": "Rahim Uddin",
  "assignedAt": "2026-11-18T04:00:00Z", "unassignedAt": null,
  "current": true, "assignedBy": "admin@stark.local"
}
```

| Method | Path | Role | Behaviour |
|---|---|---|---|
| PUT | `/vehicles/{id}/driver` | Any | Body `{ "driverId": 4 }`. 200 AssignmentResponse (the new current assignment). |
| DELETE | `/vehicles/{id}/driver` | Any | Closes the current assignment → 204. No current driver → 409 `.../errors/no-current-driver`. |
| GET | `/vehicles/{id}/assignments?page=&size=` | Any | PageResponse<AssignmentResponse>, newest `assignedAt` first. |
| GET | `/drivers/{id}/assignments?page=&size=` | Any | Same, for a driver. |

Rules (all in one `@Transactional` service method):
1. Vehicle must be `ACTIVE` and driver must be `ACTIVE`, else 409 `.../errors/assignment-not-allowed`
   (detail says which one is not active). Missing vehicle/driver → 404. Missing `driverId` → 400 field `driverId`.
2. If that driver is already the vehicle's current driver → 200, return the existing assignment (idempotent).
3. If the driver currently drives **another** vehicle → **409** `.../errors/driver-already-assigned`,
   field `driverId`, detail `"Driver Rahim Uddin is already assigned to vehicle DHK-5678. Unassign them first."` (TC-10).
4. Otherwise close the vehicle's open assignment (if any, `unassignedAt = now`) and insert the new one.
   Flush the close before the insert so the partial unique index is not violated.
5. Partial unique indexes `uq_assignment_open_vehicle` / `uq_assignment_open_driver` (WHERE unassigned_at IS NULL)
   are the safety net; their violation maps to the same 409 `driver-already-assigned`.

## 6.5 Vehicle documents (FR-D01, FR-D02, BR-07)
`DocumentType` = `REGISTRATION` | `TAX_TOKEN` | `INSURANCE` | `FITNESS_CERTIFICATE` | `ROUTE_PERMIT` | `OTHER`

**DocumentResponse**
```json
{
  "id": 31, "vehicleId": 12, "vehicleNumber": "DHK-1234",
  "documentType": "TAX_TOKEN", "documentNumber": "TT-2026-0099",
  "issueDate": "2025-12-01", "expiryDate": "2026-11-30",
  "expiryStatus": "EXPIRING_SOON", "daysUntilExpiry": 12,
  "fileName": "tax-token.pdf", "contentType": "application/pdf", "fileSize": 182344,
  "createdAt": "...", "createdBy": "staff@stark.local"
}
```

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/vehicles/{id}/documents` | Any | JSON **array** (not paged) of DocumentResponse, ordered by `expiryDate` asc |
| POST | `/vehicles/{id}/documents` | Any | `multipart/form-data` parts: `file` (required), `documentType` (required), `documentNumber` (optional, max 60), `issueDate` (optional, ISO date), `expiryDate` (**required**, ISO date). → 201 + Location `/api/v1/documents/{docId}`, body DocumentResponse |
| GET | `/documents/{docId}` | Any | DocumentResponse |
| GET | `/documents/{docId}/file?download=false` | Any | Raw bytes, correct `Content-Type`, `Content-Disposition: inline; filename="..."` (`attachment` when `download=true`), `X-Content-Type-Options: nosniff`. Needs the Bearer token like every other endpoint; CORS must expose `Content-Disposition`. |
| DELETE | `/documents/{docId}` | ADMIN | 204 (also deletes the stored file) |
| GET | `/documents/expiring?withinDays=30&page=&size=` | Any | PageResponse<DocumentResponse> of documents with `expiryDate <= today + withinDays` (**includes already expired**), ordered `expiryDate` asc. `withinDays` 0–365, default 30, else 400 |

Upload validation (BR-07) — 400 with `errors[]` unless noted:
- `file` missing/empty → field `file`.
- Allowed: PDF, JPG/JPEG, PNG — extension **and** declared content type **and** magic bytes
  (`%PDF`, `FF D8 FF`, `89 50 4E 47`) must agree, else field `file` "Only PDF, JPG or PNG files are allowed."
- Size > 5 MB → **413** `.../errors/file-too-large` "The file is larger than 5 MB." (both the multipart limit —
  `MaxUploadSizeExceededException` — and an explicit check map to this). Set multipart max-request-size a little above 5 MB.
- `expiryDate < issueDate` → field `expiryDate` "Expiry date must be on or after the issue date."
- File stored under a random key (UUID), **never** the user's file name; the original name is kept only as metadata
  (sanitized: path stripped, control characters removed, max 255 chars).

Storage (`StorageService` interface, `app.storage.provider`):
- `database` (default) — bytes in table `stored_files` (BYTEA). Survives Render redeploys because the DB persists.
- `supabase` — Supabase Storage REST API (`SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET`).
  Optional `APP_STORAGE_KEY_PREFIX` (e.g. `prod/`) is added to the object path in the bucket only, so dev and prod
  can share a bucket; the storage key saved with the document does not change.
- A storage failure (stored bytes missing, storage not reachable) → **503** `.../errors/internal`,
  title "Storage unavailable", no internal details.

## 6.6 Maintenance (FR-M01, FR-M02, BR-09)
`MaintenanceType` = `ROUTINE_SERVICE` | `OIL_CHANGE` | `TYRE` | `BRAKE` | `ENGINE` | `ELECTRICAL` | `BODY_WORK` | `INSPECTION` | `REPAIR` | `OTHER`

**MaintenanceRequest** (create and update)
```json
{ "serviceDate": "2026-11-10", "maintenanceType": "OIL_CHANGE", "cost": 3500.00,
  "vendorName": "Navana Service Centre", "odometerKm": 45210, "notes": "Synthetic oil" }
```
| Field | Rule |
|---|---|
| `serviceDate` | required, not after today (Asia/Dhaka) |
| `maintenanceType` | required |
| `cost` | required, `>= 0`, max 2 decimals, max 9,999,999,999.99 (NUMERIC(12,2)), BDT |
| `vendorName` | optional, max 100 |
| `odometerKm` | optional integer `>= 0` |
| `notes` | optional, max 1000 |

**MaintenanceResponse** = request fields + `id`, `vehicleId`, `vehicleNumber`, `createdAt`, `updatedAt`, `createdBy`.
`cost` is a JSON number (e.g. `3500.0`).

| Method | Path | Role | Notes |
|---|---|---|---|
| GET | `/vehicles/{id}/maintenance?page=&size=` | Any | **MaintenanceHistoryResponse** = PageResponse fields **plus** `"totalCost": 12500.0` (sum over **all** records of the vehicle, not just the page) and `"recordCount"` (same as totalElements). Ordered `serviceDate` desc, then `id` desc |
| POST | `/vehicles/{id}/maintenance` | Any | 201 + Location `/api/v1/maintenance/{recordId}` |
| GET | `/maintenance/{recordId}` | Any | MaintenanceResponse |
| PUT | `/maintenance/{recordId}` | Any | 200 |
| DELETE | `/maintenance/{recordId}` | ADMIN | 204 |

## 6.7 Dashboard (FR-DB01, FR-X02)
### `GET /dashboard/summary` — any role, one call
```json
{
  "vehicles":    { "total": 26, "active": 18, "inactive": 3, "maintenance": 5 },
  "documents":   { "expired": 2, "expiringSoon": 4, "withinDays": 30 },
  "maintenance": { "month": "2026-11", "count": 7, "totalCost": 48250.0 },
  "drivers":     { "total": 12, "active": 11, "assigned": 9, "licensesExpired": 1, "licensesExpiringSoon": 2 },
  "expiringDocuments": [],
  "expiringLicenses":  [],
  "generatedAt": "2026-11-18T04:00:00Z"
}
```
- `expiringDocuments`: up to 10 DocumentResponse (expired + expiring soon), soonest expiry first.
- `expiringLicenses`: up to 10 DriverResponse (expired + expiring soon), soonest expiry first.
- "This month" = calendar month of today in Asia/Dhaka (`serviceDate` between first and last day).
- Counts come from COUNT/SUM queries — never load whole tables.

## 6.8 New Problem Details `type` values
`vehicle-has-history`, `driver-has-history`, `duplicate-driver-phone`, `duplicate-driver-license`,
`assignment-not-allowed`, `driver-already-assigned`, `no-current-driver`, `file-too-large`
(all prefixed `https://stark-vms/errors/`).

## 6.9 Database (Flyway, new migrations only — never edit V1/V2)
- `V3__create_drivers.sql` — `drivers` (`phone` UNIQUE `uk_drivers_phone`, `license_key` UNIQUE `uk_drivers_license_key`, status CHECK, audit columns)
- `V4__create_assignments.sql` — `vehicle_driver_assignments` + partial unique indexes (Section 10.3 of the guideline)
- `V5__create_documents.sql` — `stored_files` + `vehicle_documents` (CHECK `expiry_date >= issue_date`, index on `expiry_date`)
- `V6__create_maintenance.sql` — `maintenance_records` (CHECK `cost >= 0`, index `(vehicle_id, service_date DESC)`)
- All FKs `ON DELETE RESTRICT` (except `vehicle_documents → stored_files`, cleaned up by the service).

## 6.10 Demo data (local profile only)
Extend the seeder: ~12 drivers (fake names/numbers), current assignments for ~9 active vehicles plus some closed
history, ~20 documents spread across EXPIRED / EXPIRING_SOON / VALID (tiny generated PDF/PNG bytes are fine),
and ~30 maintenance records over the last 6 months including several in the current month.
Dates are relative to "today" so the dashboard always shows interesting data.

---

# 7. v2.1 hardening (login throttling, optimistic locking, user management)

> **Written before implementation (2026-11-07); implemented in the backend on 2026-11-08.** Decisions: ADR 0022
> (login throttling), ADR 0023 (optimistic locking), ADR 0024 (ADMIN user management; supersedes ADR 0017), all in
> `docs/adr/`. Points settled during implementation are marked "implementation note" or "corrected during
> implementation"; corrections from the security review of 2026-11-08 are marked "after the security review".

All rules from Sections 1–6 still apply (`/api/v1` prefix, Bearer token, Problem Details, PageResponse, size cap 100,
"now" from the injected Asia/Dhaka `Clock`). Section 7 wins on any conflict.

**Roles in v2.1**

| Role | Can additionally (v2.1) |
|---|---|
| ADMIN | manage user accounts (`/users`, Section 7.3); change own password |
| STAFF | change own password (`PUT /auth/me/password`). Every `/users` endpoint → 403 |

## 7.1 Login throttling (ADR 0022)

Applies to `POST /auth/login` (and, since the security review of 2026-11-08, to wrong current passwords on
`PUT /auth/me/password`, Section 7.3.4).
- **Per e-mail:** 5 failed logins for one e-mail (normalized: trimmed, lower-case) within 15 minutes lock that
  e-mail for 15 minutes.
- **Per IP:** 20 failed logins from one client IP within 15 minutes block that IP for 15 minutes (all e-mails).
- A failed login = any login answered with the generic 401 (wrong password, unknown e-mail, disabled user). A 400
  (missing/blank field) is not counted.
- The attempt that reaches a threshold is still answered 401; the following attempts get 429. *Settled after the
  security review (2026-11-08):* an attempt is counted **before** its password is checked, so requests sent in
  parallel can never test more than 5 passwords per e-mail (or 20 per IP) in a window; the extra ones get 429. A
  successful login gives its own count back.
- While locked or blocked, the password is **not** checked — a correct password also gets 429.
- A successful login clears the failure count of that e-mail (not of the IP).
- Unknown and existing e-mails behave identically (no account enumeration).
- Thresholds are configuration (`app.security.login.max-failures-per-email` = 5, `max-failures-per-ip` = 20,
  `lockout` = 15m). The state is kept in memory per backend instance and is reset by a restart.
- The client IP is the address seen by the backend. *Corrected after the security review (2026-11-08, findings
  SEC-1 / F2):* `server.forward-headers-strategy=native` — Tomcat reads `X-Forwarded-For` only when the request
  comes from a trusted internal proxy (Render's proxy; private address ranges) and takes the **right-most** entry
  that is not such a proxy, i.e. the address Render's proxy appended. Left-most entries the client added and the
  RFC 7239 `Forwarded` header are ignored. Before this correction (`framework`) the client chose the IP key with
  either header, which let anyone **block a chosen IP** (e.g. an office NAT address) with 20 requests, not only
  evade the per-IP limit.
- Memory bound: at most 10,000 tracked keys per map; when full, expired entries are purged and then the unlocked
  entry with the fewest failures is evicted. **A locked e-mail or IP is never evicted** (when every entry is locked,
  a new e-mail is counted under its IP only). Before the review a flood of sprayed e-mails could evict a lock.
- Residual limits: an attacker who controls many real source IPs can still spread guesses for different e-mails
  over them, and anyone who knows an e-mail can keep that account locked (Section "Consequences" of ADR 0022).

**429 Too Many Requests**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 840
Content-Type: application/problem+json
```
```json
{
  "type": "https://stark-vms/errors/too-many-login-attempts",
  "title": "Too many login attempts",
  "status": 429,
  "detail": "Too many failed login attempts. Try again in 14 minutes.",
  "instance": "/api/v1/auth/login"
}
```
- `Retry-After` = remaining lock time in seconds (rounded up, at least 1; the larger value when both the e-mail and
  the IP are locked). CORS exposes `Retry-After` (with `Location` and `Content-Disposition`).
- `detail` = "Too many failed login attempts. Try again in N minutes." with N = remaining minutes rounded up
  ("1 minute" when N = 1). No `errors[]`.
- **Client:** the login screen shows `detail` as the form error (not "Invalid email or password."); a 429 never
  triggers the 401 → login redirect.

## 7.2 Optimistic locking — `version` (ADR 0023)

### 7.2.1 `version` in responses
`VehicleResponse`, `DriverResponse`, `MaintenanceResponse` and `UserResponse` (Section 7.3.1) gain one field:
```json
"version": 3
```
A JSON integer, 0 for a new record (existing records start at 0 after migration V7). It increases by 1 on every
change of the record, whoever makes it. A `PUT` whose values equal the stored ones changes nothing and keeps the
`version` (implementation note, v2.1). Lists (`PageResponse`, `MaintenanceHistoryResponse`) carry it in every row.
Other responses (`AssignmentResponse`, `DocumentResponse`, the dashboard) are unchanged.

### 7.2.2 `version` in PUT bodies (required)
`version` is **required** in these four PUTs: the version of the record as the client loaded it. Missing → **400**
`validation` with `errors[{ "field": "version", "message": "Version is required." }]`.
Create requests (`POST`) do **not** send `version` (it is ignored if sent).

`PUT /vehicles/{id}` — **UpdateVehicleRequest** (CreateVehicleRequest is unchanged, without `version`)
```json
{
  "vehicleNumber": "DHK-1234", "vehicleType": "CAR", "brand": "Toyota", "model": "Axio",
  "manufactureYear": 2019, "color": "White", "fuelType": "OCTANE",
  "version": 3
}
```

`PUT /drivers/{id}` — **UpdateDriverRequest** = DriverRequest (Section 6.3) + `version`
```json
{
  "fullName": "Rahim Uddin", "phone": "01711-000001", "licenseNumber": "DK0123456L00001",
  "licenseExpiry": "2027-03-31", "status": "ACTIVE",
  "version": 5
}
```

`PUT /maintenance/{recordId}` — **UpdateMaintenanceRequest** = MaintenanceRequest (Section 6.6) + `version`
```json
{
  "serviceDate": "2026-11-10", "maintenanceType": "OIL_CHANGE", "cost": 3500.00,
  "vendorName": "Navana Service Centre", "odometerKm": 45210, "notes": "Synthetic oil",
  "version": 0
}
```

`PUT /users/{id}` — **UpdateUserRequest**, see Section 7.3.3.

Each returns 200 with the updated response, whose `version` is the new one. All other validation rules of these
bodies are unchanged.

### 7.2.3 Stale version → 409
If the sent `version` differs from the stored one (someone else changed the record since the client loaded it), or
a concurrent save wins the race, nothing is changed and the response is:
```json
{
  "type": "https://stark-vms/errors/stale-version",
  "title": "Record changed",
  "status": 409,
  "detail": "This driver was changed by someone else since you opened it. Reload and try again.",
  "instance": "/api/v1/drivers/4"
}
```
`detail` names the record: `vehicle`, `driver`, `maintenance record` or `user`. **No `errors[]`.**

### 7.2.4 Server-side changes also bump `version`
`PATCH /vehicles/{id}/status`, a driver set to `INACTIVE` (which also closes the assignment), disabling a user, and a
password reset (admin or own) all change the record and therefore its `version`. An edit form opened before such a
change gets 409 `stale-version` on save; this is intended. Assigning or unassigning a driver does **not** change the
`version` of the vehicle or the driver.

**Client:** keep the `version` of the loaded record and send it in the PUT. On 409 `stale-version` show the dialog
"Changed by someone else" (server `detail` + "Reloading replaces your unsaved changes.") with **Reload** (refetch the
record and refill the form; local edits are discarded) and **Cancel** (keep the form).

## 7.3 User management (ADR 0024)

### 7.3.1 UserResponse
```json
{
  "id": 7,
  "email": "officer2@stark.local",
  "fullName": "Second Officer",
  "role": "STAFF",
  "enabled": true,
  "createdAt": "2026-11-07T08:00:00Z",
  "updatedAt": "2026-11-07T08:00:00Z",
  "createdBy": "admin@stark.local",
  "version": 0
}
```
- Never contains the password hash (nor when the password was changed).
- `createdBy` is `"system"` for accounts created at start-up (`APP_ADMIN_*`, `APP_STAFF_*`), as for every other
  record written without a logged-in user (JPA auditing, FR-X01). (Corrected during implementation: this line said
  `null` before.)
- The `user` object of `POST /auth/login` and the body of `GET /auth/me` are **unchanged** (`id`, `email`,
  `fullName`, `role`).

### 7.3.2 `GET /users?search=&role=&enabled=&page=&size=&sort=` — ADMIN
- `search` — optional, partial, case-insensitive match on e-mail **or** full name.
- `role` — optional, `ADMIN` | `STAFF`; `enabled` — optional, `true` | `false`. Invalid value → 400. Filters are AND-ed.
- `sort` fields: `email`, `fullName`, `createdAt`; default `email,asc`. Unknown field → 400.
- `page` / `size` as everywhere (size capped at 100).

200 OK — `PageResponse<UserResponse>`
```json
{
  "content": [
    { "id": 1, "email": "admin@stark.local", "fullName": "Fleet Admin", "role": "ADMIN", "enabled": true,
      "createdAt": "2026-11-01T06:00:00Z", "updatedAt": "2026-11-01T06:00:00Z", "createdBy": "system", "version": 0 }
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1
}
```

### 7.3.3 Single user — ADMIN

**`GET /users/{id}`** → 200 UserResponse · 404.

**`POST /users`** — **CreateUserRequest**
```json
{ "email": "Officer2@Stark.local ", "fullName": "Second Officer", "role": "STAFF", "password": "Welcome@2026" }
```
| Field | Rule |
|---|---|
| `email` | required, valid e-mail, max 255; stored trimmed and lower-cased; **unique** |
| `fullName` | required, not blank, max 100 |
| `role` | required, `ADMIN` \| `STAFF` |
| `password` | required, at least 8 characters, at most 72 bytes in UTF-8 (the BCrypt limit); messages "Password must be at least 8 characters." / "Password must be at most 72 bytes (UTF-8)." |

→ **201 Created**, `Location: /api/v1/users/{id}`, body UserResponse (`enabled: true`, `version: 0`).
E-mail already registered (checked in the service and by `uk_users_email`) → **409**:
```json
{
  "type": "https://stark-vms/errors/duplicate-email",
  "title": "Duplicate e-mail",
  "status": 409,
  "detail": "E-mail officer2@stark.local is already registered.",
  "instance": "/api/v1/users",
  "errors": [ { "field": "email", "message": "E-mail officer2@stark.local is already registered." } ]
}
```

**`PUT /users/{id}`** — **UpdateUserRequest** (all fields required; the e-mail cannot be changed)
```json
{ "fullName": "Second Officer", "role": "STAFF", "enabled": false, "version": 0 }
```
→ 200 UserResponse · 404 · 400 (validation, missing `version`) · 409 `stale-version` · 409 `self-modification` ·
409 `last-admin` (Section 7.3.6). An `email` field in the body is ignored.

**`POST /users/{id}/password`** — admin reset — **ResetPasswordRequest**
```json
{ "newPassword": "Temp@2026x" }
```
→ **204 No Content** · 404 · 400 (password policy, field `newPassword`). Bumps the user's `version`. All tokens of
that user issued before the reset stop working (Section 7.3.5). Allowed on the ADMIN's own account too (their own
session then ends on the next request); the UI uses "Change password" for one's own account.

There is **no `DELETE /users/{id}`** (the request is answered 405): users are disabled (`enabled: false`) instead,
because audit fields (`createdBy`, `assignedBy`) refer to them.

### 7.3.4 `PUT /auth/me/password` — any role (change own password)
**ChangePasswordRequest**
```json
{ "currentPassword": "Staff@12345", "newPassword": "N3w-Staff-Pass" }
```
- → **204 No Content**.
- Wrong `currentPassword` → **400** (not 401, so the client does not end the session):
  ```json
  {
    "type": "https://stark-vms/errors/validation",
    "title": "Validation failed",
    "status": 400,
    "detail": "Current password is incorrect.",
    "instance": "/api/v1/auth/me/password",
    "errors": [ { "field": "currentPassword", "message": "Current password is incorrect." } ]
  }
  ```
- `newPassword` breaks the policy (8 characters to 72 bytes) → 400 field `newPassword`; missing fields → 400.
- *Settled after the security review (2026-11-08, findings SEC-7 / F3):* a wrong `currentPassword` counts as a
  failed login of the caller's e-mail (Section 7.1, per-e-mail limit only). After 5 failures within 15 minutes — or
  while the e-mail is locked by failed logins — the request is refused with the same **429**
  `too-many-login-attempts` + `Retry-After`, before the current password is checked. It is a 429, never a 401, so
  the client keeps its session and shows `detail` in the dialog. A correct current password clears the count.
- After the 204 the **current token is no longer valid** (it was issued before the change). **Client:** immediately
  call `POST /auth/login` with the user's e-mail and the new password, store the new session and show "Password
  changed." If that login fails, clear the session and open the login screen with "Your password was changed. Please
  log in with the new password."

### 7.3.5 Token checks on every request
For every request with a Bearer token, the backend loads the user by id and answers **401** (`.../errors/unauthorized`,
detail "The access token is no longer valid. Please log in again.") when:
- the user no longer exists, or is disabled (`enabled: false`), or
- the token was issued before the last password change of the user (admin reset or own change), or
- the token was issued before the user was last **disabled** — also after the account has been enabled again
  (*settled after the security review, 2026-11-08, finding SEC-5*: column `users.tokens_revoked_at`, V8).

*Corrected after the security review (finding SEC-3):* the issue time is compared in **milliseconds** (token claim
`iat_ms`; the standard `iat` has whole seconds only). Before, every token issued earlier in the same second as a
password reset stayed valid for its full 60 minutes. A token without `iat_ms` (issued by an older build) is compared
by its `iat`. The client's automatic re-login after its own change is a later request and is never affected.

The effective role is the **current role in the database**, not the `role` claim of the token. So disabling,
demoting or resetting the password of a user takes effect on that user's next request (a demoted ADMIN gets 403 on
ADMIN endpoints at once). Login of a disabled user is still the generic 401 "Invalid email or password.".
If the database cannot be reached for this lookup, the request ends with the usual **503** "Service unavailable"
(never a 401, so the client keeps its session).

### 7.3.6 Guards — 409, no `errors[]`
| `type` | When | `detail` |
|---|---|---|
| `.../errors/self-modification` (title "Not allowed on your own account") | an ADMIN sets `enabled: false` on their own account, or changes their own `role` away from `ADMIN` | "You cannot disable your own account or remove your own ADMIN role." |
| `.../errors/last-admin` (title "Last ADMIN") | the change would disable or demote the last enabled ADMIN (also when two ADMINs change each other at the same moment) | "At least one enabled ADMIN must remain." |

## 7.4 Endpoint list (v2.1)

All paths are relative to `/api/v1`, except `/actuator/...`, `/swagger-ui.html` and `/v3/api-docs`. "Any" = ADMIN or
STAFF. **Bold** = new or changed in v2.1.

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/login` | public | **429 when throttled (7.1)** |
| GET | `/auth/me` | Any | |
| **PUT** | **`/auth/me/password`** | **Any** | **new (7.3.4)** |
| GET | `/vehicles` | Any | rows carry **`version`** |
| POST | `/vehicles` | Any | no `version` in the body |
| GET | `/vehicles/{id}` | Any | **`version`** |
| **PUT** | **`/vehicles/{id}`** | Any | **`version` required, 409 `stale-version`** |
| DELETE | `/vehicles/{id}` | ADMIN | |
| PATCH | `/vehicles/{id}/status` | ADMIN | **bumps `version`** |
| PUT | `/vehicles/{id}/driver` | Any | |
| DELETE | `/vehicles/{id}/driver` | Any | |
| GET | `/vehicles/{id}/assignments` | Any | |
| GET | `/vehicles/{id}/documents` | Any | |
| POST | `/vehicles/{id}/documents` | Any | |
| GET | `/vehicles/{id}/maintenance` | Any | rows carry **`version`** |
| POST | `/vehicles/{id}/maintenance` | Any | no `version` in the body |
| GET | `/drivers` | Any | rows carry **`version`** |
| POST | `/drivers` | Any | no `version` in the body |
| GET | `/drivers/{id}` | Any | **`version`** |
| **PUT** | **`/drivers/{id}`** | Any | **`version` required, 409 `stale-version`** |
| DELETE | `/drivers/{id}` | ADMIN | |
| GET | `/drivers/{id}/assignments` | Any | |
| GET | `/documents/expiring` | Any | |
| GET | `/documents/{docId}` | Any | |
| GET | `/documents/{docId}/file` | Any | |
| DELETE | `/documents/{docId}` | ADMIN | |
| GET | `/maintenance/{recordId}` | Any | **`version`** |
| **PUT** | **`/maintenance/{recordId}`** | Any | **`version` required, 409 `stale-version`** |
| DELETE | `/maintenance/{recordId}` | ADMIN | |
| GET | `/dashboard/summary` | Any | |
| **GET** | **`/users`** | **ADMIN** | **new (7.3.2)** |
| **POST** | **`/users`** | **ADMIN** | **new (7.3.3)** |
| **GET** | **`/users/{id}`** | **ADMIN** | **new (7.3.3)** |
| **PUT** | **`/users/{id}`** | **ADMIN** | **new (7.3.3), `version` required** |
| **POST** | **`/users/{id}/password`** | **ADMIN** | **new (7.3.3)** |
| GET | `/actuator/health` | public | |
| GET | `/swagger-ui.html`, `/v3/api-docs` | public | |

## 7.5 New Problem Details `type` values (v2.1)
All prefixed `https://stark-vms/errors/`.

| `type` | Status | Title | `errors[]` | Returned by |
|---|---|---|---|---|
| `too-many-login-attempts` | 429 (+ `Retry-After`) | Too many login attempts | no | `POST /auth/login`, `PUT /auth/me/password` (wrong current passwords) |
| `stale-version` | 409 | Record changed | no | `PUT /vehicles/{id}`, `/drivers/{id}`, `/maintenance/{recordId}`, `/users/{id}` |
| `duplicate-email` | 409 | Duplicate e-mail | yes, field `email` | `POST /users` |
| `self-modification` | 409 | Not allowed on your own account | no | `PUT /users/{id}` |
| `last-admin` | 409 | Last ADMIN | no | `PUT /users/{id}` |

## 7.6 Database (Flyway, new migrations only — never edit an existing one)
- `V2_1__add_vehicle_version.sql`: `vehicles.version BIGINT NOT NULL DEFAULT 0`, byte-identical to the v1.1 file, so a
  database upgraded from v1.x validates (v2.0 already uses V3 for `drivers`).
- `V7__add_version_and_user_management.sql`:
  - `version BIGINT NOT NULL DEFAULT 0` on `drivers`, `maintenance_records` and `users`;
  - `users.password_changed_at TIMESTAMPTZ NULL` (`NULL` = never changed);
  - `users.created_by` already exists (V1) and is not added again.
- `V8__add_users_tokens_revoked_at.sql` (security review, 2026-11-08): `users.tokens_revoked_at TIMESTAMPTZ NULL`,
  set when an enabled account is disabled (Section 7.3.5).

## 7.7 Frontend (v2.1)
- **Users** navigation item, visible to ADMIN only; routes `/users`, `/users/new`, `/users/:id/edit` (STAFF is sent
  to the dashboard; the backend 403 is the real control).
- Users list: debounced search, role and enabled filters, sort, pagination, the usual breakpoints and
  loading / data / empty / error states; `enabled` as a text + colour badge.
- User form: e-mail read-only on edit, password only on create; "Reset password" dialog; disabling a user needs a
  confirmation.
  - "Reset password" is a row action of the users list (table and cards), hidden on the signed-in ADMIN's own row.
    It is not offered in the edit form, because the reset bumps `version` and would make an open form stale.
  - On the own account the form locks `role` and `enabled` (with the `self-modification` text as a hint); the
    backend 409 remains the real control. `self-modification` and `last-admin` are shown as a general error message.
  - Client-side password checks mirror the server: at least 8 characters (Unicode code points, as the server counts them) and at most 72 bytes in UTF-8 (counted in
    bytes, so 25 × "€" = 75 bytes is refused), plus a "repeat password" field that never leaves the browser.
- **Change password** in the user menu for every role, with the automatic re-login of Section 7.3.4.
- Edit forms send `version` and handle 409 `stale-version` (Section 7.2.4); the login screen handles 429
  (Section 7.1).
  - "Edit forms" = vehicle form, driver form, maintenance dialog, user form, and also the driver "Set INACTIVE
    instead" action (it is a `PUT /drivers/{id}` with the driver's loaded `version`; on `stale-version` the driver
    lists are refreshed and the server `detail` is shown).
  - After **Reload** the app says "Reloaded the latest version. Your unsaved changes were discarded."
  - A response without `version` (a pre-v2.1 backend) is read as `version: 0`.
- Change password: after the 204, a 401 from any other request that was still using the old token is ignored until
  the automatic re-login has finished (otherwise the user would briefly be signed out with "session expired").
