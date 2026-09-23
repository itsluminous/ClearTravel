# AGENTS.md — Clear Travel

Instructions for AI agents (and humans) working in this repo. Read fully before editing.

## Project overview

Clear Travel is an offline-first, Material You (dynamic color) Android travel companion:
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
| `app` | Hilt application, MainActivity (single-activity Compose), bottom bar (Trips / Journeys / Checklist / Documents / Menu), NavHost, Journeys Trains\|Flights segmented composition, deep links, launcher icon, manifest (Maps key placeholder), androidTest e2e suite (4 classes, Hilt test modules) | done |
| `core:designsystem` | `ClearTravelTheme` (dynamic color + #0B57D0 seed fallback), typography ≥16sp body, `EmptyState`, `ChipRow`, `FullWidthFilterRow`/`FullWidthFilterChip`, `LocalDatePickerDialog`, `DateFormats`, `ExplainableIcon`, `ClearTravelCard`, `ClearTravelFab`, `TextEditDialog`, `PasswordField`, reorderable-list helpers, `ShellChromeController` seam, `DocumentViewerScreen` (shared image/PDF viewer: zoom/rotate, fullscreen, landscape rail; ADR-030/034), `CardShare` (off-screen card renderer + share intents, ADR-039) — anything two features need lives here (ADR-036) | done |
| `core:model` | `SyncableEntity` (UUID + updatedAt + tombstone, ADR-002), `EntityIds`, `ThemeMode`, domain models/enums — **contract: changes need an ADR** | done |
| `core:database` | Room entities/DAOs/converters, `ClearTravelDatabase` (schema v3: v2 `train_coaches`, v3 `travel_documents`; committed `schemas/` + `MigrationTest`) — **contract: changes need an ADR**. Encryption lives BELOW it (SQLCipher factory in `core:data`), so schemas and DAO tests are untouched | done |
| `core:data` | Repository interfaces + Room-backed impls, `TrainStatusProvider`/`FlightStatusProvider` contracts (Hilt-bound), settings DataStore (+ `lockTiming`, `backupSchedule`), backup export/merge — format v2 password envelope (ADR-015/031, `docs/backup-format.md`; ADR-040 derived built-in preset item ids + seeded-duplicate collapse on import), `JourneyAddRequestBus` cross-tab seam (ADR-028), `security/`: `VaultKeyedOpenHelperFactory` (lazy SQLCipher), plaintext→encrypted DB + file migrations, `AppFileLayout`, `SecureStorageInitializer`, `share/`: ADR-039 share-link codec (`ShareLinkCodec`, payload DTOs, mappers) + `SharedContentImporter` (id-stable upsert) | done |
| `core:security` | ADR-031: `KeyVault` (random DEK wrapped by PBKDF2 password KEK + optional biometric Keystore key; `vault.json`), `LocalFileCipher` (CTEF chunked AES-GCM), `PortableCipher` (CTEB password envelope for backups/Drive), `BiometricKeyWrapper`/`BiometricUnlock`, `AppLockController` + `LockTiming`. Pure JVM except the Keystore/BiometricPrompt wrappers; never depends on data/database | done |
| `core:notifications` | Channels (trains/flights/reminders), builders, deep links, POST_NOTIFICATIONS permission gate | done |
| `core:google` | Google linking (Credential Manager), Calendar sync (dedicated "Clear Travel" calendar), Drive uploads/backups + restore ladder, sync workers, ADR-037 scheduled automatic backup (`ScheduledBackupWorker`/`ScheduledBackupScheduler`, local always + Drive when enabled) | done (needs-user-setup: `GOOGLE_WEB_CLIENT_ID`, see `docs/google-setup.md`) |
| `core:scrape` | Rule-driven WebView scraper engine (DOM storage on, `dismissSelectors`, ready-signal timeout → raw-page fallback); per-site JSON rule files in assets + HTML fixtures (ADR-003) | done |
| `core:ocr` | PDF→bitmap→preprocess→ML Kit text recognition + BCBP barcode decode; pure extraction functions + OCR-text fixtures | done |
| `core:testing` | `MainDispatcherRule`, generic `inMemoryDatabase<T>()`, `Fixtures` builders — exposed as MAIN source | done |
| `feature:trains` | Train tickets CRUD, redesigned cards (ADR-020), detail sheet, PNR refresh (foreground WebView, user-solved captcha; injected `RuleRegistry`), SMS/OCR/PNR-link prefill + de-duplication (ADR-024), hands-free route fetch (ixigo primary / erail fallback) + offline route page (ADR-019), seat map from `train_coaches` + seat-layout data files (ADR-022), ticket share image + PNR link, archive | done (on-device validated, `docs/validation-report.md`) |
| `feature:flights` | Flight CRUD (date is picker-only), boarding-pass + booking-confirmation import (ADR-017), de-duplication (ADR-025), status check WebView: airline rule → Google flight-status card fallback (`GoogleFlightsExtractor`, ADR-026) → web search, failure banner/retry + outcome line, check-in windows data, card quick actions (refresh / window-gated web check-in / share image + add-data link, ADR-039), WorkManager polling (EntryPoint workers, ADR-013) + notifications | done (on-device validated; Air India + Google rules verified live) |
| `feature:itinerary` | Trips tab: trips, day-grouped items (auto-sort by time + drag override, ADR-029), timeline + Google Maps view (markers only), location picker with Nominatim place search + platform-Geocoder fallback (ADR-035), Google Maps link intake (ADR-029 D), commute legs linked to journeys incl. add-from-form via `JourneyAddRequestBus` (ADR-028), share trip as self-contained link (ADR-039) | done (map needs-user-setup: `MAPS_API_KEY` + Play-services device) |
| `feature:checklist` | Checklist tab: per-trip checklists, preset templates + manager, share checklist as self-contained link + `ChecklistLanding` hook (ADR-039) | done |
| `feature:documents` | Documents tab: travel documents (passport, visa, …) as encrypted local files with typed labels + expiry, add via system picker, bottom-docked live search (ADR-033), shared viewer, edit/delete (ADR-027; local-only, Drive sync is a follow-up) | done (device-validated: `docs/validation-report.md`, ADR-030/033/034 runs) |
| `feature:menu` | Menu tab: Settings (theme, Security: change password / biometric unlock / lock timing, presets, Google account), Backup/Restore (automatic-backup schedule Off/Daily/Weekly/Monthly, local + Drive, source-password prompt for foreign backups), About | done |
| `feature:applock` | ADR-031 gate composed around the whole shell: blocking first-run password setup (autofill `NewPassword`, strength hint, data-loss warning, fingerprint toggle), unlock (password autofill or BiometricPrompt), "Securing your data" storage preparation, process-lifecycle re-lock; ADR-032 first-run wizard steps 2–4 in `onboarding/` (Google link or offline → restore from file / Drive or start fresh → backup password) gated on `SettingsRepository.onboardingPending` | done (device-validated on the emulator: fresh setup + plaintext→SQLCipher migration + wizard incl. foreign-password restore) |

**Ownership boundaries:** feature modules depend ONLY on `core:*`, NEVER on each other;
`core:designsystem` reads document bytes only through its `DocumentFileReader` seam
(`LocalDocumentFileReader`, installed by the app shell with the decrypting reader);
cross-feature interaction goes through `core:data` contracts (e.g. the ADR-028
`JourneyAddRequestBus` + `ItineraryRepository.observeItemsLinkedToJourney`) and the
app shell's landing hooks (`JourneysDeepLink`, `TripsLanding`, `ChecklistLanding`). The app module is the
only composition point (Journeys tab, cross-tab coordination). `core:designsystem` never depends on
data/database/features. UI never touches Room DAOs or WebView engines directly.

## Build / test / lint commands

```bash
./gradlew ktlintCheck                # style (ktlint_official; run ktlintFormat to fix)
./gradlew lintDebug                  # Android lint — HardcodedText/SetTextI18n are ERRORS
./gradlew testDebugUnitTest          # unit tests (Robolectric DAO tests included)
./gradlew assembleDebug              # app/build/outputs/apk/debug/ClearTravel-debug.apk

# the full local quality gate (same as CI) — ALWAYS redirect to build.log (git-ignored):
./gradlew ktlintCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest > build.log 2>&1
tail -n 30 build.log                 # inspect with tail/grep; output is huge
./gradlew connectedDebugAndroidTest  # e2e suite on a running emulator (see anti-stall rules)
# macOS has no `timeout`: wrap long emulator steps yourself (e.g. `perl -e 'alarm 600; exec @ARGV' -- cmd`)
```

Run ktlint + unit tests before EVERY commit. No milestone is done until its tests
exist and the full gate passes.

## Hard rules

1. **No hardcoded user-visible strings** — not in Kotlin, not in Compose, not in
   notifications. Every string goes in a `res/values/strings.xml` (module-local for
   feature/core modules, `app` for shell strings), prefixed with the module namespace
   (`trains_`, `flights_`, `itinerary_`, `checklist_`, `documents_`, `menu_`). Lint `HardcodedText`/
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
7. **Encryption at rest is not optional** (ADR-031): every user file is written
   through `LocalFileCipher` (never a raw `outputStream()` under `filesDir`), Room
   only ever opens through the vault-keyed factory, anything leaving the device
   (backups, Drive uploads) goes through `PortableCipher`, and nothing touches Room
   before the `AppLockGate` opens (workers check `KeyVault.isUnlocked` and no-op with
   the "unlock to sync" notification). Never persist the DEK, a sub-key or the
   password; never add a recovery/reset path — data loss on a forgotten password is
   the documented design. New user-file directories must be added to `AppFileLayout`
   so the one-time migration and the backup engine see them.
   First run is the ADR-032 wizard (password → Google → restore-or-fresh → backup
   password) gated on `SettingsRepository.onboardingPending`; anything that must not
   overlay it (the notification permission request, startup housekeeping) runs inside
   the gate's content / `onUnlocked`, never at the top of the activity tree.
8. **Git**: NEVER push, never add a remote, never force-push. Conventional Commits,
   imperative mood, subject ≤50 chars (`feat(trains): add ticket form`). Commit after
   every completed task. Never commit generated `build/` output.
9. Every icon-only control uses `ExplainableIcon` (long-press explanation); empty
   screens use `EmptyState`; errors/confirmations use snackbars, not toasts; FAB
   (`ClearTravelFab`) is the primary add action per tab; detail views are bottom
   sheets; body text ≥16sp.
   Shared UI (any composable/helper two features need — filter rows, date picker,
   date formats, dialogs) lives in `core:designsystem`, never as private per-feature
   copies (ADR-036); picker-backed read-only fields observe their own
   `InteractionSource`, never a transparent overlay.
   **Dialogs (ADR-041):** any dialog with a text field passes
   `properties = InputDialogProperties` (no dismiss on an outside tap — a gesture-nav
   edge swipe lands as an outside touch first; back and the explicit Cancel still
   close it). Pure confirmations, choice lists and pickers keep the default
   tap-outside dismissal.
   **Keyboard (ADR-041):** IME insets are applied ONCE, in the app shell
   (`ClearTravelApp` NavHost `imePadding()` after `consumeWindowInsets`), plus
   `LockScaffold` and the externally hosted forms in `MainActivity`. Never add
   `imePadding()`/`WindowInsets.ime` inside a tab screen — it is already consumed;
   scrollable forms bring the focused field into view on their own.

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
- **Security seams in tests**: `KeyVault` is `DefaultKeyVault(InMemoryKeyFileStore(),
  iterations = 1_000)` (cheap KDF; strength is not under test), `LocalFileCipher(key =
  { vault.fileKey() })`, `BiometricKeyWrapper` faked with a plain AES key. DAO tests
  keep the plain in-memory Room factory — SQLCipher has no host natives. The e2e
  suite's `TestSecurityModule` is pre-unlocked; flip `freshInstall` per class for
  first-run flows.
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
