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
