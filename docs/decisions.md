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

## ADR-015: Backup, export & import — versioned ZIP, generic LWW merge, BackupManager seam

**What.** Milestone 6 implements spec feature 6 across `core:database`, `core:data`
and `feature:menu`; the full format contract lives in `docs/backup-format.md`.

- **Format**: a versioned ZIP — `manifest.json` (`schemaVersion` = 1, `appVersion`,
  `createdAt`, per-entity counts) + one JSON array per entity type under `entities/`
  (FULL dumps INCLUDING tombstoned rows — deletions must replicate) + raw bytes of
  bundled attachments under `attachments/<attachmentId>`. Wire DTOs
  (kotlinx-serialization, `core/data/backup/BackupDtos.kt`) are deliberately
  decoupled from Room entities AND domain models with explicit mappers: the backup
  file is a frozen external contract that must outlive schema/model refactors.
  Conventions mirror ADR-004 storage (epoch millis, ISO dates, enum `storageValue`
  with safe fallbacks); readers ignore unknown keys and tolerate missing entity
  files, so additive evolution needs no version bump.
- **New `BackupDao`** on `ClearTravelDatabase` — the one sanctioned deviation from
  the ADR-002 write discipline: full-table dumps (tombstones included) and
  timestamp-PRESERVING `@Upsert`s. The merge already resolved last-write-wins, so
  bumping `updated_at` there would corrupt future merges. The DAO is engine-internal;
  features never see it.
- **Merge = pure generic algorithm** (`BackupMerger.merge(local, backup)` over any
  `SyncableEntity`): backup-only rows insert AS-IS (id + `updatedAt` + tombstone
  preserved); local-only rows untouched; same-id rows resolve ENTIRELY to the newer
  `updatedAt` including tombstone state; ties keep local → repeated imports are
  idempotent by UUID. One implementation + one test suite covers all 11 entity
  types; `importApply` runs it per entity inside a single Room transaction and
  reports an aggregate inserted/updated/skipped `MergeSummary` for the snackbar.
- **Attachments**: bundle ONLY local-only rows (`driveFileId == null`) whose file
  exists at export time; Drive-backed rows carry just the id (bytes re-fetched on
  restore — the Google-milestone seam). On import, winning bundled rows are
  extracted to `filesDir/attachments/<id>` and `localPath` re-pointed (paths are
  device-local; sync fields stay verbatim); drive-id-only rows restore as-is with
  path resolution deferred.
- **Version gate**: `schemaVersion > 1` → typed
  `BackupException.UnsupportedSchemaVersion` at preview AND apply; unreadable
  ZIP/manifest → typed `CorruptedBackup`; stream failures → typed `Io`. The menu UI
  maps each to a distinct snackbar.
- **Public seam** `BackupManager` (Hilt-bound `DefaultBackupManager`):
  `exportToUri(uri)` (SAF write + refresh of the app-storage copy),
  `exportLatestToAppStorage()` (`filesDir/backups`, pruned to the newest 3),
  `importPreview(uri)` (manifest-only — drives the confirm dialog with date +
  counts, zero writes), `importApply(uri)`, `latestLocalBackup()`. The Google
  milestone composes exactly these for Drive upload/auto-restore; no engine changes
  anticipated.
- **feature:menu**: new data-driven root entry → Backup & Restore screen. Export
  via SAF `CreateDocument` (suggested name `cleartravel-backup-YYYYMMDD-HHmm.zip`,
  shared `BackupFileNames` helper), import via SAF `OpenDocument` with
  preview-then-confirm (`AlertDialog` with backup date + record counts), last-backup
  info, progress state, snackbars with per-row merge accounting. `feature:menu`
  gains a `robolectric` test dependency (its ViewModel test touches `android.net.Uri`).

**Why.** The generic merger is the heart: writing LWW once against the
`SyncableEntity` contract (the exact reason ADR-002 exists) makes the merge
provably uniform across entity types and trivially testable without Room, while the
raw-upsert DAO keeps repositories' bump-on-write rule intact everywhere else.
DTO decoupling + manifest versioning keep old backups importable forever and newer
backups gracefully rejected, and the four-method `BackupManager` seam lets the Drive
milestone land without touching the engine.

## ADR-016: Google integration — plain REST clients, state-store reconciliation, menu-hosted hooks

**What.** Milestone 7 implements spec feature 5 (+ the Drive halves of feature 6) in
`core:google` and `feature:menu`, everything OFF by default and fully degraded when
`GOOGLE_WEB_CLIENT_ID` is empty:

- **No Google API client libraries.** Calendar v3 and Drive v3 are called through a
  tiny `HttpURLConnection` wrapper (`rest/GoogleApiHttp`) + kotlinx-serialization
  JSON builders, behind fake-able seams `CalendarClient`/`DriveClient`. The planned
  `google-api-services-calendar/drive` + `google-api-client-android` +
  `google-http-client-gson` dependencies were dropped: the integration needs four
  HTTP verbs and one multipart upload, and the API-client stack (Guava, transport,
  gson) buys APK size and version pinning risk for zero testability — every test
  runs against fakes either way. **No version-catalog changes** — credentials,
  googleid and play-services-auth were already catalogued.
- **Linking** (`auth/`): `GoogleAccountManager` (Hilt-bound facade) over
  `GoogleAuthorizer` (Credential Manager account pick + Play services
  `AuthorizationClient` scopes/tokens) and `GoogleLinkStore` (Preferences DataStore
  `google_link`: email, granted scopes, cached calendar/folder ids, the three
  feature toggles — device-local by design, never in Room/backup). Linking grants
  NO scopes; each Settings toggle requests its own **incremental** scope
  (`calendar.app.created` for Calendar — least privilege that can create the
  dedicated calendar; `drive.file` shared by Drive uploads + Drive backup). A
  consent resolution surfaces as a typed `NeedsScopeConsent(pendingIntent)` the UI
  launches. Blank client id → `GoogleLinkState.NotConfigured` → explanatory
  disabled UI (an ANDROID client id fails with console error `[28444]`;
  docs/google-setup.md).
- **Calendar sync = periodic + on-demand reconciliation, not per-write triggers.**
  `CalendarSyncEngine.reconcile()` diffs current live rows (itinerary items, train
  tickets, flight journeys) against a device-local `CalendarSyncStateStore`
  (rowId → eventId + content fingerprint, SharedPreferences): unknown row → insert
  (event id recorded on the row via the normal repository `save` — the `updatedAt`
  bump is acceptable and keeps the ADR-002 write discipline intact — AND in the
  store), changed fingerprint → PATCH, store entry with no live row → DELETE. This
  makes deletions and trip cascades detectable **without tombstone queries** (no
  `core:data` interface changes — repositories are consumed as-is), and rows
  restored from a backup with a `googleEventId` are ADOPTED (update, never a
  duplicate insert). The dedicated "ClearTravel" calendar is created once (id
  cached), verified per pass, recreated if deleted server-side; disconnect
  optionally deletes it via a cleanup worker. Trigger = one-shot work on
  enable/link + a 6-hourly periodic catch-up (`WorkManagerGoogleSyncScheduler`);
  per-write triggers can be added later by calling `scheduleCalendarSync()` after
  saves — deliberately not wired into repositories this wave to keep `core:data`
  frozen.
- **Drive uploads**: `DriveUploadEngine` drains
  `AttachmentRepository.getPendingDriveUploads()` into the "ClearTravel" folder,
  persisting `driveFileId` via the repository. Boarding passes (a path on the
  flight row, no attachment row) are REGISTERED as FLIGHT `Attachment` rows keyed
  by local path exactly once, so one queue and one `driveFileId` column cover
  them. Failures leave rows pending (worker retry w/ exponential backoff, max 5;
  periodic catch-up). Missing local files are skipped, not retried forever. The
  **restore ladder** `AttachmentFileResolver.resolve()` (local file → Drive
  download into `filesDir/attachments/<id>` + `localPath` re-point → placeholder)
  is the integration point features should call where attachment bytes are read —
  wiring the train/flight sheets onto it is follow-up integration work.
- **Backup-to-Drive**: the `BackupManager` contract is untouched; the hook lives in
  `feature:menu`'s `BackupRestoreViewModel` — after every successful export it
  calls `GoogleSyncScheduler.scheduleBackupUpload()` (worker self-skips when
  disabled/unlinked, so the call is unconditional). `DriveBackupService` uploads
  the newest `filesDir/backups` ZIP (deduped by name), prunes Drive to the **5
  newest**, lists backups (never creating the folder on a read) and downloads one
  to cache. **Fresh-install restore** is hosted entirely in the Backup & Restore
  screen (no app-module edits): when linked and `FreshInstallDetector` reports zero
  trips+journeys+checklists and Drive holds a backup, a one-time prompt offers the
  newest backup (date + size) → download + `importApply`; a manual "Restore from
  Drive" list feeds `importPreview` → confirm → `importApply`.
- **Workers** all use the ADR-013 EntryPoint pattern (no `:app` edits, no
  `@HiltWorker`); `GoogleNotAvailableException` (no link/token) resolves as quiet
  SUCCESS, never retry noise. Calendar event text is built from `core:google`
  string resources through the pure `CalendarEventStrings` value (hard rule 1 —
  calendar events are user-visible text).

**Why.** Reconciliation-by-state-store is the only deletion-safe design that needs
zero contract changes in frozen `core:data`; plain REST keeps the dependency set
lean and the fake seams honest; menu-hosted hooks keep the app module untouched
(parallel-agent boundary); and every brain (mapper, engine diff, prune, ladder,
heuristic, scope gating, link state machine, worker verdicts) is pure enough to be
covered by the ~70 fake-backed unit tests this milestone ships.

## ADR-018: Train route fetch — erail.in rule, hands-free auto-flow, pure RouteMapper

**What.** The trains feature gains a 'Fetch route' action (ticket detail sheet, next
to Check PNR status) that loads the train's COMPLETE station schedule into the
ticket's `train_route_stops`:

- **Source: erail.in** (`erail-route` rule v1,
  `https://erail.in/train-enquiry/{trainNumber}`), chosen by live recon 2026-09-21
  (`docs/recon/train-route-NOTES.md`): bare train number in the URL (no slug, GET
  only), NO captcha, NO login, NO cookie/consent banner (`dismissSelectors` empty),
  route table (`#divRouteList table.RouteList`) server-rendered with a fixed column
  order — verified identical across a single-day (22346) and an overnight (12951)
  train. Rejected: NTES (JS SPA, navigation failed outright from a plain session)
  and the official form-driven schedule page (no deep link, prefill+submit for no
  benefit); trainman/confirmtkt untested fallbacks. Rule + fixture are built from
  the REAL captured DOM of train 22346.
- **ScrapeParams gains `trainNumber`** (additive fourth field, default null) with a
  `{trainNumber}` placeholder — the first `kind: "train"` rule addressed by train
  number rather than PNR. Trains rules keep being looked up by `ruleById` (the
  registry's kind-based selection only exists for flights' IATA dispatch).
- **Hands-free auto-flow, same WebView host pattern as the PNR check** (ADR-011):
  visible WebView → load → readySignal (`tr` count > 1 under the route table) →
  dump → pure `RouteMapper` → `TrainRepository.replaceRouteStops` → auto-close +
  "N stations loaded" snackbar, reopening the detail sheet so the new route is
  immediately visible. No user interaction is needed, but the page stays visible
  (progress is self-evident, and it matches the engine's foreground-only posture,
  ADR-013). The session's `NeedsUserAction` event — emitted mechanically because
  the rule has no `submitSelector` — is deliberately ignored by the route screen:
  a direct-GET page has nothing for the user to do, and changing the engine's
  event semantics for this case was rejected as a non-additive behavior change.
  `ParseFailed` (or a mapper null) keeps the raw page + banner + retry, exactly
  like the PNR flow; stored data is never touched on failure.
- **`RouteMapper` is a pure function** (`map(ticketId, ScrapedData):
  List<TrainRouteStop>?`) owning the erail quirks — `RuleExtractor.postProcess`
  only applies to single-value fields, not rows, so row-level normalization
  belongs in the mapper: dot times `HH.MM` → `HH:mm` (zero-padded, also accepts
  `H:MM`), the `First`/`Last` origin/terminus literals → "" (the model's "no time
  at this end" convention), non-numeric day → 1, station name falls back to the
  station code, `sortOrder` = extraction order. Fewer than two usable stops →
  null → parse-failure path. The rule extracts `halt` and `distance` too, but the
  frozen `TrainRouteStop` (ADR-004) has no such columns — they are captured in the
  rule/fixture for a future ADR + schema migration, not persisted or rendered now.
  Detail-sheet rendering gains only a "Day N" line on stops with `day > 1`.
- **PNR re-check affordance**: verification (this wave) confirmed the Check PNR
  status flow has NO one-shot guard anywhere — the button is always visible,
  every `start()` builds a fresh session/WebView, and
  `applyStatusResult` re-merges per-passenger current status by position on every
  apply — so re-checking already worked indefinitely. The gap was discoverability:
  the detail sheet now shows a hint ("Seats not confirmed yet — check again closer
  to your journey", pure `hasUnconfirmedSeat` predicate: any passenger whose
  current-else-booking status is non-blank and not `CNF*`) next to the Check
  button.

**Why.** erail.in is the only recon'd source that makes the flow fully hands-free —
which is what makes a "route" feature worth one tap. Keeping every quirk in the
pure mapper (not the rule's postProcess, not the engine) preserves ADR-008's
layering: the rule stays a faithful selector map of the real DOM (fixture-pinned),
the engine stays generic, and the one function that interprets erail's conventions
is trivially unit-testable (12 tests) and swappable per-source if a different
schedule site ever ships as a fallback rule.

## ADR-017: Booking-confirmation import — third add-flight path, attachment-row storage

**What.** Flights gain a booking-confirmation (e-ticket PDF/image) import — a document
that EXISTS FROM BOOKING DAY, unlike boarding passes — as a third add path plus an
attach/view flow on existing flights:

- **`core:ocr`: `BookingConfirmationExtractor`** (pure Kotlin, same discipline as
  `BoardingPassTextExtractor` — labeled HIGH / structural MEDIUM / weak LOW, garbage
  → typed EMPTY, never throws) extracts PNR/booking reference, passenger name,
  airline IATA + flight number, date, route, cabin class and seat-if-present from
  airline-direct AND OTA layouts. PNR resolution is tiered: a labeled airline
  record locator (6-char alnum) is HIGH; a labeled OTA reference (MakeMyTrip/
  Cleartrip booking-id shapes) is MEDIUM — a real booking handle but not the
  airline PNR; a bare 6-char alnum is LOW. **Multi-flight handling:** confirmations
  often describe several segments (return trips), so the extractor dedupes distinct
  carrier+number tokens, extracts the FIRST segment fully and exposes
  `additionalFlights` — the form renders a "return leg detected — add it
  separately" hint rather than guessing at multi-leg persistence (one
  `FlightJourney` IS one leg, ADR-004). `OcrPrefillService.prefillBookingConfirmation`
  reuses the barcode-first pipeline: confirmations normally carry no BCBP, but when
  one IS embedded it wins (authoritative; extra BCBP legs feed `additionalFlights`).
  Fixtures (ADR-003/ADR-009 discipline): airline-direct (Air India style), OTA
  (MakeMyTrip style), return-trip (Cleartrip style) + the shared garbage fixture
  asserting the EMPTY fallback.
- **`feature:flights`: storage as `Attachment` rows, NOT a new flight column.** The
  picked file is copied into `filesDir/attachments/<attachmentId>.<ext>` and
  persisted through `AttachmentRepository.save` as an `ownerType = FLIGHT` row with
  its mimeType and `driveFileId = null` — which puts booking confirmations on the
  existing Drive upload queue (ADR-016 drains pending rows automatically) and into
  backup bundles (ADR-015 bundles local-only attachment bytes) with ZERO engine or
  schema changes. `boardingPassPath` stays frozen and special (offline gate
  display); the ADR-016 note that Drive registration keys boarding-pass attachment
  rows by the same local path is honored by the documents list, which dedupes on it.
- **UI.** Add-options sheet gains "Import booking confirmation" (picker → prefill →
  form with per-field confidence markers incl. a new CABIN marker + the return-leg
  hint); the flight detail sheet gains "Attach booking confirmation" for EXISTING
  flights (picker → attachment row → snackbar) and a Documents list (boarding pass
  first, then attachments) — each row opens the existing full-brightness viewer,
  which gained only a `titleRes` parameter. New seams mirror the existing ones:
  `BookingConfirmationImporter` (Hilt-bound `OcrBookingConfirmationImporter`)
  keeps `FlightFormViewModel` and the new `FlightDocumentsViewModel` plain-JVM
  testable, and the documents list is a pure `buildFlightDocuments` function.

**Why.** Attachment-row storage was chosen over a second path column because the
schema is frozen (ADR-004) and the attachments aggregate already carries exactly the
needed behaviors (polymorphic FLIGHT owner, Drive queue, backup bundling, restore
ladder, soft-delete cascade on flight delete) — a column would have required schema,
backup-format and Drive-engine changes for a strictly worse result. First-leg-plus-
hint multi-flight handling keeps the extractor honest about what a single
`FlightJourney` row can represent while still telling the user the return leg was
seen.

## ADR-019: Train-route redesign — ixigo primary + erail fallback, offline route page, card actions

**What.** Redesign of the train-route experience per user steering (2026-09-21):

- **ixigo.com becomes the PRIMARY route source** (`ixigo-route` rule v1,
  `https://www.ixigo.com/trains/{trainNumber}`), built from REAL captures of trains
  22346 (7 stops, single day) and 13151 (85 stops, THREE running days) —
  `docs/recon/ixigo-NOTES.md`. Direct GET, no captcha/consent/expander; the full
  route (even 85 rows) renders in the initial HTML, so the hands-free flow of
  ADR-018 is unchanged. Rule quirks: the row selector MUST use a descendant
  combinator (`tbody tr`) because ixigo div-wraps each `<tr>` inside `<tbody>`
  (React artifact — a child combinator matches zero rows); a defensive
  `.close-banner` dismiss entry covers ixigo's currently-invisible app banner
  (the engine only clicks visible matches, so it is a no-op today); `h1 span.name`
  is name-then-number (`"Vande Bharat Exp 22346 Train"`), the opposite of erail.
  The rule extracts `halt`/`distance` cells (unit-suffixed: `5min`, `127 km`) to
  document the page shape, but they are not persisted (below). Multi-day is a
  user requirement: the harness pins the 22346 fixture; a second fixture-only
  test (`IxigoRouteMultiDayFixtureTest`) feeds the 13151 capture through the same
  pure `RuleExtractor` path (the harness contract is one fixture pair per rule)
  asserting all 85 rows and the day column's 1→2→3 progression at the exact
  midnight-crossing stations (DDU, YJUD).
- **erail.in stays as the FALLBACK** (`erail-route` v2). A crashed validation
  run's live fix — adopted after verifying its tests green — showed erail serves
  a MOBILE layout (`#divResult table.DataTable`, 6 columns) to the in-app WebView,
  not the desktop table the original recon captured; the mobile layout has **no
  Day/Code/Halt columns**. `RouteFetchViewModel` tries sources in priority order
  (`RULE_IDS = [ixigo-route, erail-route]`); on ParseFailed the banner offers
  "Try another source" which cycles to the next rule (and back), alongside Retry
  (same source) — the simple cycling design over any automatic failover, keeping
  the user in control of which site they are looking at.
- **One `RouteMapper` serves both shapes** (their row keys overlap by design):
  colon AND dot times; `starts`/`ends` AND `First`/`Last` end literals → "";
  ixigo's `-` platform placeholder → "" ("platform when known"); `day` uses the
  source column when a cell parses, and is otherwise **inferred per midnight
  crossing** (a stop whose reference time regresses past the previous stop's
  increments the running day) — this keeps the day-less erail mobile fallback
  multi-day-correct and lets one malformed day cell carry the running day forward.
- **OFFLINE route page** (`TrainRouteScreen`/`TrainRouteViewModel`): renders the
  stored route purely from Room (`observeRouteStops`) — station list with
  arrival/departure, day section headers when the journey spans days, platform +
  derived halt per stop, journey duration and a "route fetched" line. The website
  is opened ONLY to (re)fetch: the page's refresh action (and its empty-state
  fetch button) navigate to the existing `RouteFetchScreen` WebView flow, whose
  write lands back in Room and is picked up reactively. "Route fetched" uses the
  newest stop's `updatedAt` (a `replaceRouteStops` batch shares one write stamp);
  the ticket's `lastFetchedAt` keeps meaning "PNR checked" only.
- **Schema decision: NO migration.** `TrainRouteStop` stays frozen (ADR-004) with
  arrival/departure/platform/day/sortOrder. `halt` is DERIVED in the UI from the
  arr/dep delta (`haltMinutes`, +24h wrap across midnight); `distance` is skipped.
  An additive migration (halt/distance columns) was considered and rejected: it
  would touch `core:database`, backup DTOs and merge mappers for data that is
  fully derivable (halt) or cosmetic (distance) — revisit only if a coach-position
  feature lands and needs real schema anyway.
- **Card actions**: each train ticket card gains two `ExplainableIcon`s on its
  right — refresh ("Check PNR status") opening the PNR check flow directly, and a
  place pin ("Train route") opening the offline route page when a route is stored
  (`TrainTicketCard.hasRoute`, live from `observeRouteStops`) or the fetch flow
  directly otherwise. Card tap → detail sheet is unchanged; the sheet's "Fetch
  route" action becomes "View route" with the same conditional. A successful
  fetch now lands on the offline page (previously it reopened the detail sheet),
  so the fetched route is immediately visible from Room.

**Why.** ixigo wins primary on data richness (day column in the WebView-served
layout — erail's mobile layout lost it — plus coach-position data for a future
feature) with equal hands-free ergonomics; erail is kept because it costs one rule
file + fixture and gives a one-tap escape hatch when ixigo's markup changes. The
offline page enforces the offline-first rule for routes (the old flow re-opened the
website every time the user wanted to LOOK at a route); deriving halt keeps the
contract-frozen schema untouched at zero information loss.

## ADR-020: Train card redesign, ticket sharing (image + PNR link), share-sheet file intake

**What.** Three UX additions modelled on a reference train-tracker card, per user
steering (2026-09-21):

- **Card redesign** (`feature:trains` `list/`): every train ticket card is now a
  header band (origin code · journey date + departure time · destination code,
  `primaryContainer`), a slim `tertiary` accent stripe, body lines (bold
  `number - name`, `PNR …`, class/quota, italic tertiary "Updated X ago") with
  compact filled status pills (`RAC - 10`, `WL - 45`, `CNF B4-32`), and a VERTICAL
  action column of `ExplainableIcon`s: refresh (PNR check), seat (opens the detail
  sheet — the seat data lives there), place pin (route, ADR-019) and share. All
  formatting is pure (`TrainCardFormat.kt`: `stationCode` — `Name (CODE)` → `CODE`;
  `relativeAge`; `passengerPillLabel`; `departureTime` from the first stored route
  stop). **Chart status is deliberately NOT shown**: `TrainStatusResult.chartPrepared`
  is never persisted (the frozen `TrainTicket` has no column, ADR-004/005) and a
  guess from per-passenger status would be wrong for RAC/WL; class/quota takes that
  line instead. Schema untouched. The band, body and pill composables are shared
  with the share image so the picture matches the list.
- **Share = image + caption.** The card's share action renders a self-contained
  `ShareTicketCard` (no action icons) OFF-SCREEN into a PNG (`ShareImageRenderer`:
  a throw-away INVISIBLE `ComposeView` attached to the activity decor view, measured
  at 1080 px, laid out, drawn onto a software canvas and removed — all synchronously
  in one main-thread call, so it never reaches a visible frame). Chosen over
  `GraphicsLayer.toImageBitmap()` because it needs no placement inside the live
  composition and works on every Compose version the project pins. Always the LIGHT
  scheme (the image lands in other apps' chat bubbles). PNG → `cacheDir/share/` →
  app `FileProvider` (`<applicationId>.fileprovider`, `res/xml/file_paths.xml`,
  declared in the app manifest — the feature builds the authority from
  `packageName`) → `ACTION_SEND image/png` + `EXTRA_TEXT` caption
  (`trains_share_text`: "Check out my train ticket (PNR …): <link>") via the
  chooser. Render/IO failure degrades to a TEXT-ONLY share of the same caption —
  never a dead button.
- **PNR deep-link format** (`TicketShareLinks`, pure build + parse in ONE place):
  `https://itsluminous.github.io/ClearTravel/pnr/<pnr>` is what we share, plus the
  custom-scheme twin `cleartravel://pnr/<pnr>`; both are `ACTION_VIEW`/`BROWSABLE`
  intent filters on `MainActivity`. **Not `autoVerify`**: there is no owned domain
  serving `assetlinks.json` yet, so the system chooser (browser vs. app) is the
  intended behaviour; the https shape was still chosen so recipients WITHOUT the app
  get a real URL. An incoming link opens the train ADD form carrying only the PNR
  (`TrainTicketFormViewModel.startWithPnr`); the recipient saves and then runs the
  captcha-gated PNR check — auto-fetch is impossible by design (ADR-011). **User
  follow-up:** publish a GitHub Pages landing page at that path (and, later,
  `assetlinks.json` to enable `autoVerify`).
- **Share-sheet FILE intake** (app module — the only place trains AND flights are
  composed): the `ACTION_SEND` filter now also accepts `image/*` and
  `application/pdf`. Shared TEXT keeps its direct train-SMS route; a shared file
  opens a "What's this file?" dialog (Train ticket / Flight boarding pass / Flight
  booking confirmation / Cancel). Before the user picks, a cheap auto-detect
  PRESELECTS the likely type: `OcrSharedDocProbe` runs the PUBLIC
  `OcrPrefillService` boarding-pass path first (barcode-first — a decoded BCBP
  short-circuits everything), else the train and booking extractors concurrently;
  pure `pickLikelyDocType` then applies: barcode wins → else the extractor with the
  most non-empty HIGH/MEDIUM fields (LOW is too noisy to steer) → all-zero or a tie
  → no preselection. The probe reuses the three existing pipelines instead of
  duplicating extraction logic; the cost is up to three OCR runs of the same file
  (follow-up: a single-pass probe in `core:ocr`, out of this wave's ownership).
  Confirmed choice → the EXISTING prefill flows through new additive public entries:
  `TrainsExternalEntry(TrainsEntryRequest.Text|File|Pnr)` (of which
  `TrainsSharedTextEntry` is now a thin wrapper) and
  `FlightsExternalEntry(FlightsEntryRequest.BoardingPass|BookingConfirmation)`
  (hosts `FlightFormScreen` and chains "Save & check status" into
  `StatusCheckScreen`). The app's `MainActivity` owns a single `ExternalEntry`
  state for all of these.

**Why.** The reference card puts every per-ticket action one tap away and makes the
share output self-explanatory; reusing the card's own composables for the share
image keeps the two in lock-step for free. Keeping chart status off the card avoids
inventing data the schema doesn't hold. The https-plus-custom-scheme link pair gives
a shareable URL today without waiting for domain ownership, while parsing both
shapes in one pure object keeps the format a single testable contract. Routing the
file intake through the existing prefill entries (rather than new import code)
honours the no-duplication rule and the feature ownership boundaries: the app module
only composes, `core:ocr` is untouched, and each feature gained exactly one
additive public entry composable.

## ADR-021: Checklist UX rework — editable built-ins, drag-to-reorder, per-row edit

**Supersedes** the "built-in presets are read-only" and "reordering uses up/down
buttons" parts of ADR-010. ADR-010's nested-NavHost and full-screen-detail decisions
stand.

**What.**

1. **Built-in presets are fully editable** — rename, add/edit/remove/reorder items —
   exactly like user presets. The `PresetEditViewModel` `builtIn` guards and the
   read-only banner are gone; `builtIn` remains a display flag ("Built-in" chip,
   built-ins-first ordering) and a **delete guard**: `PresetManagerViewModel.deletePreset`
   still refuses built-ins and the manager hides the trash icon for them, so
   duplicate-then-delete stays the escape hatch for hiding a template. Safety rests on
   the seeder's existing idempotency (ADR-006): `seedBuiltInPresets` keys on each
   definition's FIXED id via `getByIdIncludingDeleted` and skips any preset row that
   exists — live or tombstoned — without touching its items. A renamed built-in, an
   edited/removed/added/reordered template item, and a deleted built-in all survive
   re-seeding unchanged; `PresetSeedingTest` now proves the item-level cases too.
   `core:data` needed no change.
2. **Drag-to-reorder** replaces the up/down arrow buttons in the checklist detail
   screen and the preset editor. `core:designsystem` gains a self-contained helper
   (no new dependencies): `rememberReorderableListState(items, key, onDrop)` owns a
   locally reordered copy of the caller's list (`state.items`), `ReorderHandle` is a
   `DragIndicator` `ExplainableIcon` ("Reorder") on the LEFT of each row whose
   `detectDragGestures` starts the drag on press-and-move immediately (no long-press
   — the handle is dedicated, so row taps keep their meaning) and consumes the
   gesture so the list doesn't scroll instead; `Modifier.reorderableItem` translates
   the dragged row with the pointer (`graphicsLayer` + `zIndex`) and animates every
   other row's placement (`animateItem`). Neighbours swap live while dragging; on
   drop `onDrop(from, to)` fires ONCE with the original and final indices, and the
   local order is held until the caller's list re-emits so there is no flicker back.
   Constraints: the `LazyColumn` must contain only the reorderable rows (layout index
   = list index) and there is no auto-scroll at the edges (packing lists are short).
   The pure index math (`List.moved(from, to)`) is unit-tested.
   ViewModels replace `moveItem(itemId, up)` with `moveItem(from, to)`: rows in the
   affected range take the `sortOrder` of the slot they now occupy (the set of sort
   keys is unchanged, only `min(from,to)..max(from,to)` rows are rewritten); same
   index / out of range is a no-op. Persistence still goes through the existing
   `saveItems` bulk upsert — no repository contract change.
3. **Per-row edit + delete icons.** Every checklist item row and preset item row
   carries an EDIT (pencil) and a DELETE (trash) `ExplainableIcon` on the right,
   replacing the single cross/remove icon. Edit opens the shared
   `TextEditDialog` (`core:designsystem`; takes resolved strings so feature modules
   keep resource ownership) and saves through new ViewModel `renameItem(itemId, text)`
   (trimmed; blank/unchanged ignored) → existing `saveItem`/`saveItems`. Delete keeps
   the soft-delete behavior.

**Why.** Users asked to tune the shipped templates in place; "duplicate to customize"
left a permanently frozen copy in the list and made the templates feel second-class.
ADR-010's original concern — an edited built-in can never be "reset" because the
seeder skips it — is accepted as the cost of editability (the asset can still be
re-added by duplicating any preset or by a future explicit "restore defaults"
action), and the delete guard is kept because it costs nothing and prevents an
accidental permanent loss of a template. Drag handles are the expected mobile
reorder affordance; the fragility ADR-010 worried about is contained by keeping the
gesture on a dedicated handle, keeping the helper dependency-free and small, and
persisting a single `(from, to)` on drop so ViewModel semantics stay deterministic
and unit-testable. Per-row edit fixes the previous "delete and retype" workflow for
typos.

## ADR-022: Train seat map — `train_coaches` (schema v2), scrape `extraRows`, seat layouts as data

**What.** A seat-map screen per train ticket, modelled on a reference train-tracker
app (coach-position strip → accuracy warning → bay-wise berth grid with the
passenger's berths highlighted), built on three additive contract changes:

1. **Schema v2 — `train_coaches`** (the project's FIRST migration). New `core:model`
   entity `TrainCoach(id, ticketId, code, sortOrder)` + ADR-002 sync fields: one row
   per physical coach of the ticket's train in rake order from the engine (`EN`,
   `GN`, `S1`… `PC`, `B4`); `sortOrder` is the 0-based position that doubles as the
   number under each coach in the strip. Room `ClearTravelDatabase` bumps to
   `DatabaseConstants.SCHEMA_VERSION = 2` with a hand-written
   `DatabaseMigrations.MIGRATION_1_2` (`CREATE TABLE` + `ticket_id` index, SQL mirrors
   the exported `schemas/2.json` which is committed); `DatabaseModule` registers
   `addMigrations(*DatabaseMigrations.ALL)`. `MigrationTest` (Robolectric +
   `room-testing`, the exported schema dir is wired as a test asset source) creates a
   REAL v1 file with a ticket/passenger/stop, runs the migration with schema
   validation and asserts the rows survive and the new table is usable — a migration
   whose SQL drifts from the entity fails in CI, not on a phone. `TrainDao` gains
   `observeCoaches` / `upsertCoaches` / `softDeleteCoachesFor`; `TrainRepository`
   gains the additive `observeCoaches(ticketId)` and `replaceCoaches(ticketId,
   coaches)` (soft-delete-then-insert, same shape as `replaceRouteStops`) and its
   delete cascade covers coaches. Backup (ADR-015): `TrainCoachDto` + mappers +
   `entities/train_coaches.json` + manifest key `train_coaches` + one more
   `BackupMerger` pass; NO `schemaVersion` bump — readers already treat a missing
   entity file as an empty list, so pre-ADR-022 backups import unchanged (tested)
   and coaches merge LWW like every other entity (tested).
   `Fixtures.trainCoach` builder added to `core:testing`.
2. **Scrape schema: `extraRows`** (ADR-008 addition, additive). `ScrapeRule` gains
   `extraRows: Map<String, RowExtract> = emptyMap()` and `ScrapedData` gains
   `extraRows: Map<String, List<Map<String, String>>>`: named SECONDARY row-sets
   from the same page, each evaluated exactly like `rows` (own selector, own
   `minRows`). Rules without the field behave byte-for-byte as before (every
   existing rule + fixture stays green; the harness now also compares `extraRows`,
   defaulting to empty). `ixigo-route` v3 declares `coaches` →
   `.coach-position-cntr .coach-position-container .coach-box`, field `code` = the
   box text, `minRows: 0` so a layout without the coach section still yields the
   route (the erail fallback has no coach data at all — that is fine). Fixtures:
   the coach-position section from the REAL desktop captures
   (`docs/recon/ixigo-22346.html` → `page.html`: `EN C1 C2 C3 C4 C5 E1 C6 C7`;
   `ixigo-13151.html` → `multiday.html`: 20 coaches `EN GN GN S1…S7 PC M1 B1…B5
   A1 GN GN`) is spliced verbatim into the existing mobile-layout fixtures — the
   selectors are layout-independent, and whether the LIVE mobile page carries the
   section is exactly what `minRows: 0` makes irrelevant for correctness.
3. **Seat layouts are behavior-as-data** (ADR-003):
   `feature/trains/src/main/assets/seat-layouts/<class>.json` for `SL`, `3A`, `2A`,
   `1A`, `CC`, `EC`, `2S`, `GN`. Format (`SeatLayoutDefinition`): `version`,
   `classCode`, `displayName`, `kind` (`BERTH`|`SEAT`), `total`, `perBay`, and a
   `template` of `perBay` cells `{offset, type, row, column, block}` — berth `n`
   lives in bay `(n-1)/perBay + 1` at offset `(n-1) % perBay`; `row` gives the two
   facing rows of a berth bay, `block` `LEFT`/`RIGHT` puts the cell before/after
   the aisle (side berths are the RIGHT block), `type` is the enum `LOWER`,
   `MIDDLE`, `UPPER`, `SIDE_LOWER`, `SIDE_UPPER`, `WINDOW`, `AISLE` — an enum, not
   a label, so the UI renders it through string resources (hard rule 1). The
   pure `SeatLayoutEngine` validates (offsets must be exactly `0 until perBay`,
   positive total/perBay → typed `SeatLayoutException.InvalidTemplate`; bad JSON →
   `MalformedJson`) and expands the template into `Bay(rows: BayRow(left, right))`
   with `SeatLayout.berth(n)` lookup; a partial tail bay (2A's 43–46 without side
   berths) falls out of `total` naturally. Contents: SL 9×8=72, 3A 8×8=64
   (ICF), 2A 7×6+4=46, 1A 6×4=24 (coupes approximated as cabins), CC 3+2 ×
   16 rows = 78 (last row 3 seats), EC 2+2 = 56, 2S 3+3 = 108, GN 3+3 = 90
   (unreserved approximated). The parameterized `SeatLayoutAssetTest` enumerates
   every file and REQUIRES a pinned sample in its `EXPECTED` table (e.g. SL
   1=LOWER bay 1, 7=S.LOWER bay 1, 23=S.LOWER bay 3, 72=S.UPPER bay 9; 3A 64;
   2A 46=UPPER bay 8) — a layout file without a pin fails CI. `SeatLayoutCatalog`
   (Hilt singleton over a `SeatLayoutSource`: assets at runtime, filesystem in
   tests) caches parsed layouts and resolves unknown/unusable files to null.
   **Class resolution** is the pure `TrainClassResolver`: coach-code prefix first
   — `S`→SL, `B`/`M`→3A, `A`→2A, `H`→1A, `C`→CC, `E`→EC, `D`→2S, `GN`/`GS`/`UR`/
   `SLR`→GN, `EN`/`EOG`/`PC`/`RMS`/`LOCO`→none — then the ticket's `travelClass`
   (aliases `3E`→3A, `FC`→1A, `EA`/`EV`→EC). `SeatBerthParser` turns the free-text
   `seatBerth` (`32`, `32 LB`, `B4 32`, `S1/12`, `12A`) into the berth number: the
   first digit run not preceded by a letter/digit; `0` = unallotted → null.
4. **UI — `SeatMapScreen`** (`feature:trains` `seatmap/`, internal navigation
   state `TrainsScreen.SeatMap`): top bar `"<coach> · <class name>"` +
   `"<number> · <name>"` with a refresh `ExplainableIcon`; passenger chips
   (`B4 - 32`, raw text like `WL 12` when no berth parses); the COACH STRIP — a
   `LazyRow` with a leading engine box (`Icons.Filled.Train`, no custom art) and
   one box per stored coach (code above, position 1..n below — the engine is
   unnumbered; ticket coach = filled primary + filled position pill, selected
   coach = outlined, tap = view THAT coach's class layout); the accuracy WARNING
   banner (`errorContainer`); then a `LazyColumn` of bays, each an outlined block
   whose rows draw left cells · flexible aisle · right (side) cells, every cell
   berth number + type label, the ticket's own berths in `tertiary`. Rendering is
   Room-only (`SeatMapViewModel`: `observeTicket` + `observePassengers` +
   `observeCoaches` + selection); highlighting follows the SELECTED coach —
   passengers seated in it (coach-less passengers count only when the ticket has
   no coach info at all). No stored coaches + no resolvable layout → `EmptyState`
   + "Fetch route & coaches"; no coaches but a resolvable class → a compact card
   with the fetch button above the map (the map still helps); unknown class →
   "No seat map for this coach". Fetch opens the EXISTING `RouteFetchScreen`
   (ONE fetch fills route AND coaches) with `returnToSeatMap = true` so success
   lands back on the map. Strings are module-local (`trains_seatmap_*`).
5. **Entry points.** The card's seat `ExplainableIcon` now opens the seat map
   (it previously opened the detail sheet, whose own seat rows are unchanged);
   the detail sheet gains a "Seat map" button next to "View route".

**Why.** A coach-position feature is exactly the "real schema need" ADR-019
deferred the first migration for, and a table of coach codes is the smallest
schema that carries it (halt/distance stay derived). Keeping the second row-set
an additive rule-schema field (rather than a second rule or a second fetch)
means one page load fills both route and coaches with zero engine behavior
change for existing rules. Seat maps as data mirror the scrape rules: Indian
Railways berth numbering is a fixed template per class, so a bay template +
count is the whole truth, trivially reviewable and pinned per file — and
`perBay`/`total` alone let the same engine cover berth and seating classes.
Everything that decides (engine, resolver, parser, ViewModel projections) is
pure and unit-tested; the UI only draws.

**Addendum (on-device validation 2026-09-21) — `ixigo-route` v4.** The claim above
that the coach selectors are layout-independent was wrong: the LIVE MOBILE page the
WebView renders nests `.coach-boxes > .coach-box-cntr > (.coach-number, .coach-box)`
where `.coach-box` holds the coach TYPE (`CC`, `EC`) and the code sits in the sibling
`.coach-number`, while the desktop recon has `.coach-position-container >
.coach-box-container > .coach-box` with the code as box text. v3 therefore stored
ZERO coaches on a real fetch (route still fine — `minRows: 0` did its job). v4's
row selector is the group `.coach-position-cntr .coach-box-container,
.coach-position-cntr .coach-box-cntr` and the `code` field selects
`.coach-number, .coach-box` (Jsoup `selectFirst` = first match in document order,
so mobile rows yield the code, desktop rows the only box). Fixture `mobile.html` is
the DevTools capture of that live page (`IxigoRouteMobileCoachesFixtureTest`), and
the desktop-shaped `page.html` / `multiday.html` stay green, pinning BOTH layouts.

## ADR-023 — applyStatusResult inserts scraped train passengers (2026-09-21)

ADR-005 defined passenger merge as by-position into EXISTING rows only, which
silently dropped all seat data when a ticket had fewer (or zero) passenger rows
than the live PNR result — the common case for tickets added via the PNR deeplink
or with only a PNR typed in. `applyStatusResult` now additionally INSERTS scraped
passengers beyond the stored count (coach/berth/booking/current status,
sortOrder appended; name left blank — the result page is anonymous). Existing
rows keep the by-position merge semantics unchanged. Live-verified against real
PNR 8553674906 (user-solved captcha).

## ADR-024 — PNR de-duplication, intake landing, OCR reading order (2026-09-21)

**Context.** Live use surfaced four defects in one session: (1) the same PNR could
be added twice (every add path — manual, quick add, SMS/file prefill, deep link —
created a fresh row); (2) a ticket added via the share sheet / PNR link dropped the
user on the default Trips tab afterwards, so the add looked like a no-op; (3) the
PNR-check/route-fetch screens completed INSTANTLY on re-entry (a stale `Applied`
state in a ViewModel scoped to the Journeys back-stack entry re-fired `onApplied`,
even chaining a route fetch for the wrong train); (4) PDF ticket import filled only
PNR/train/stations because ML Kit's block order shredded the ERS table.

**Decisions.**

1. **`TrainRepository.findByPnr(pnr)`** (additive, frozen-contract rule):
   live ticket — archived INCLUDED, tombstones EXCLUDED — whose PNR equals the
   trimmed, case-folded input. DAO `findLiveByPnr` normalizes the stored value in
   SQL (`UPPER(TRIM(pnr))`) so legacy rows match. `TrainTicketFormViewModel.save()`
   runs the check for every save and emits `TrainFormEvent.DuplicatePnr(existingId)`
   — writing nothing — when a match exists whose id differs from the ticket being
   edited (editing keeps its own PNR; re-pointing an edit at ANOTHER ticket's PNR is
   refused too). Hosts show `trains_form_duplicate_pnr` and open the EXISTING
   ticket's detail sheet. Flights: NOT shipped in this wave (needs an equivalent
   `FlightRepository` lookup + host plumbing through `FlightFormScreen`'s callback
   API); tracked as a follow-up in `docs/recon-followup.md`.
2. **Intake landing.** `TrainsExternalEntry`/`TrainsSharedTextEntry` now report a
   `TrainsEntryResult` (`Saved(ticketId, openPnrCheck)` / `DuplicatePnr(existingId)`
   / `Cancelled`) and `FlightsExternalEntry` the saved flight id (or null). The
   shell maps these to a `JourneysDeepLink` (`forTrainsEntry` / `forFlightsEntry`,
   unit-tested) — the SAME hook notification deep links use (ADR-014) — so after
   the hosted form closes the app lands on Journeys with the right segment, cold
   start included. `JourneysDeepLink.entityId` became nullable (cancel = segment
   only) and gained `trainsAction`; `TrainsContent` gained `initialAction:
   TrainsLandingAction` (`OPEN_DETAIL` default = unchanged behaviour,
   `OPEN_PNR_CHECK` for a PNR-only quick add — waits ≤3 s for the saved card to
   arrive from Room, then opens the check so the ADR-023 backfill + route chain
   runs exactly as in-tab, `DUPLICATE_PNR` = existing ticket + notice).
3. **Stale-state guard for scrape screens.** `PnrCheckScreen` and
   `RouteFetchScreen` start the ViewModel and observe completion inside ONE effect
   (`start(); uiState.collect { Applied -> onApplied }`) instead of a separate
   `LaunchedEffect(state)`, so a previous run's `Applied` can never complete a fresh
   run before its page loads.
4. **WebView touch-scroll hardening.** `WebView.configureTouchScrolling()`
   (`core:scrape`) is applied by `ScrapeWebViewController.start()` and the flights
   web-search `PlainWebView`: scrollbars, `OVER_SCROLL_IF_CONTENT_SCROLLS`, and
   `requestDisallowInterceptTouchEvent(true)` on ACTION_DOWN (non-consuming) so no
   ancestor `ViewGroup` can steal vertical drags. Honest note: adb swipes, flings
   and slow motion-event drags all scrolled the live indianrail page on the
   emulator both before and after this change (see `docs/validation-report.md`);
   the reported "taps work, swipes don't" could not be reproduced there, so this is
   defensive and the report documents how it was tested.
5. **OCR reading order.** `OcrTextRecognizer` returns row text rebuilt from ML Kit
   line geometry (`OcrLayout`: rows = lines whose vertical centres are within 0.6×
   the median line height, cells joined by two spaces). `IrctcTicketExtractor`
   parses per cell, supports stacked header/value tables by column index, `Start
   Date*`, parenthesised class codes and a passenger `currentStatus` (additive field
   on `PassengerExtraction`, mapped into the form's current-status column). Pinned
   by a REAL anonymized capture (`irctc-ticket-3.geometry.txt` → `OcrLayoutTest` →
   `irctc-ticket-3.txt` → `expected.json`).

**Consequences.** Adding a PNR that exists is impossible from any path; the user is
taken to the existing ticket. Shares/links always end on Journeys. Re-opening a
check never auto-completes. ERS PDFs import passengers with statuses. Test fakes of
`TrainRepository` (feature:trains, feature:itinerary, core:google) implement
`findByPnr`.
