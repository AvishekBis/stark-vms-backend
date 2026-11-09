# 0004. Frontend state management with Riverpod

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken when the v1.0 frontend was
  built)

## Context

The Flutter Web app needs state that several screens share:
- the session (token, user, expiry);
- the vehicle list query (search, page, sort);
- the server data behind the list, the detail page and the dashboard;
- form submission state with server field errors (the duplicate-number 409 belongs under the number field,
  FR-V05).

After a create, update or delete, the list and the dashboard must refresh. Session expiry and any 401 must send
the user to login (FR-AUTH-02, guideline 14.2).

Guideline references:
- section 8: "Provider or Riverpod (follow the course; Riverpod scales better)";
- section 14.2: loading, data, empty and error states on every screen;
- section 15: widget tests with fakes.

## Decision

We use **Riverpod 2.6.1 (`flutter_riverpod`) without code generation**, with **go_router 18.1.2** for routing,
and one `ProviderScope` created in `FE/bootstrap.dart`.

### Provider kinds and what each is used for

| Kind | Used for | Providers |
|---|---|---|
| `Provider` | Infrastructure, repositories, stateless actions | `apiClientProvider`, `accessTokenHolderProvider`, `unauthorizedEventsProvider` (`FE/core/network/network_providers.dart`), `sharedPreferencesProvider` (overridden in `bootstrap.dart`), `clockProvider`, `routerProvider`, `authRepositoryProvider`, `sessionStorageProvider`, `vehicleRepositoryProvider`, `currentUserProvider`, `vehicleActionsProvider` |
| `NotifierProvider` (kept alive) | Change counter and list query state | `vehicleChangesProvider` (`VehicleChangeTracker`), `vehicleQueryProvider` (`VehicleQueryController`) |
| `NotifierProvider.autoDispose` | The vehicle form, with server field errors (`VehicleFormState`) | `vehicleFormControllerProvider` |
| `AsyncNotifierProvider` | The session | `authControllerProvider` |
| `AsyncNotifierProvider.autoDispose` | The login form | `loginControllerProvider` |
| `FutureProvider.autoDispose` (+ `.family`) | Server data for a screen; `AsyncValue` gives loading / data / error | `vehicleListProvider`, `totalVehiclesProvider`, `recentVehiclesProvider`; family `vehicleDetailProvider(id)` |
| `ChangeNotifierProvider` | The slow-request monitor behind the "Connecting to server…" banner | `slowRequestMonitorProvider` |

- `StateProvider` and `StateNotifierProvider` are not used.
- Screens only `watch` providers and forward user actions. They never call a repository
  (`stark-vms-frontend/README.md`, Architecture).

### Session and auth state

- `AuthController` (`FE/features/auth/presentation/auth_controller.dart`) owns the session lifecycle: login,
  logout, restore on reload, auto-logout at token expiry, and sign-out on any 401.
- `build()` subscribes to `unauthorizedEventsProvider` and restores the stored session from `SessionStorage`:
  - an expired stored session is cleared with a notice;
  - otherwise the session is activated, and `GET /auth/me` refreshes the user (a 401 there clears the session).
- `_activate` writes the token into the in-memory `AccessTokenHolder` and starts a `Timer` that fires at
  `expiresAt`.
- `ApiClient`'s `AuthInterceptor` (`FE/core/network/api_client.dart`) adds `Authorization: Bearer`. On a 401 from
  any request except `/auth/login` it fires `onUnauthorized`.
- `AccessTokenHolder` and `UnauthorizedEvents` live in `core/network/session_signals.dart`, so `core` never imports
  the `auth` feature for the token, and there is no auth ↔ ApiClient cycle.
- `VehicleQueryController` watches `currentUserProvider.select((u) => u?.id)`, so the list query resets when
  another user signs in.
- Token storage is decided in [0007](0007-authentication-stateless-jwt.md).

### Change counter for refresh

- `vehicleChangesProvider` is a `Notifier<int>`, and `markChanged()` increments it.
- `vehicleListProvider`, `totalVehiclesProvider` and `recentVehiclesProvider` `ref.watch` it.
- Every mutation bumps it:
  - the vehicle form save (`vehicle_form_controller.dart`), which also invalidates `vehicleDetailProvider(id)`;
  - `VehicleActions.delete`, which also steps back one page when it removed the last row of a later page.
- `vehicleDetailProvider` deliberately does not watch the counter, so a deleted vehicle is not re-fetched while its
  page closes. It is invalidated explicitly instead.

### Routing guard

- `FE/core/router/app_router.dart` builds one `GoRouter` with `refreshListenable` fed by the auth state.
- The pure function `authRedirect` decides every redirect:
  - restoring the session → `/splash?from=`;
  - signed out → `/login?from=`;
  - signed in on `/login` or `/splash` → the target, or `/dashboard`.
- `_safeTarget` accepts only in-app paths, so there is no open redirect.
- A `ShellRoute` wraps the signed-in routes in `AppShell`, and `onException` goes to `/dashboard`.
- Role checks in the UI only hide buttons. The backend enforces roles ([0007](0007-authentication-stateless-jwt.md)).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| `provider` package (`ChangeNotifier` + `Provider`) | Allowed by the guideline. It depends on the widget tree (`BuildContext`) for lookup, has no built-in async state (`AsyncValue`), and overriding dependencies in tests is clumsier. The guideline itself says Riverpod scales better. |
| Bloc / Cubit | Not named in the guideline; an event and a state class per screen is more boilerplate for an app that loads pages and submits forms. |
| `setState` and passing callbacks | No shared state: the dashboard would not refresh after a change on the list. |
| `riverpod_generator` / `riverpod_annotation` | Needs `build_runner` and generated files in the build and CI for about twenty providers. |
| Re-fetch in each screen's `initState` | Easy to forget, and screens would know about each other. The counter makes "what depends on what" visible in the provider that watches it. |
| `StateNotifier` / `StateProvider` | Superseded by `Notifier` in Riverpod 2. |

## Consequences

**Positive**
- Widget and unit tests replace repositories and controllers through provider overrides
  (`stark-vms-frontend/test/helpers/fakes.dart`, `pump_app.dart`). No network is used in tests.
- `AsyncValue` and the shared state views give every screen loading, data, empty and error states.
- The pattern scales to v2.0: Assignment 2 adds one counter per record kind on the same rules.

**Negative / accepted trade-offs**
- The counter is manual. A new mutation that forgets `markChanged()` shows stale data, and no test catches it
  automatically.
- `VehicleChangeTracker` is specific to vehicles. A second record kind would need a generic tracker; Assignment 2
  introduced `core/utils/change_tracker.dart` for this.
- The dashboard reads the vehicles repository directly (see 0003).

**Follow-ups**
- Plan a Riverpod 3 upgrade in its own ADR.

## Related

- Guideline: sections 8 (state management), 14.2 (four states, ApiClient with 401 → login, debounce), FR-AUTH-02,
  FR-V05.
- ADRs: [0003](0003-data-ownership-and-cross-context-contracts.md), [0007](0007-authentication-stateless-jwt.md).
- Files: `FE/bootstrap.dart`, `FE/core/router/app_router.dart`, `FE/core/network/api_client.dart`,
  `FE/core/network/session_signals.dart`, `FE/features/auth/presentation/auth_controller.dart`,
  `FE/features/vehicles/presentation/vehicle_providers.dart`, `FE/features/vehicles/presentation/vehicle_form_controller.dart`,
  `FE/features/dashboard/presentation/dashboard_providers.dart`, `stark-vms-frontend/pubspec.yaml`.

Path prefix: `FE/` = `stark-vms-frontend/lib/`.
