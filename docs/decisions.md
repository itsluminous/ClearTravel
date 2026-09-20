# Architecture Decision Records

Short, numbered entries for every contract change, schema change, or non-obvious design
choice — additive-only, newest at the bottom. An agent or human joining later must be
able to reconstruct the reasoning from this file alone.

---

## ADR-001: Module layout (incl. core:scrape and core:ocr as dedicated modules)

**What.** Multi-module Gradle layout: `app` + `core:designsystem`, `core:model`,
`core:database`, `core:data`, `core:notifications`, `core:google`, `core:scrape`,
`core:ocr`, `core:testing` + `feature:trains`, `feature:flights`, `feature:itinerary`,
`feature:checklist`, `feature:menu`. All 15 modules are declared in
`settings.gradle.kts` from milestone 1 so later parallel agents never edit that file.
Feature modules depend only on `core:*`, never on each other; the app module is the
only composition point (e.g. the Journeys tab composes `TrainsContent` and
`FlightsContent` side by side under a segmented control).

**Why the extra `core:scrape` / `core:ocr` modules** (not in the Samaroh reference):
both are *shared engines* used by two features. The rule-driven WebView scraper serves
trains (PNR enquiry) AND flights (13 airline status pages); the OCR/barcode pipeline
serves train-ticket import AND boarding-pass import. Putting either engine inside a
feature module would force a feature→feature dependency (forbidden); putting them in
`core:data` would bloat the repository layer with WebView/ML Kit machinery. Dedicated
modules keep the engines independently testable (fixture-driven parameterized tests)
and independently ownable by parallel agents.

**Why `core:model` is an android-library, not pure kotlin-jvm.** The quality gate and
CI run `testDebugUnitTest`, which only exists for Android modules — a kotlin-jvm
module's `test` task would silently fall outside the gate. The module still contains
zero Android framework code (plain Kotlin + kotlinx-serialization only), keeping it
unit-test friendly, and consistency of build configuration across all modules
outweighed the marginally faster pure-JVM compile.

## ADR-002: UUID id + `updated_at` + tombstone on EVERY entity

**What.** Every persisted entity implements `core:model`'s `SyncableEntity`: a
client-generated UUID string `id` (via `EntityIds.newId()`), an `updated_at` Instant
bumped on every write, and a nullable `deleted_at` tombstone (soft delete only — rows
are never hard-deleted while a backup could still reference them).

**Why.** These three fields are REQUIRED by the backup merge semantics (spec feature
6): import is a MERGE, never a wipe — same-id rows resolve by last-write-wins on
`updated_at`, rows only in the backup are inserted, deletions are honored via
tombstones, and repeated imports stay idempotent because UUIDs never collide with
auto-increment ids. They also keep a future sync layer possible without schema
breakage. DAOs/repositories must never expose hard deletes; Room queries filter
`deleted_at IS NULL` by default.

## ADR-003: Behavior-as-data — scrape rules, presets, check-in windows as versioned data files

**What.** Behavior that changes independently of code ships as versioned data files,
not Kotlin: per-site scrape rule JSON files (`assets/scrape-rules/`, one file per
site: url template, prefill/submit selectors, ready signal, extract map — schema stub
in `core:scrape`), airline check-in-window override tables, checklist preset
templates, and OCR field-extraction patterns. Each file carries its own `version`
field and ships with its own recorded fixture + expected-output test; a parameterized
test runs every rule file against its fixture, so adding a file without a fixture
fails CI.

**Why.** Scrapers break when sites change and airlines change their pages often —
fixing a parser must mean editing ONE small data file, never engine code. The generic
engines (`RuleDrivenScraper`, preset instantiator, extraction runner) are written and
unit-tested once; per-site/per-preset behavior stays reviewable, diff-able data. This
also keeps behavior changes safe for parallel agents: adding an airline touches one
new file plus one fixture, with no merge conflicts in shared code.

## ADR-008: Scrape engine — JS-dump + pure jsoup extraction, events Flow, CI fixture enforcement

**What.** `core:scrape` implements the ADR-003 rule engine in three strictly separated
layers: (1) `RuleDrivenScrapeSession` — a WebView-agnostic "brain" per scrape attempt
that expands the rule's URL/prefill/submit/readySignal into JavaScript snippets and
emits a `Flow<ScrapeEvent>` (`PageReady`, `NeedsUserAction`, `Extracted`,
`ParseFailed(rawHtml)`); (2) `ScrapeWebViewController` — a deliberately thin host that
wires a CALLER-owned WebView to the session (load → inject prefill via
`evaluateJavascript` → optional auto-click submit → poll the ready signal → dump
`document.documentElement.outerHTML`), carrying zero parsing logic; (3)
`RuleExtractor.extract(rule, html)` — a PURE Kotlin function (jsoup on the dumped
HTML) returning `ExtractionResult.Success(ScrapedData)` or
`Failure(reason, rawHtml)`, never throwing on unexpected markup. Rules with
`submitSelector: null` (captcha pages, e.g. indianrail-pnr) emit `NeedsUserAction`
instead of auto-submitting. `RuleRegistry` loads all JSON files from
`assets/scrape-rules/` behind a `RuleSource` interface (asset-backed at runtime,
filesystem-backed in tests) and selects flight rules by IATA prefix
(`6E-2345` → the rule declaring `"6E"` in `iataCodes`); unknown airlines return null
so features fall back to a web-search URL. jsoup (pinned in the version catalog) is
the single new dependency.

**Why.** The WebView half is inherently untestable on the JVM, so ALL intelligence
lives in the pure extraction path — the exact code fixture tests exercise; the host
stays dumb enough that instrumented coverage later is a formality. Events-as-Flow
keeps feature modules reactive and the WebView lifecycle in the caller's composable
(engine written once, no per-feature engine code). Fixture enforcement is structural:
a parameterized test enumerates EVERY rule file in assets and fails for any rule
without `src/test/resources/fixtures/<ruleId>/{page.html,expected.json}` — adding an
airline without a recorded fixture cannot pass CI (ADR-003), and the enforcement
mechanism itself is self-tested. Parse failures carry the raw HTML because the spec's
fallback is showing the user the raw page, never a crash or a blocked UI.

## ADR-009: OCR pipeline design — staged classes, pure extractors, per-field confidence, fixture discipline

**What.** `core:ocr` splits the on-device import pipeline into small single-purpose
stages: `PdfPageRasterizer` (platform `PdfRenderer` → Bitmap, white background, fixed
target width), `BitmapPreprocessor` (grayscale + linear contrast stretch via
`ColorMatrix` only — **no OpenCV dependency; deskew deliberately skipped** since
tickets/passes are axis-aligned scans in practice and grayscale+contrast is the main
accuracy win), `OcrTextRecognizer` / `BcbpBarcodeDecoder` (thin suspend wrappers over
ML Kit text recognition and barcode scanning restricted to PDF417/Aztec/QR; both
degrade to empty output on failure, never throw). Everything that interprets text is
**pure Kotlin, plain-JUnit testable**: `BcbpParser` (IATA Resolution 792 type-M
mandatory 60-char block, multi-leg tolerant, Julian date resolved to the year nearest
"today"), `IrctcTicketExtractor`, `IrctcSmsParser` and `BoardingPassTextExtractor`.
The single Android-facing entry point is `OcrPrefillService`
(`prefillTrainTicket(Uri)`, `prefillTrainTicketFromText(String)`,
`prefillBoardingPass(Uri)` — barcode first, OCR-text heuristics as fallback); Hilt
provides the ML Kit clients (`di/OcrModule`).

**Confidence model.** Every field is an `ExtractedField(value, confidence)` with a
fixed ladder: BCBP barcode content and label-anchored text matches ("PNR:", "Date of
Journey:") are HIGH; strong unlabeled structural matches (a `NDLS-BCT` station pair, a
bare `AI 0865` flight token) are MEDIUM; weak heuristics (a bare 6-char alphanumeric
as PNR) are LOW; absent is NONE with a null value. Extractors **always succeed
structurally** — garbage input returns the typed EMPTY result (blank form + file
attached), never an exception — so feature UIs need no error paths, only the review
form.

**Fixture discipline (extends ADR-003).** Recorded OCR-text fixtures live in
`core/ocr/src/test/resources/fixtures/` as `<name>.txt` + `<name>.expected.json`
pairs; parameterized tests run every fixture through its extractor and compare the
full serialized result (extraction models are `@Serializable` precisely to enable
this). The set ships with two IRCTC ERS layouts, two boarding-pass layouts, one IRCTC
SMS, and one garbage fixture asserting the EMPTY fallback — adding an extraction
behavior without a fixture is a review-blocking omission, same as scrape rules.

## ADR-004: Entity catalog and relations (schema v1)

**What.** Room schema v1 (`ClearTravelDatabase`, exported to `core/database/schemas/`,
committed) with 11 tables. Domain models live in `core:model` (pure Kotlin,
`SyncableEntity` implementations); Room `*Entity` mirrors + trivial mappers live in
`core:database`; repositories in `core:data` expose ONLY `core:model` types — feature
modules never see Room classes. Relations are by UUID reference (no Room foreign
keys — soft deletes make FK cascades wrong; repositories own delete cascades):

- `trips` ←(trip_id)— `itinerary_items`. An itinerary item is one row for both
  variants: `type` PLACE (lat/lng, category, planned time, note, link) or COMMUTE
  (mode, from/to names, optional `linked_journey_id` + `linked_journey_type`
  pointing at a train ticket or flight journey).
- `checklists` (nullable `trip_id` — standalone checklists allowed) ←— `checklist_items`.
- `checklist_presets` ←— `checklist_preset_items` (templates; see ADR-006).
- `train_tickets` ←(ticket_id)— `train_passengers` and `train_route_stops`.
- `flight_journeys` (self-contained; status/gate/belt columns are the merge target
  of provider results, ADR-005).
- `attachments` with a polymorphic owner (`owner_type` TRAIN|FLIGHT|ITINERARY +
  `owner_id`), `drive_file_id` null until uploaded (drives the Drive upload queue).
- Google Calendar sync uses a nullable `google_event_id` column directly on
  `itinerary_items`, `train_tickets`, and `flight_journeys` (simplest; no join table).

Conventions: snake_case columns; enums stored as stable `storageValue` strings (never
`name()`); `Instant` as epoch millis; `LocalDate` as ISO strings; unknown stored enum
values parse to a safe fallback (`UNKNOWN`/`OTHER`) so old app versions never crash on
newer data. Every table carries ADR-002's `id`/`updated_at`/`deleted_at`; all DAO read
queries filter `deleted_at IS NULL`; soft-delete queries set `deleted_at` AND
`updated_at` in one statement. Repository delete cascades: trip → itinerary items +
trip-scoped checklists (+ their items); train ticket → passengers + route stops +
TRAIN attachments; flight → FLIGHT attachments; checklist → items; preset → items.

**Why.** One schema freeze for every feature agent to build on. Model/entity
separation keeps the module graph honest (`core:data` api-exposes `core:model` only)
and keeps `core:model` framework-free.

## ADR-005: Provider result contracts (TrainStatusResult / FlightStatusResult)

**What.** `core:data` owns the provider contracts; implementations land elsewhere
(`core:scrape`, feature modules) and are Hilt-bound:

- `TrainStatusProvider.fetchPnrStatus(pnr): Result<TrainStatusResult>` —
  `TrainStatusResult(pnr, passengers: List<TrainPassengerStatus>, chartPrepared?,
  trainNumber, trainName, fetchedAt)`; `TrainPassengerStatus(currentStatus,
  bookingStatus, coach, seatBerth)`.
- `FlightStatusProvider.fetchFlightStatus(airlineIata, flightNumber, date):
  Result<FlightStatusResult>` — `FlightStatusResult(status, schedDep/schedArr,
  estDep/estArr, depTerminal/depGate, arrTerminal/arrGate, baggageBelt,
  aircraftType, fetchedAt)`.

Conventions: `suspend` even for the interactive WebView provider (it suspends while
the user completes the page); errors are `Result.failure` — callers keep showing the
last stored data. Empty string / null in a result means "source didn't report it".
Persistence goes through `TrainRepository.applyStatusResult` (per-passenger
`currentStatus` matched BY POSITION, coach/seat merged only when reported, ticket
`lastFetchedAt` set) and `FlightRepository.applyStatusResult` (status always; times/
gates/terminals/belt/aircraft merged only when reported; `lastFetchedAt` set) — so a
partial scrape never wipes known data.

**Why.** Freezes the seam between the domain layer and the scrape/API engines so both
sides can be built in parallel; merge-only-known-fields keeps flaky scrapes safe.

## ADR-006: Checklist preset append semantics (multi-append, dedupe-by-text, isolation)

**What.** `ChecklistRepository.appendPreset(checklistId, presetId)`:

- **Cumulative multi-append**: a checklist can absorb any number of presets over
  time (canonical example: append "International travel", then "Medicines", onto the
  same checklist). Each call appends after the current max `sort_order`.
- **Dedupe by exact text**: preset items whose exact text already exists live in the
  checklist are skipped (so overlapping presets — e.g. both containing "Power bank" —
  don't duplicate). Re-appending the same preset is a no-op.
- **Copy, never link**: appended items are NEW `checklist_items` rows with fresh
  UUIDs. The preset is never mutated by an append, and editing/deleting a preset
  later never mutates checklists built from it (spec §4).

Built-in presets are behavior-as-data (ADR-003): a versioned JSON asset
(`core/data/src/main/assets/presets/builtin-presets.json`) with FOUR presets —
Domestic trip, International travel, Trek, Medicines — each with a FIXED UUID.
Seeding runs from the Room `onCreate` callback and is idempotent by id, checking
existence INCLUDING tombstones: a user-deleted built-in stays deleted and a
user-edited built-in is never overwritten; fixed ids also keep backup merges
duplicate-free. The seeding test doubles as the asset's fixture test.

**Why.** The multi-append + dedupe behavior is an explicit user requirement; copy
semantics are the only ones compatible with "editing a preset never mutates existing
checklists"; tombstone-aware seeding is what makes "built-in" and "user-deletable"
coexist.

## ADR-007: User-entered API keys in EncryptedSharedPreferences

**What.** `SettingsRepository` splits storage: theme mode and provider selection live
in a plain Preferences DataStore; user-entered status API keys (train/flight) live in
`EncryptedSharedPreferences` (androidx-security-crypto, AES256-GCM values + AES256-SIV
keys under an Android Keystore master key). The encrypted store is injected as a
`SharedPreferences` behind the `@SecurePreferences` qualifier, so unit tests
substitute a plain instance (Robolectric has no Keystore). API keys are exposed as
suspend accessors only — never Flows — to keep them out of observable state.

**Why (vs "encrypted DataStore").** The version catalog already ships
androidx-security-crypto and there is no first-party encrypted DataStore — wiring
Tink into DataStore by hand is more code and more crypto surface for zero benefit at
this data size. Keys are read rarely (only when an API provider fires), so
SharedPreferences' synchronous model is fine behind `Dispatchers.IO`.

## ADR-010: Per-tab nested NavHost; built-in presets read-only (duplicate-to-customize)

**What.** (1) Feature tabs own their sub-navigation: `checklistGraph()`/`menuGraph()`
register ONE destination on the app NavHost, and that destination hosts a nested
`NavHost` (`rememberNavController` inside the tab) for its subscreens (checklist
list → full-screen detail; menu root → Settings / Manage presets / preset editor /
About). Checklist detail is a full screen, not a bottom sheet — packing lists are
long and need the add-item field + reorder controls anchored. Reordering uses
up/down buttons (swap `sort_order` with the neighbour), not drag handles. (2) In
Manage presets, built-in presets are READ-ONLY: no edit/delete (guarded in both the
UI and the ViewModels); the sanctioned customization path is duplicate-then-edit
(`duplicatePreset` yields a user copy, `builtIn = false`). User presets support
rename, add/remove/reorder, duplicate and delete.

**Why.** (1) The app module passes no NavController into feature graphs, and feature
modules must not depend on each other — a nested NavHost keeps ALL subscreen wiring
inside the owning module (app/ is never touched when a feature adds a screen) and
keeps the bottom bar highlighted on the owning tab, matching the feature READMEs.
Up/down buttons are deterministic and trivially unit-testable where drag-reorder in
Lazy lists is gesture-fragile. (2) Tombstone-aware seeding (ADR-006) means an edited
built-in would never be re-seeded — a user who breaks a built-in template could never
recover it; read-only built-ins + duplicate-to-customize preserves the templates
while allowing full customization, and copy semantics already guarantee editing any
preset never mutates existing checklists.

## ADR-011: Train PNR refresh bypasses TrainStatusProvider; manual stub keeps the seam alive

**What.** The trains feature's DEFAULT PNR refresh is an interactive, full-screen,
user-visible WebView flow (`feature:trains` `pnr` package): it builds a
`RuleDrivenScrapeSession` from `RuleRegistry.ruleById("indianrail-pnr")` +
`ScrapeParams(pnr)`, hosts a caller-owned WebView via `ScrapeWebViewController`, lets
the USER tap submit and solve the captcha (CONFIRMED live on indianrail at recon time,
`docs/recon/NOTES.md`), then maps `ScrapeEvent.Extracted`'s `ScrapedData` through the
pure `PnrStatusMapper` and persists via `TrainRepository.applyStatusResult` — WITHOUT
going through `TrainStatusProvider.fetchPnrStatus`. The seam stays alive anyway:
`feature:trains` Hilt-binds `ManualTrainStatusProvider` (providerId `"manual"`), an
always-available stub whose `fetchPnrStatus` fails with
`InteractiveCheckRequiredException`, signalling callers to route the user to the
interactive check. `PnrStatusMapper.map(pnr, data, fetchedAt)` is a pure function
pinned by unit tests to the exact field/row names the `indianrail-pnr` rule emits
(`trainNumber`/`trainName`/`chartingStatus` fields; `bookingStatus`/`currentStatus`
rows); it keeps the raw page status text and additionally extracts coach/seat from
`STATUS/COACH/SEAT` shapes so `applyStatusResult`'s merge-only-known-fields semantics
apply. A mapper null (rows present but no usable status text) is treated exactly like
`ParseFailed`: raw page stays visible, stored data unchanged.

**Why.** ADR-005 deliberately made `fetchPnrStatus` a suspend call, but a
captcha-gated flow has no sane suspend shape: the "fetch" spans user interaction in a
composable-owned WebView whose lifecycle belongs to the UI (ADR-008), and wrapping
that in a suspending provider would force the provider to own UI state. Writing
through `applyStatusResult` keeps the persistence contract identical for every future
provider. **How an API provider slots in later:** implement `TrainStatusProvider`
with a real `fetchPnrStatus` (user-supplied key, ADR-007 storage), bind it behind the
Settings provider selection, and have the detail sheet's "Check PNR status" action try
the active provider first — on success call `applyStatusResult` with its result, on
`InteractiveCheckRequiredException`/failure fall back to launching the interactive
WebView screen. No UI or repository changes are needed; only the action's dispatch
logic grows one branch.

## ADR-012: Itinerary feature — nested NavHost, pure map/day logic, map degradation, maps-compose version pin

**What.** `feature:itinerary` (milestone 3) is structured around four decisions:

1. **Nested NavHost inside the Trips tab.** The app shell calls `tripsGraph()` with no
   NavController, so in-feature navigation (trip list → trip detail → item form) runs
   on a nested `NavHost` owned by the `TRIPS_ROUTE` composable. `TRIPS_ROUTE` and the
   `tripsGraph()` signature are unchanged from the skeleton — the app module needs no
   edits, and system back works because the nested NavController registers with the
   back dispatcher.
2. **Pure logic extracted from UI** (`logic/` package, plain Kotlin, JVM-testable):
   day grouping (`groupItemsByDay`, `nextOrderInDay`, `moveWithinDay`, `dayCount`,
   `dateForDay`), map content building (`buildTripMapContent` — numbered per-day
   markers for located PLACEs, per-day ordered polylines, null-coordinate items
   skipped), the stable day palette (`dayColorArgb`), manual "lat, lng" parsing
   (`parseLatLng`) and the map readiness guard (`mapUnavailableReason`). ViewModels
   and composables only orchestrate these functions.
3. **Offline-first map degradation.** The GoogleMap composable renders only when the
   pure guard `mapUnavailableReason(hasPlayServices, hasApiKey)` passes; otherwise
   the map view (and the tap-picker) degrade to an inline notice while the timeline —
   which reads Room only — keeps working. The API key presence is read from the
   merged manifest meta-data, so the empty-key default never crashes.
4. **maps-compose transitive pin exclusion.** maps-compose 6.12.2 declares
   `androidx.core:core(-ktx):1.17.0` (requires AGP ≥ 8.9.1; project is on 8.7.3) and
   its own newer Compose BOM. Both are excluded on the dependency edge in
   `feature/itinerary/build.gradle.kts` so the version-catalog pins keep winning
   across every consumer, without touching the frozen catalog. Revisit when AGP is
   upgraded.

Commute legs store only `linkedJourneyId`/`linkedJourneyType` (ADR-004); the journey
picker lists candidates by injecting `TrainRepository`/`FlightRepository` read-only —
feature modules may inject any `core:data` interface, keeping the no feature→feature
dependency rule intact.

**Why.** Keeps milestone 3 fully inside `feature:itinerary` (parallel-agent safe),
makes the grouping/marker/reorder behavior unit-testable without Robolectric or Play
services, and honors the spec requirement that everything except live map tiles works
offline.

## ADR-013: Flights milestone — interactive-only scrape, data-driven check-in windows, EntryPoint worker

**What.** `feature:flights` + `core:notifications` land with these choices:

- **Status refresh is interactive-only; background polling does NOT scrape.** The
  "Check status" flow hosts a VISIBLE WebView driven by the ADR-008 engine
  (`RuleRegistry.flightRuleFor` → `RuleDrivenScrapeSession` →
  `AirlineStatusMapper` → `FlightRepository.applyStatusResult`). A headless
  WorkManager-hosted WebView variant was considered and rejected for this wave:
  WebViews demand main-thread lifecycles inside a worker, the recon showed
  consent-overlay/anti-bot postures that want a human present, and the spec
  explicitly sanctions the fallback of notifying "status may have changed — tap to
  check". `FlightStatusWorker` therefore computes only what is knowable offline:
  check-in-window crossings (from the data file below) and proximity nudges
  (dedupe-keyed per 12h/3h bucket). `FlightChangeDetector` (pure old-vs-new diff →
  gate assigned/changed, delay, cancellation, belt) runs on the interactive refresh
  path and feeds the same `FlightNotifier` calls, so notification behavior is
  identical whenever a headless fetch path appears later.
- **Escalating cadence via a self-chaining unique OneTimeWork** (`flights-status-poll`,
  REPLACE) instead of PeriodicWork: WorkManager cannot vary a periodic interval, and
  the spec requires >48h→6h, 48–12h→3h, 12–3h→30min, <3h→15min (pure `NextPollDelay`,
  floor = WorkManager's 15-min minimum; polling stops 6h after departure).
- **Worker dependencies via a Hilt `@EntryPoint`, not `@HiltWorker`**: `@HiltWorker`
  requires the app module to install `Configuration.Provider`/`HiltWorkerFactory`;
  the EntryPoint keeps the milestone app-module-free (parallel-agent boundary). The
  app shell owes no wiring; `FlightPollScheduler.ensureScheduled` runs from the
  flights UI.
- **Notification dedupe state lives in feature-local SharedPreferences**
  (`flights_poll_state`), NOT a Room column: it is device-local bookkeeping that must
  never enter the backup/merge surface (ADR-002 covers synced entities only).
- **Deep-link intent contract** (`core:notifications` `DeepLinkContract`): every
  notification's content intent is the package LAUNCH intent + extras
  `com.itsluminous.cleartravel.deeplink.TARGET` (`flight`/`train`) and
  `...deeplink.ENTITY_ID` (entity UUID). Resolving the extras into navigation inside
  `MainActivity` is deferred to integration; `core:notifications` cannot reference the
  activity class across module boundaries. POST_NOTIFICATIONS is declared in the
  `core:notifications` manifest; every post is gated on `NotificationPermissions.canPost`
  (silent no-op without permission).
- **Check-in windows are behavior-as-data** (ADR-003):
  `feature/flights/src/main/assets/checkin-windows.json` carries the default 48h→1h
  window, per-airline overrides, the airline display name, AND the airline's web
  check-in URL (the detail sheet's "Open web check-in" deep link; web-search
  fallback when absent). Pure `computeCheckInWindow`; `CheckInWindowsAssetTest` is
  the file's fixture test (all 13 spec airlines present, sane hours, https URLs).
- **Canonical scrape field vocabulary for flight rules** (consumed by the pure
  `AirlineStatusMapper`): `status`, `aircraftType`, `dep/arrAirport` (IATA in
  parentheses), `dep/arrDate`, `dep/arrTimeSched|Est`, `dep/arrTerminalGate`
  (pipe-separated), `baggageBelt` — emitted as `rows` (one map per result card;
  Air India returns MULTIPLE cards per query, disambiguated against the saved
  journey by departure airport, then date). Scraped times are airport-local with no
  timezone info; the mapper interprets them in the device zone as a documented
  best-effort. The frozen `ScrapeRule` schema has no `{date}`-format field, so the
  per-rule URL date format lives in feature code (`FlightStatusFallbacks.formatDateForRule`,
  default ISO; `airindia` → `yyyyMMdd`).
- **Airline rule inventory (quality over quantity).** Only `airindia` ships (v1,
  verified=true — selectors from the real captured result DOM in
  `docs/recon/airindia.html`, multi-card fixture). IndiGo (PNR-only search, result
  DOM never observed, submit-enable condition unresolved), Akasa (form recon'd but
  result DOM never observed) and all probed international airlines (JS shells over
  curl — no server-rendered result markup) have NO credible extraction basis, and
  SpiceJet is structurally unscrapeable (React-Native-Web atomic CSS) — all fall
  back to the registry-returns-null web-search path, which is unit-tested.
  `RuleRegistry` is currently Hilt-provided from `feature:flights`
  (`FlightsProvidersModule`); if `feature:trains` needs it too, integration should
  hoist that single `@Provides` into a shared module.

**Why.** Keeps the scope honest (interactive scrape is the only recon-validated
path), keeps every "brain" pure and unit-tested (mapper, window, cadence, diff,
evaluator — 70 tests in `feature:flights` alone), and keeps all cross-module seams
(worker wiring, deep links, rule provisioning) additive for the integration wave.

## ADR-014: App-shell integration — theme, deep links, share sheet, RuleRegistry hoist, startup housekeeping, hermetic e2e

**What.** The integration wave wires everything the feature milestones deferred to
app/ ownership:

- **Theme.** `ThemeViewModel` (app module) exposes `SettingsRepository.themeMode` as
  a `StateFlow` with initial `ThemeMode.SYSTEM`; `MainActivity` collects it via
  `collectAsStateWithLifecycle` and resolves it to `ClearTravelTheme(darkTheme=…)`
  (`SYSTEM → isSystemInDarkTheme()`). The SYSTEM initial value matches the splash
  theme, so the splash→content handoff never flashes the wrong theme while DataStore
  loads.
- **Notifications.** `NotificationChannelRegistrar.registerAll()` runs in
  `MainActivity.onCreate` (idempotent). POST_NOTIFICATIONS is requested exactly ONCE
  per install (`app_shell_state` SharedPreferences flag) via the activity-result
  API; a denial is never re-prompted — posting stays a silent no-op through
  `NotificationPermissions.canPost`, and the user can grant later from Settings.
  Device-local flag deliberately outside Room/backup (same reasoning as ADR-013's
  poll-state store).
- **Deep links.** `MainActivity` (already `singleTask`) parses the ADR-013
  `DeepLinkContract` extras in `onCreate` + `onNewIntent` into a `JourneysDeepLink`
  Compose state (a `nonce` field makes repeat links to the same entity distinct).
  `ClearTravelApp` navigates to the Journeys tab; `journeysGraph(deepLink,
  onDeepLinkConsumed)` selects the Trains/Flights segment and forwards the entity id
  into the features through NEW additive hooks: `TrainsContent(initialTicketId=…)`
  and `FlightsContent(initialFlightId=…)` → `FlightListScreen(initialDetailFlightId=…)`
  — all defaulted, so existing call sites are untouched.
- **Share sheet.** The app manifest adds the deferred `ACTION_SEND` `text/plain`
  intent filter; `MainActivity` routes `EXTRA_TEXT` to `feature:trains`'
  `TrainsSharedTextEntry` rendered over the shell; `onDone` (save or cancel) returns
  to the normal UI.
- **RuleRegistry hoist.** The single `RuleRegistry` `@Provides` moved from
  `feature:flights` (`FlightsProvidersModule`, deleted; file renamed to
  `FlightsBindingsModule.kt`) into `core:scrape`'s new `di/ScrapeModule` — the
  registry serves both trains and flights (ADR-013 note). This required adding the
  ksp+hilt plugin pair and `hilt-android` to `core:scrape` (a `@InstallIn` module is
  only aggregated when its defining module runs the Hilt compiler) — the only
  build-file change of the wave; no version-catalog changes. `feature:trains`'
  `PnrCheckViewModel` still constructs its registry directly (unchanged, works);
  migrating it to injection is optional follow-up.
- **Startup housekeeping** (`app` `startup/AppStartupTasks`, launched from
  `MainActivity.onCreate` on `Dispatchers.IO`): (1) auto-archive — train tickets
  past `feature:trains`' `isPastJourney` and flights past the app-level
  `isPastFlight` (journey day strictly before today; falls back to `schedDep`'s
  local date when `date` is null; lives in app because it composes both feature
  aggregates) are `setArchived(true)`; idempotent since archived rows leave
  `observeActive`. (2) flight-poll kick — computes the soonest `schedDep` across
  active flights and calls `FlightPollScheduler.ensureScheduled` (KEEP policy).
  This kick is what actually STARTS the ADR-013 self-chaining WorkManager chain:
  the in-feature `ensureScheduled(context, null)` call is a documented no-op
  (`NextPollDelay.compute(null) == null`), and nothing scheduled on save — without
  the app-open kick, polling stayed dormant after reboot/force-stop or when a
  flight was saved without revisiting the tab.
- **Hermetic e2e suite** (`app/src/androidTest`): `HiltTestRunner` swaps in
  `HiltTestApplication`; `TestDatabaseModule` (`@TestInstallIn`, replaces
  `DatabaseModule`) provides an in-memory Room with the same async preset-seeding
  callback. One happy path per feature — checklist-from-preset + second-preset
  append snackbar, trip creation, manual train ticket → PNR in detail sheet, manual
  flight → route in detail sheet. No network, providers, scraping or OCR paths are
  reachable from these flows; POST_NOTIFICATIONS is pre-granted via UiAutomation so
  the one-time permission dialog never overlays the UI under test. The suite is
  compiled by the quality gate (`assembleDebugAndroidTest`) and executed on the
  emulator in a later validation stage.

**Why.** Every seam used here was pre-declared by the feature ADRs (deep-link
contract, scheduler contract, shared-text entry, hoist note) — the integration wave
only composes them in the app module, keeping the feature ownership boundaries
intact (the sole feature-module edits are the defaulted deep-link hook parameters
and the DI deletion the hoist note prescribed).
