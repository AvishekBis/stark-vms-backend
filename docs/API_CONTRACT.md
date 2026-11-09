# Stark Group VMS — API Contract (Assignment 1, v1.0)

This is the contract between `stark-vms-frontend` (Flutter Web) and `stark-vms-backend` (Spring Boot).
Requirement IDs refer to *Stark_Group_VMS_Project_Guideline.docx*.

All paths are relative to **`/api/v1`**. JSON is camelCase. Dates are ISO-8601 (`2026-11-15`),
timestamps are ISO-8601 with offset (`2026-11-15T10:20:30.123+06:00` or `...Z`).

Every endpoint except `POST /auth/login` needs `Authorization: Bearer <accessToken>`.

> v1.1 = Sections 1–5 **plus Section 6** (hardening: login throttling, optimistic locking on vehicles; ADRs 0022 and
> 0023). Section 6 wins on any conflict. It was written **before implementation** (2026-11-07) and has been
> **implemented since 2026-11-08** (backend and frontend); the point settled during implementation is marked.

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
- v1.1: too many failed attempts → **429** with `Retry-After` (Section 6.1).

### `GET /auth/me` — any role
200 OK → `{ "id": 1, "email": "...", "fullName": "...", "role": "ADMIN" | "STAFF" }`

Missing / invalid / expired token on **any** protected endpoint → **401** Problem Details.
Authenticated but wrong role → **403** Problem Details.

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
Same body (v1.1: plus the required `version`, Section 6.2). → 200 VehicleResponse (updatedAt changes). 404 if missing.
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
- `errors` is present for **400 validation** errors (one entry per invalid field) and for
  **409 duplicates** (so the UI can show the message on the right field). Otherwise absent (v1.1: in particular
  for `stale-version` and `too-many-login-attempts`).
- `type` values: `.../errors/validation`, `.../errors/duplicate-vehicle-number`,
  `.../errors/not-found`, `.../errors/unauthorized`, `.../errors/forbidden`,
  `.../errors/bad-request`, `.../errors/conflict`, `.../errors/internal`, plus the v1.1 types of Section 6.3
  (`too-many-login-attempts`, `stale-version`).
- No stack traces, ever. A `DataIntegrityViolationException` on `uk_vehicles_number_key`
  returns the same 409 as the service-level check.

| Status | When |
|---|---|
| 200 / 204 | read, update / delete |
| 201 | created (+ `Location`) |
| 400 | validation failed, malformed JSON, bad enum, bad sort (v1.1: also a missing `version` in `PUT /vehicles/{id}`) |
| 401 / 403 | missing/invalid token / role not allowed |
| 404 | id not found |
| 409 | duplicate vehicle number; v1.1: `stale-version` (the vehicle changed since it was loaded) |
| 429 | v1.1: too many failed logins for this e-mail or from this IP (`.../errors/too-many-login-attempts`, header `Retry-After: <seconds>`); only `POST /auth/login` returns it |
| 500 | unexpected server error (`.../errors/internal`, generic detail) |
| 503 | database temporarily unreachable (`.../errors/internal`, title "Service unavailable"); retry later |

---

## 4. Other
- `GET /actuator/health` (no `/api/v1` prefix) — public, for Render health checks.
- Swagger UI: `/swagger-ui.html`; OpenAPI JSON: `/v3/api-docs` — public.
- CORS: only origins in `CORS_ALLOWED_ORIGINS` (comma-separated). Never `*`.
- JSON responses of 2 KB or more are gzip-compressed when the client sends `Accept-Encoding: gzip` (browsers and
  HTTP clients do this and decompress transparently).

## 5. Demo accounts (local profile only — non-real credentials)
| Role | Email | Password |
|---|---|---|
| ADMIN | `admin@stark.local` | `Admin@12345` |
| STAFF | `staff@stark.local` | `Staff@12345` |

In production the first ADMIN comes from `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD`
(and optional `APP_STAFF_EMAIL` / `APP_STAFF_PASSWORD`), created at start-up only if that email does not exist.

---

## 6. v1.1 hardening (login throttling, optimistic locking)

> **Written before implementation (2026-11-07), implemented 2026-11-08.** Decisions: ADR 0022 (login throttling) and
> ADR 0023 (optimistic locking, vehicles only), in `docs/adr/`. The same rules apply in Assignment 2 (v2.1), which also
> adds user management; v1.0/v1.1 has no user-management endpoints. A security review on 2026-11-08 led to
> corrections in 6.1, marked "after the security review".

Roles are unchanged: `POST /auth/login` is public, every other endpoint needs a token, `DELETE /vehicles/{id}` is
ADMIN only.

### 6.1 Login throttling (ADR 0022)

Applies to `POST /auth/login` only.
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
  over them, and anyone who knows an e-mail can keep that account locked (ADR 0022, "Consequences").

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
  the IP are locked). CORS exposes `Retry-After` (with `Location`).
- `detail` = "Too many failed login attempts. Try again in N minutes." with N = remaining minutes rounded up
  ("1 minute" when N = 1). No `errors[]`.
- **Client:** the login screen shows `detail` as the form error (not "Invalid email or password."); a 429 never
  triggers the 401 → login redirect.

### 6.2 Optimistic locking on vehicles (ADR 0023)

**VehicleResponse** gains one field:
```json
"version": 3
```
A JSON integer, 0 for a new vehicle (existing vehicles start at 0 after migration V2_1). It increases by 1 on every
change of the vehicle. `GET /vehicles` rows carry it too. *(Settled during implementation:)* a `PUT` that changes no
value writes nothing, so the `version` stays the same and an open form stays valid.

**`PUT /vehicles/{id}`** — **UpdateVehicleRequest** now **requires** `version` (the version of the vehicle as the
client loaded it):
```json
{
  "vehicleNumber": "DHK-1234",
  "vehicleType": "CAR",
  "brand": "Toyota",
  "model": "Axio",
  "manufactureYear": 2019,
  "color": "White",
  "fuelType": "OCTANE",
  "version": 3
}
```
- Missing (or `null`) `version` → **400** `validation` with
  `errors[{ "field": "version", "message": "Version is required." }]`.
- The `version` is compared first: a stale `version` gives the 409 below even when the new vehicle number would also
  be a duplicate.
- All other rules of Section 2 (BR-02, BR-03) are unchanged. → 200 VehicleResponse with the new `version`.
- **CreateVehicleRequest** (`POST /vehicles`) is unchanged and does not send `version` (it is ignored if sent). The
  create and update bodies therefore no longer have the same shape.

**Stale version → 409.** If the sent `version` differs from the stored one (someone else saved the vehicle since the
client loaded it), or a concurrent save wins the race, nothing is changed and the response is:
```json
{
  "type": "https://stark-vms/errors/stale-version",
  "title": "Record changed",
  "status": 409,
  "detail": "This vehicle was changed by someone else since you opened it. Reload and try again.",
  "instance": "/api/v1/vehicles/12"
}
```
No `errors[]`. Any future server-side change of a vehicle also bumps its `version`, so an edit form opened before it
gets this 409 (intended).

**Client:** keep the `version` of the loaded vehicle and send it in the PUT. On 409 `stale-version` show the dialog
"Changed by someone else" (server `detail` + "Reloading replaces your unsaved changes.") with **Reload** (refetch the
vehicle and refill the form; local edits are discarded) and **Cancel** (keep the form).

### 6.3 New Problem Details `type` values (v1.1)
All prefixed `https://stark-vms/errors/`.

| `type` | Status | Title | `errors[]` | Returned by |
|---|---|---|---|---|
| `too-many-login-attempts` | 429 (+ `Retry-After`) | Too many login attempts | no | `POST /auth/login` |
| `stale-version` | 409 | Record changed | no | `PUT /vehicles/{id}` |

### 6.4 Endpoint list (v1.1)
**Bold** = changed in v1.1.

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/auth/login` | public | **429 when throttled (6.1)** |
| GET | `/auth/me` | Any | |
| GET | `/vehicles` | Any | rows carry **`version`** |
| POST | `/vehicles` | Any | no `version` in the body |
| GET | `/vehicles/{id}` | Any | **`version`** |
| **PUT** | **`/vehicles/{id}`** | Any | **`version` required, 409 `stale-version`** |
| DELETE | `/vehicles/{id}` | ADMIN | |
| GET | `/actuator/health` (no `/api/v1` prefix) | public | |
| GET | `/swagger-ui.html`, `/v3/api-docs` | public | |

### 6.5 Database (Flyway, new migration only — never edit V1/V2)
- `V2_1__add_vehicle_version.sql` — `ALTER TABLE vehicles ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`
