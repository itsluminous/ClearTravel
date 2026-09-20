# AGENTS.md — ClearTravel

Instructions for AI agents (and humans) working in this repo. Read fully before editing.

## Project overview

ClearTravel is an offline-first, Material You (dynamic color) Android travel companion:
train journeys (PNR status via rule-driven WebView scraping), flight journeys
(boarding-pass import, status polling, gate/delay notifications), trip itineraries on a
map, and packing checklists. **Serverless**: no custom backend — Room is the single
source of truth on device; live data comes from third-party public sources called from
the app; a Google Cloud project supplies only client-side keys (Maps SDK, OAuth for
optional Calendar/Drive sync). Full product spec: `/Users/kupraki/repo/prompt.md`.
Package root: `com.itsluminous.cleartravel`.

## Module map (module = agent ownership boundary)

| Module | Contents | Status |
|---|---|---|
| `app` | Hilt application, MainActivity (single-activity Compose), bottom bar (Trips / Journeys / Checklist / Menu), NavHost, Journeys Trains\|Flights segmented composition, launcher icon, manifest (Maps key placeholder), androidTest e2e suite | skeleton |
| `core:designsystem` | `ClearTravelTheme` (dynamic color + #0B57D0 seed fallback), typography ≥16sp body, `EmptyState`, `ChipRow`, `ExplainableIcon`, `ClearTravelCard`, `ClearTravelFab` | done (skeleton scope) |
| `core:model` | `SyncableEntity` (UUID + updatedAt + tombstone, ADR-002), `EntityIds`, `ThemeMode`, future domain models/enums — **contract: changes need an ADR** | skeleton |
| `core:database` | Room entities/DAOs/converters; `ClearTravelDatabase` lands with the first entity — **contract: changes need an ADR** | stub |
| `core:data` | Repository interfaces + Room-backed impls, `TrainStatusProvider`/`FlightStatusProvider` contracts (Hilt-bound), settings DataStore | stub |
| `core:notifications` | Channels (trains/flights/reminders), builders, deep links, POST_NOTIFICATIONS permission gate | stub |
| `core:google` | Google linking (Credential Manager), Calendar sync (dedicated "ClearTravel" calendar), Drive uploads/backups | stub |
| `core:scrape` | Rule-driven WebView scraper engine; per-site JSON rule files in assets + HTML fixtures (ADR-003) | stub |
| `core:ocr` | PDF→bitmap→preprocess→ML Kit text recognition + BCBP barcode decode; pure extraction functions + OCR-text fixtures | stub |
| `core:testing` | `MainDispatcherRule`, generic `inMemoryDatabase<T>()`, `Fixtures` builders — exposed as MAIN source | done (skeleton scope) |
| `feature:trains` | Train tickets CRUD, detail sheet, PNR refresh (foreground WebView), SMS/OCR prefill, archive | stub (`TrainsContent`) |
| `feature:flights` | Flight CRUD, boarding-pass import, status providers, WorkManager polling + notifications | stub (`FlightsContent`) |
| `feature:itinerary` | Trips tab: trips, day-grouped items, timeline + Google Maps view | stub (route + empty state) |
| `feature:checklist` | Checklist tab: per-trip checklists, preset templates + manager | stub (route + empty state) |
| `feature:menu` | Menu tab: Settings (theme, presets, API keys, Google), Backup/Restore, About | stub (route + empty state) |

**Ownership boundaries:** feature modules depend ONLY on `core:*`, NEVER on each other;
cross-feature interaction goes through `core:data` contracts. The app module is the
only composition point (Journeys tab). `core:designsystem` never depends on
data/database/features. UI never touches Room DAOs or WebView engines directly.

## Build / test / lint commands

```bash
./gradlew ktlintCheck                # style (ktlint_official; run ktlintFormat to fix)
./gradlew lintDebug                  # Android lint — HardcodedText/SetTextI18n are ERRORS
./gradlew testDebugUnitTest          # unit tests (Robolectric DAO tests included)
./gradlew assembleDebug              # app/build/outputs/apk/debug/ClearTravel-debug.apk

# the full local quality gate (same as CI) — ALWAYS redirect to build.log (git-ignored):
./gradlew ktlintCheck lintDebug testDebugUnitTest assembleDebug > build.log 2>&1
tail -n 30 build.log                 # inspect with tail/grep; output is huge
```

Run ktlint + unit tests before EVERY commit. No milestone is done until its tests
exist and the full gate passes.

## Hard rules

1. **No hardcoded user-visible strings** — not in Kotlin, not in Compose, not in
   notifications. Every string goes in a `res/values/strings.xml` (module-local for
   feature/core modules, `app` for shell strings), prefixed with the module namespace
   (`trains_`, `flights_`, `itinerary_`, `checklist_`, `menu_`). Lint `HardcodedText`/
   `SetTextI18n` are error-severity and CI-blocking. Single language (English) at
   launch, but the no-hardcoding discipline is non-negotiable from day one.
2. **Offline-first**: reads from Room only; every screen renders with no network.
   Status fetches write to Room; UI observes Room via Flow. Never block UI on network.
3. **UUID + `updated_at` + tombstones on every entity** (ADR-002): implement
   `SyncableEntity`, bump `updated_at` on every write, soft-delete only. Required by
   backup merge — never add an entity without these.
4. **Behavior-as-data** (ADR-003): scrape rules, check-in windows, checklist presets,
   OCR patterns are versioned data files, each with its own fixture test. A rule file
   without a fixture fails CI.
5. **Contract freeze**: changes to `core:model`, `core:database`, or repository
   interfaces in `core:data` require an ADR entry in `docs/decisions.md` in the same
   change. Additive extensions are fine with an ADR; breaking changes need strong
   justification.
6. **Secrets** live in `local.properties` (git-ignored: `MAPS_API_KEY`,
   `GOOGLE_WEB_CLIENT_ID` — the *Web application* OAuth client, never an Android one;
   wrong type fails linking with error `[28444]`) or in encrypted DataStore for
   user-entered API keys. Empty values are safe defaults. NEVER commit
   `local.properties`, `*.log`, `.DS_Store`, keystores.
7. **Git**: NEVER push, never add a remote, never force-push. Conventional Commits,
   imperative mood, subject ≤50 chars (`feat(trains): add ticket form`). Commit after
   every completed task. Never commit generated `build/` output.
8. Every icon-only control uses `ExplainableIcon` (long-press explanation); empty
   screens use `EmptyState`; errors/confirmations use snackbars, not toasts; FAB
   (`ClearTravelFab`) is the primary add action per tab; detail views are bottom
   sheets; body text ≥16sp.

## Working conventions

- **ADR process**: any contract-adjacent change (new repository method, new Room
  query, cross-feature component) gets a numbered entry in `docs/decisions.md` in the
  same change — what + why, additive-only.
- **Tests alongside features, never retrofitted**: ViewModels, repositories, DAOs
  (Robolectric via `core:testing`'s `inMemoryDatabase<T>()`), parsers against recorded
  fixtures (HTML for scrape rules, OCR text for extraction — including a
  garbage/changed-markup fixture asserting the fallback path).
- **Providers are Hilt-bound interfaces** (`TrainStatusProvider`,
  `FlightStatusProvider` in `core:data`): WebView scrape provider is the default,
  API provider optional, mock/manual always available — the UI never depends on a
  concrete provider.
- **Versioning**: `versionName` default lives in `app/build.gradle.kts` (currently
  `0.1.0`), overridable with `-PappVersionName` / `-PappVersionCode` (the release
  workflow derives them from the `v*` tag).

## Anti-stall emulator rules (macOS)

- Every adb/emulator command in an agent run MUST be wrapped in a timeout; UI
  verification is done via screenshots (`adb exec-out screencap -p > shot.png`)
  captured non-interactively — never block on an interactive command or an emulator
  window.
- Use an EXISTING AVD (`$HOME/Library/Android/sdk/emulator/emulator -list-avds`);
  do not create AVDs or reinstall SDK components.
- If a physical phone is attached over adb it is READ-ONLY — never install,
  uninstall, clear data, or push files to it; all install/verify work happens on the
  emulator.

```bash
$HOME/Library/Android/sdk/emulator/emulator -avd <existing-avd-name> &
adb wait-for-device
./gradlew installDebug
adb shell am start -n com.itsluminous.cleartravel/.MainActivity
./gradlew connectedDebugAndroidTest    # instrumented tests on the running emulator
```

## Release process

Tag `main` with `vX.Y.Z` → `.github/workflows/release.yml` re-runs the full gate,
builds a signed release APK (secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`; forks fall back to a debug-signed `*-debugsigned.apk`)
and attaches it to a GitHub Release; `versionName` derives from the tag. Bump the
default `versionName` in `app/build.gradle.kts` alongside each release so debug builds
stay in step.
