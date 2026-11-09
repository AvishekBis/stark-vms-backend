# 0004. Frontend state management with Riverpod

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when the v1.0 frontend was
  built; v2.0 added the driver, document and maintenance counters and query controllers under the same rules)

## Context

The Flutter Web app needs state that several screens share:
- the session (token, user, expiry),
- the list query of each list (search, filters, page, sort),
- the server data behind lists, detail pages and the dashboard,
- form submission state with server field errors.

After any create, update or delete, every screen that shows the changed data must refresh. The dashboard numbers
must match the database (FR-DB01). Session expiry and any 401 must send the user to login (FR-AUTH-02,
guideline 14.2).

Guideline section 8 says "Provider or Riverpod (follow the course; Riverpod scales better)". Section 14.2 asks
for loading, data, empty and error states on every screen. Section 15 asks for widget tests with fakes.

## Decision

We use **Riverpod 2.6.1 (`flutter_riverpod`) without code generation**, with **go_router 18.1.2** for routing
and one `ProviderScope` created in `FE/bootstrap.dart`.

### Provider kinds and what each is used for

| Kind | Used for | Examples |
|---|---|---|
| `Provider` | Infrastructure, repositories, stateless action objects | `apiClientProvider`, `accessTokenHolderProvider`, `unauthorizedEventsProvider` (`FE/core/network/network_providers.dart`), `sharedPreferencesProvider` (overridden in `bootstrap.dart`), `clockProvider`, `fileOpenerProvider`, `documentFilePickerProvider`, `routerProvider`, every `*RepositoryProvider`, `sessionStorageProvider`, `currentUserProvider`, `vehicleActionsProvider`, `driverActionsProvider`, `assignmentActionsProvider`, `documentActionsProvider`, `maintenanceActionsProvider` |
| `NotifierProvider` (kept alive) | Change counters and list query state | `vehicleChangesProvider`, `driverChangesProvider` (`FE/core/utils/change_tracker.dart`), `documentChangesProvider`, `maintenanceChangesProvider`; `vehicleQueryProvider`, `driverQueryProvider`, `expiringDocumentsQueryProvider` |
| `NotifierProvider.autoDispose` | Form controllers with `FormSubmitState` (server field errors via `FormErrors.fromApi`) | `vehicleFormControllerProvider`, `driverFormControllerProvider`, `documentUploadControllerProvider`, `maintenanceFormControllerProvider` |
| `AsyncNotifierProvider` | The session | `authControllerProvider` |
| `AsyncNotifierProvider.autoDispose` | The login form | `loginControllerProvider` |
| `FutureProvider.autoDispose` (+ `.family`) | Server data for a screen; `AsyncValue` gives loading / data / error | `vehicleListProvider`, `driverListProvider`, `expiringDocumentsProvider`, `dashboardSummaryProvider`; families `vehicleDetailProvider(id)`, `driverDetailProvider(id)`, `vehicleDocumentsProvider(id)`, `maintenanceHistoryProvider(...)`, `vehicleAssignmentsProvider(...)`, `driverAssignmentsProvider(...)`, `assignableDriversProvider(query)` |
| `ChangeNotifierProvider` | One legacy-style listenable: the slow-request monitor behind the "Connecting to server…" banner | `slowRequestMonitorProvider` |

`StateProvider` and `StateNotifierProvider` are not used.

Screens only `watch` providers and forward user actions. They never call a repository
(`stark-vms-frontend/README.md`, Architecture).

### Session and auth state

- `AuthController` (`FE/features/auth/presentation/auth_controller.dart`) owns the session lifecycle: login,
  logout, restore on reload, auto-logout at token expiry, and sign-out on any 401.
- `build()` subscribes to `unauthorizedEventsProvider` and restores the stored session from `SessionStorage`:
  - an expired stored session is cleared with a notice;
  - otherwise the session is activated and `GET /auth/me` refreshes the user, and a 401 there clears it.
- `_activate` writes the token into the in-memory `AccessTokenHolder` and starts a `Timer` that fires at
  `expiresAt`.
- `ApiClient`'s `AuthInterceptor` (`FE/core/network/api_client.dart`) adds `Authorization: Bearer`. On a 401
  from any request except `/auth/login` it fires `onUnauthorized`.
- `AccessTokenHolder` and `UnauthorizedEvents` live in `core/network/session_signals.dart`, so `core` never
  imports the `auth` feature and there is no auth ↔ ApiClient cycle.
- Query controllers watch `currentUserProvider.select((u) => u?.id)`, so filters reset when another user signs in.
- Token storage is decided in [0007](0007-authentication-stateless-jwt.md).

### Change counters for refresh

- Each record kind has a counter (`ChangeTracker extends Notifier<int>`, `markChanged()` increments it).
- List, detail and dashboard providers `ref.watch` the counters of the data they show. Every mutation bumps the
  counters of everything it can affect.
- The vehicle and driver counters are declared in `core/utils/change_tracker.dart`, because vehicles, drivers
  and assignments all bump them. This keeps the vehicles and drivers features from importing each other.

| Mutation | Bumps |
|---|---|
| Vehicle save | vehicle counter, invalidates `vehicleDetailProvider(id)` |
| Vehicle delete | vehicle counter |
| Vehicle status change | vehicle and driver counters, invalidates the vehicle detail |
| Driver save / deactivate | driver and vehicle counters |
| Driver delete | driver counter |
| Assign / unassign | vehicle and driver counters, invalidates the vehicle detail |
| Document upload / delete | document counter |
| Maintenance save / delete | maintenance counter |

Watchers:
- `dashboardSummaryProvider` watches all four counters.
- The assignment histories watch the vehicle and driver counters.
- `vehicleDetailProvider` does not watch the counter; it is invalidated explicitly by the mutations that change it.

### Routing guard

- `FE/core/router/app_router.dart` builds one `GoRouter` with `refreshListenable` fed by
  `ref.listen(authControllerProvider)`.
- The pure function `authRedirect` decides every redirect:
  - restoring the session → `/splash?from=`;
  - signed out → `/login?from=`;
  - signed in on `/login` or `/splash` → the target, or `/dashboard`.
- `_safeTarget` accepts only in-app paths (no open redirect).
- A `ShellRoute` wraps the signed-in routes in `AppShell`, and `onException` goes to `/dashboard`.
- Filters and tabs are URL query parameters (`status`, `type`, `tab`, `withinDays`), so a reload or a deep link
  restores the view.
- Role checks in the UI only hide buttons. The backend enforces roles ([0007](0007-authentication-stateless-jwt.md)).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `provider` package (`ChangeNotifier` + `Provider`) | Allowed by the guideline. It depends on the widget tree (`BuildContext`) for lookup, has no built-in async state (`AsyncValue`), and overriding dependencies in tests is clumsier. The guideline itself says Riverpod scales better. |
| Bloc / Cubit | Not named in the guideline. It needs an event and a state class per screen, which is more boilerplate for an app that mostly loads pages and submits forms; testing is good but not better than Riverpod overrides. |
| `setState` and passing callbacks | No shared state: the dashboard would not refresh after a change on another screen. |
| `riverpod_generator` / `riverpod_annotation` | Needs `build_runner` and generated files in the build and CI. The project has 49 top-level providers, which are manageable by hand. |
| Refresh by re-fetching in each screen's `initState` or by a global event bus | Easy to forget, and screens would know about each other. Counters make "what depends on what" visible in the provider that watches. |
| `StateNotifier` / `StateProvider` | Superseded by `Notifier` in Riverpod 2. |

## Consequences

**Positive**
- Widget and unit tests replace repositories and controllers through provider overrides
  (`stark-vms-frontend/test/helpers/fakes.dart`, `fakes_v2.dart`, `pump_app.dart`); no network in tests.
- `AsyncValue` plus the shared state views give every screen loading / data / error (and empty) states.
- After any mutation the dashboard and lists show the database state (FR-DB01) without a manual refresh.

**Negative / accepted trade-offs**
- The counters are manual. A new mutation that forgets `markChanged()`, or bumps the wrong counter, shows stale
  data, and no test catches it automatically.
- Logic is duplicated across the three query controllers and two `*Actions` classes: pagination and sort, and
  "step back a page after deleting the last row". The query value classes repeat `copyWith`, equality and
  `toQueryParameters`, and their `copyWith` uses untyped `Object?` parameters (finding SOLID-FE-07).
- Maintenance pagination and "step back a page" live in widget state and run inside `build`. The "was edited"
  audit rule is also computed in the widget (finding SOLID-FE-06).
- The dashboard navigates to vehicles through the URL but to drivers by changing the drivers query controller
  (finding SOLID-FE-12, see 0003).
- The app stays on Riverpod 2.x while 3.x exists.

**Follow-ups**
- One generic paged-query controller, with "step back after deleting the last row" in one place; move the
  maintenance paging out of the widget.
- Dashboard → drivers through query parameters, like vehicles.
- Plan a Riverpod 3 upgrade in its own ADR.

## Related

- Guideline: sections 8 (state management), 14.2 (four states, ApiClient with 401 → login, debounce), FR-AUTH-02,
  FR-DB01.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0007](0007-authentication-stateless-jwt.md).
- Files: `FE/bootstrap.dart`, `FE/core/utils/change_tracker.dart`, `FE/core/router/app_router.dart`,
  `FE/core/network/api_client.dart`, `FE/core/network/session_signals.dart`,
  `FE/features/auth/presentation/auth_controller.dart`, `FE/features/*/presentation/*_providers.dart`,
  `stark-vms-frontend/pubspec.yaml`.

Path prefix: `FE/` = `stark-vms-frontend/lib/`.
