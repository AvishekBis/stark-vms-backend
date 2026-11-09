# 0013. Document file storage behind `StorageService` (database BYTEA by default, Supabase optional)

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when v2.0 built FR-D01; the
  key prefix, the HTTP timeouts and the 503 mapping were added on 2026-11-07)
- **New in v2.0**

## Context

FR-D01 asks for uploads of PDF, JPG or PNG files up to 5 MB, which can be viewed and downloaded. BR-07 requires a
file type check and the 5 MB maximum.

Guideline section 13 says: check size, extension and content type; store under a random key, never the user's
file name; serve with `Content-Disposition`. Section 8 lists "Object storage with a free tier (Cloudinary,
Supabase Storage or S3-compatible)", because the Render free-tier disk is not persistent. The risk table says:
"Object storage from the start behind a StorageService interface".

Other forces:
- the course has to run with zero extra accounts, locally and on Render;
- every endpoint requires the JWT, including file downloads (`docs/API_CONTRACT.md` §6.5).

## Decision

- **Interface:** `BE/document/storage/StorageService` with `store(key, bytes, contentType)`, `load(key)` (throws
  `StorageException` if the object is missing or unreadable) and `delete(key)` ("a missing object is not an
  error").
- **Implementations, chosen by `app.storage.provider` (`APP_STORAGE_PROVIDER`):**
  - `database` (default, `@ConditionalOnProperty(..., matchIfMissing = true)`): `DatabaseStorageService` stores
    the bytes as `BYTEA` in `stored_files` (`MIG/V5__create_documents.sql`). It is `@Transactional`, so the
    bytes commit or roll back together with the metadata row.
  - `supabase`: `SupabaseStorageService` calls the Supabase Storage REST API on a **private** bucket
    (`SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET`).
    - It uses the auto-configured `RestClient.Builder`, so `spring.http.client.connect-timeout: 5s` and
      `read-timeout: 30s` apply.
    - The optional `APP_STORAGE_KEY_PREFIX` (for example `prod/`) is added to the object path only.
- **Keys and metadata:**
  - The storage key is `UUID.randomUUID()`, chosen by `DocumentService.upload`.
  - The original file name is kept only as sanitized metadata in `vehicle_documents.file_name`.
  - `vehicle_documents.storage_key` links to the file. There is no FK to `stored_files`, because the bytes may
    live in Supabase.
- **Validation before storing** (`BE/document/FileValidator.java`):
  - the extension, the declared content type and the magic bytes (`%PDF`, `FF D8 FF`, `89 50 4E 47`) must agree;
  - the file must be ≤ 5 MB;
  - multipart limits are 5 MB per file and 6 MB per request, and `server.tomcat.max-swallow-size: 20MB` lets the
    client receive the 413 `file-too-large` Problem Details instead of a reset connection.
- **Download only through the API:**
  - `GET /documents/{id}/file` returns the bytes with the stored `Content-Type`, `X-Content-Type-Options:
    nosniff` and `Content-Disposition: inline` (or `attachment` with `download=true`), built by
    `ContentDispositions` (ASCII fallback plus RFC 5987 `filename*`);
  - the frontend downloads with the bearer token and opens a short-lived Blob URL, which it revokes after one
    minute (`FE/core/platform/file_opener_web.dart`).
- **Failure handling:**
  - if saving the metadata fails after the bytes were stored, `upload` removes the stored object again;
  - a `StorageException` becomes 503 "Storage unavailable" without internal details ([0008](0008-error-format-problem-details.md)).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Local disk on the Render instance | Not persistent on the free tier: files disappear on redeploy or restart (guideline 18.4 caveats). |
| Object storage only (Supabase/S3/Cloudinary) as the single implementation | Needs an external account and secrets before the app can run at all, locally and in CI. The interface keeps it one setting away. |
| Database only, no interface | Locks the project into BYTEA. The free Render PostgreSQL has 1 GB (about 200 files at the 5 MB maximum), so real use needs object storage. |
| Public bucket / signed URLs handed to the browser | Bypasses the JWT and role model. Signed URLs would be a valid later optimisation for large files. |
| Cloudinary | Image-centric; PDFs are second-class. Supabase Storage has a plain REST API that needs no SDK. |

## Consequences

**Positive**
- The app works with no external account: documents survive Render redeploys because they live in the managed
  database.
- Switching to Supabase is configuration only (`APP_STORAGE_PROVIDER=supabase` plus three variables). No caller
  changes.
- Uploaded content is checked by its bytes, so a renamed executable is rejected.

**Negative / accepted trade-offs**
- **Database size.** With `database`, file bytes fill the 1 GB free database, and backups grow with them.
  `stark-vms-backend/README.md` says to use `supabase` for real use.
- **Memory.** Files pass through memory as `byte[]` (≤ 5 MB each); the controller returns `byte[]`.
- **The two implementations do not behave the same around transactions** (finding SOLID-BE-06), and the
  interface does not say so.
  - `DatabaseStorageService.store`/`delete` join the caller's transaction.
  - `SupabaseStorageService` makes an irreversible HTTP call at once.
  - In `DocumentService.delete` the metadata row is deleted and flushed, then the Supabase object is deleted
    *before* the transaction commits. If the commit then fails, the row points to a deleted object, and later
    downloads return 503.
  - With `supabase`, a crash between storing the object and inserting the metadata can leave an orphaned object
    in the bucket.
- With `supabase`, the upload holds a pooled DB connection during the HTTP call (bounded by the 30 s read
  timeout).

**Follow-ups**
- Document the transactional semantics on `StorageService`.
- In `DocumentService.delete`, remove the external object only after commit
  (`TransactionSynchronization.afterCommit`, or `@TransactionalEventListener(phase = AFTER_COMMIT)`), and accept
  an orphan, not a dangling row.
- A periodic orphan sweep for the bucket.
- Signed, short-lived download URLs if files grow beyond a few MB.
- Virus scanning is not done; it would be its own ADR.

## Related

- Guideline: sections 8 (File storage), 13 (file uploads), 18.1 (files per environment), 18.4 (free-tier
  caveats), 21 (risk table), FR-D01, BR-07.
- ADRs: [0005](0005-layered-architecture-and-solid-conventions.md), [0016](0016-deployment-render-and-netlify.md),
  [0018](0018-accepted-deviations-from-the-guideline.md).
- Files: `BE/document/storage/StorageService.java`, `BE/document/storage/DatabaseStorageService.java`,
  `BE/document/storage/SupabaseStorageService.java`, `BE/document/storage/StorageProperties.java`,
  `BE/document/storage/StoredFile.java`, `BE/document/DocumentService.java`, `BE/document/FileValidator.java`,
  `BE/document/ContentDispositions.java`, `stark-vms-backend/src/main/resources/application.yml`,
  `stark-vms-backend/render.yaml`, `FE/core/platform/file_opener_web.dart`, `docs/API_CONTRACT.md` §6.5.

Path prefixes: `BE/` = `stark-vms-backend/src/main/java/com/starkgroup/vms/`,
`MIG/` = `stark-vms-backend/src/main/resources/db/migration/`, `FE/` = `stark-vms-frontend/lib/`.
