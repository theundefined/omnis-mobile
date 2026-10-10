# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Omnis Mobile is a native Android app (Kotlin, Jetpack Compose/Material 3) that acts as a mobile client for the OMNIS/Ex Libris Primo library system used by Polish libraries (Biblioteka Raczyńskich, Biblioteka Narodowa, UAM, UJ, etc.). It lets a user manage multiple library accounts across different institutions ("tenants") in one app: log in, view/group loans, and renew them. It's the mobile counterpart to [omnis-py](https://github.com/theundefined/omnis-py).

## Commands

- Build debug APK: `./gradlew assembleDebug`
- Build release APK/bundle: `./gradlew assembleRelease bundleRelease` (requires `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` env vars; falls back to debug signing if `RELEASE_KEYSTORE_PASSWORD` is unset)
- Run unit tests: `./gradlew test` (single test: `./gradlew test --tests "com.theundefined.omnis.RepositoryTest"`)
- Run instrumented tests: `./gradlew connectedAndroidTest`
- Lint: `./gradlew lint` (note: `checkReleaseBuilds` is disabled in `app/build.gradle.kts`)
- Cut a release: `./release.sh [major|minor|patch|vX.Y.Z]` — bumps `versionName`/`versionCode` in `app/build.gradle.kts`, commits as `release: vX.Y.Z`, and pushes to `main`. Must be run from a clean working tree on `main`. Does **not** build or tag locally — GitHub Actions does that (see below).

Requires JDK 21 and the Android SDK. `local.properties` and `.env` hold local-only config and are gitignored.

## CI/CD architecture (important — don't try to replicate manually)

This project uses a "gatekeeper" CD model defined in `.github/workflows/build.yml`:
- Every push/PR builds `assembleDebug` only, to catch compile errors cheaply.
- A push to `main` whose HEAD commit message matches `^release: (vX.Y.Z)$` (i.e. produced by `release.sh`) triggers a full `assembleRelease`/`bundleRelease`, signed with the production keystore (`RELEASE_KEYSTORE` secret, decoded to `omnis.jks`).
- Only if that release build succeeds does GitHub Actions create the git tag and a GitHub Release with the APK/AAB attached. There is no local tagging — `release.sh` only pushes the version-bump commit.
- Sentry release/source-map (ProGuard mapping) upload is wired into the release build via `sentry.properties`, generated in CI from `SENTRY_AUTH_TOKEN`.
- The `ui-tests` job calls `.github/workflows/ui-tests.yml` (reusable workflow): instrumented Compose tests from `app/src/androidTest` on an emulator (`reactivecircus/android-emulator-runner`), run against the public demo server (`MOCK_TENANT`, omnis-mock on Render) with the demo account. On other branches `ui-tests.yml` runs on its own `push` trigger. Tests only read data — the demo account is shared, so never add tests that renew loans or place/cancel holds.
- After a successful release build **and green UI tests**, the `publish-play` job uploads the same signed `.aab` (passed as a workflow artifact) to the Play **internal** track via `.github/scripts/publish_to_play.py`. Auth is keyless Workload Identity Federation (provider path and service account email are `google-play` environment secrets `GCP_WIF_PROVIDER`/`PLAY_SERVICE_ACCOUNT`, not in the repo; GCP pool `github`, provider `omnis-mobile`, trusting only this repo's ID + `refs/heads/main` + GitHub environment `google-play`), impersonating a service account that Play Console only allows to release to testing tracks. The script is idempotent (already-uploaded/already-live versionCode is a no-op), so the job can be re-run. Release notes are generated from commit subjects since the previous tag. To (re-)publish an existing release manually: `gh workflow run build.yml -f tag=vX.Y.Z`. Python deps are hash-pinned in `.github/scripts/requirements.txt` (regenerate with `pip-compile --generate-hashes`); actions are pinned by commit SHA.

When changing versioning, signing, or the release flow, `release.sh` and `.github/workflows/build.yml` must stay in sync.

## Architecture

MVVM + Kotlin Flow, single `MainActivity` (no navigation library — `MainScreen` composable switches views internally). Data flows one way: `OmnisRepository` → `OmnisViewModel` (`StateFlow<UiState>`) → Compose UI.

```
data/model/       Account, Tenant, Loan, and raw API response DTOs (Models.kt)
data/model/Tenants.kt   Hardcoded list of known library tenants (KNOWN_TENANTS)
data/local/        AccountManager — encrypted on-device persistence
data/remote/       OmnisApi — Retrofit interface for the OMNIS/Primo backend
data/repository/   OmnisRepository — orchestrates login + API calls + persistence
ui/OmnisViewModel.kt   UI state, grouping/sorting, refresh orchestration
ui/components/     Compose screens (MainScreen, SettingsScreen, AddAccountForm, LoanComponents)
```

Key points to understand before making changes:

- **Tenant model**: A `Tenant` (`data/model/Tenants.kt`) captures everything needed to talk to one library's Primo instance: `baseUrl`, `institution`, and `view` (Primo's institution/view codes). Adding support for a new library means adding an entry to `KNOWN_TENANTS` with the right values — no code changes needed. Users can also add a library that isn't listed by pasting a link to its catalog ("Mojej biblioteki nie ma na liście" in `AddAccountForm`): `parseCatalogLink` reads `baseUrl`/`institution`/`view` from the `vid=` param, and `OmnisRepository.fetchCustomTenant` checks the public view configuration (`/primaws/rest/pub/configuration/vid/{vid}`) for the name and an `ALMA` (username+password) auth profile — SSO-only and NDE-only catalogs are rejected with a specific message. `Tenant.authProfile` (null = `"Alma"`) carries a non-default ALMA profile name into every `login` call.
- **Auth flow has no persistent session/token storage.** `OmnisRepository` re-logs-in (via `OmnisApi.login`) on essentially every operation (`fetchAccountProfile`, `getLoansForAccount`, `renewLoan`) because the plaintext username/password is stored per-`Account` and used to obtain a fresh JWT each time, rather than caching/refreshing a token. Each repository call creates its own short-lived Retrofit/OkHttp client (`createClient`) with an in-memory (non-persistent) cookie jar, and login requires first hitting `/discovery/search` to seed session cookies before calling `/primaws/suprimaLogin`.
- **Placing a hold is the one exception to per-call re-login**: `OmnisRepository.HoldSession` keeps one client + token in memory for the duration of the hold dialog, because the item's `link-to-service` embeds a session-scoped `physicalServiceId` — the form GET and the submit POST must run in the session that fetched the items. Never persisted; any error drops it and the next attempt logs in again.
- **Credentials are stored encrypted, not hashed** (`AccountManager` uses `EncryptedSharedPreferences`) because the password is needed for repeated re-authentication above. Do not "improve" this to hash the password without understanding this constraint.
- **Display name comes from the JWT payload**, decoded manually (base64 + Gson `JsonParser`, not a JWT library) inside `OmnisRepository`, duplicated across `loginAndAddAccount` and `fetchAccountProfile`.
- **Loan caching**: `AccountManager` persists the last-fetched loans per account (`saveCachedLoans`/`getCachedLoans`). `OmnisViewModel` shows cached loans immediately on load/error and only replaces them after a successful network refresh, so the UI is offline-tolerant. When editing refresh logic, preserve this cache-then-refresh pattern.
- **Grouping/sorting is client-side state**, not server-driven: `OmnisViewModel.updateGroupedLoans` groups the flattened `(Account, Loan)` list either by account display name or by `library - location`, then sorts within each group (`GroupingMode`, `SortMode` enums). `reapplyGroupingAndSorting` re-derives groups from whatever is already in `uiState.loans`/cache without hitting the network — use it (not `refreshAllLoans`) for grouping/sort toggles.
- **Serialization is split**: kotlinx.serialization (`@Serializable`) is used for on-device persistence of `Account`/`Loan` (via `AccountManager`'s `Json`), while Gson (`GsonConverterFactory`) is used for Retrofit API responses. This is why model classes like `Loan` carry both `@SerialName` and `@SerializedName` annotations on every field — keep both in sync when adding fields. GSON is intentionally not used for anything R8/release-sensitive per `GEMINI.md`.
- **Two model shapes per API resource**: `Loan` (internal/persisted model, includes `renewable: Boolean`, `accountId`, `ownerName`) vs `LoanResponseItem` (raw API DTO, includes `renew: String?`). `OmnisRepository.getLoansForAccount` is the mapping boundary between them.
- Networking: OkHttp client explicitly disables redirects (`followRedirects(false)`, `followSslRedirects(false)`) — this was a deliberate fix (see commit `1f64abb`), don't re-enable without checking why.
- Release build currently has `isMinifyEnabled = false` in `app/build.gradle.kts` — this is intentional (see `GEMINI.md`: prior R8/`-dontobfuscate` attempts caused regressions), not an oversight.
- Sentry DSN is injected via Gradle `manifestPlaceholders["sentryDsn"]`, sourced from the `SENTRY_DSN` project property or env var, so it never lands in source control.
- App strings are localized (`res/values` = Polish default, `res/values-en` = English); keep both in sync when adding UI copy.

See `GEMINI.md` for the fuller (Polish-language) engineering knowledge base this project maintains — CI/CD model, signing/security decisions, and a backlog of planned features (barcode library card, bulk renew, background due-date notifications, branch-view borrower grouping).
