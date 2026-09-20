# On-Device Validation Report — 2026-09-21

Emulator: AVD `Android_16_AOSP_Medium` (Android 16, AOSP — **no Google Play services**),
app installed from `installDebug` at commit `9f45e64`. Concurrent backup-agent commits
(`7ea9338`…`c46534d`) were in the tree during the run. All UI driven non-interactively
via adb (`uiautomator dump` + `input tap/text/keyevent`), verified via screenshots in
`docs/validation/`.

## Instrumented e2e suite (`connectedDebugAndroidTest`)

First run crashed the instrumentation process after 2 tests:
`IllegalStateException: There are multiple DataStores active for the same file
(settings.preferences_pb)` — Hilt builds one `SingletonComponent` per test **class** in
the same process, and the production `SettingsModule` constructed a second DataStore on
the same file. Fixed inside `app/src/androidTest` (owned scope, commit `9f45e64`):
`TestSettingsModule` (empty, replaces `SettingsModule`) + `TestRepositoryModule`
(mirrors `RepositoryModule`, binds an in-memory `FakeSettingsRepository`).

Final result — **4/4 PASS**:

| Test class | Test | Result |
|---|---|---|
| ChecklistE2eTest | createFromPreset_thenAppendSecondPreset_showsSnackbar | PASS |
| FlightsE2eTest | addFlightManually_detailSheetShowsRoute | PASS |
| TrainsE2eTest | addTicketManually_detailSheetShowsPnr | PASS |
| TripsE2eTest | createTrip_appearsInList | PASS |

## Check matrix

| # | Check | Verdict | Evidence / notes |
|---|---|---|---|
| 1 | Emulator boot + app launch | PASS | Tabs render; POST_NOTIFICATIONS asked exactly once on first launch |
| 2 | Trips tab renders (empty state + FAB) | PASS | `02-trips-tab.png` |
| 3 | Journeys tab (Trains/Flights segments, empty states) | PASS | `03-journeys-tab.png` |
| 4 | Checklist tab | PASS | `04-checklist-tab.png` |
| 5 | Menu tab (Settings / Presets / Backup & Restore / About) | PASS | `05-menu-tab.png` |
| 6 | Checklist create-from-preset | PASS | Name required before Create enables (good); "Domestic trip" → 10 items. `06`, `07` |
| 7 | Checklist append second preset | PASS | "Medicines" appended, snackbar "6 items added", 0 of 16 packed. `08` |
| 8 | Theme switch Menu→Settings→Dark applies live | PASS | Screen avg brightness 22/255 after switch, no restart. `09-theme-dark.png` |
| 9 | Share-sheet ACTION_SEND → prefilled train form | PASS | PNR/train/DOJ/route/class/status/coach/seat all prefilled; "Auto-filled from your ticket" banner. `10` (see note N1) |
| 10 | Deep link `TARGET=flight ENTITY_ID=<uuid>` | PASS | From Trips tab, app switched to Journeys→Flights AND opened the AI 101 detail sheet directly. `12-deeplink-flight.png` |
| 11 | Real scrape: Air India flight status (AI 101, today) | **FAIL** | Page loads with query params (`fno=101&on=20260921` confirmed in logcat), but the status widget never renders — see Defect D1. `14`, `16`, `17` |
| 12 | Real scrape: IRCTC PNR pre-fill injection | PASS | indianrail.gov.in enquiry page loads, PNR input EditText contains `8524567890` (injected), banner "Tap Submit on the page and solve the captcha — the app reads the result automatically" visible. Captcha untouched. `18-pnr-webview.png` |
| 13 | Flight poll scheduling on app open (ADR-014 kick) | PASS | Flight with `sched_dep` +2h saved → relaunch → WorkManager DB shows unique work `flights-status-poll` → `FlightStatusWorker` ENQUEUED, initial delay 30.0 min. Flight with NULL `sched_dep` correctly schedules nothing (documented no-op) |
| 14 | Trip map view | BLOCKED (env) | AOSP image: graceful in-app message "The map needs Google Play services, which is unavailable on this device. The timeline keeps working offline." `20-trip-map-no-key.png`. GMS devices additionally need `MAPS_API_KEY` (currently EMPTY in `local.properties`) |

## Defects found

### D1 (HIGH) — WebView scraping: DOM storage disabled breaks airindia.com

Neither `core/scrape/ScrapeWebViewController` nor
`feature/flights/status/StatusCheckScreen` sets
`settings.domStorageEnabled = true` (only `javaScriptEnabled`). With DOM storage off,
`window.localStorage` is null in the page, and Air India's flight-status clientlib
crashes before rendering its form:

```
Uncaught TypeError: Cannot read properties of null (reading 'getItem')
  source: airindia.com/.../airindia-flightstatus/clientlibs...min.js
```

Observed behavior: page shell loads, OneTrust cookie wall appears (dismissible), then
the widget area stays on "LOADING" forever — no form fields exist, so query-param
injection has nothing to act on and extraction can never fire. **Fix suggestion (app
code, not applied per ownership): enable `domStorageEnabled` (and consider
`databaseEnabled`) on both scrape WebViews.** Many airline SPAs require localStorage,
so this likely affects other rules too.

### D2 (MEDIUM) — Scrape failure is silent on the flight detail sheet

After closing the stuck WebView, the detail sheet still reads "Status never checked" —
no error/raw-page-fallback banner, no last-attempt timestamp. The documented
raw-page-fallback outcome did not surface for the "site JS crashed, no result markers"
case; it appears only page-level failures are handled. Users can't tell a check was
attempted and failed. (`17-flight-sheet-after-scrape.png`)

### D3 (LOW) — Cookie-consent walls stall auto-submit

Air India shows a OneTrust consent dialog on first visit; the instruction banner says
"Complete the search on the page below if it doesn't submit automatically", which
covers it, but the scrape rules could dismiss known consent selectors (OneTrust ids
are stable) to make the flow hands-free. Recorded for rule refinement.

### Notes (not defects)

- **N1** — `IrctcSmsParser` route regex expects station **codes** (`BCT-NDLS`,
  `\b[A-Z]{2,5}-[A-Z]{2,5}\b`); a share text with full names ("MUMBAI CENTRAL-NEW
  DELHI") prefills everything except stations/class. Matches the real IRCTC SMS
  format, so working as designed; a full-name fallback could be a future rule tweak.
- **N2** — e2e DataStore crash root cause is a test-infra property (per-class Hilt
  components), fixed in androidTest; production is unaffected (single process-long
  component).
- **N3** — The AI 101 flight was created without a departure time; startup poll kick
  correctly no-ops for it (ADR-013/014 `NextPollDelay.compute(null) == null`).

## Needs the user

1. `MAPS_API_KEY` in `local.properties` is empty — trip map cannot be validated end to
   end even on a GMS device until a key is provided.
2. Map view on GMS hardware: this run used an AOSP image; re-verify the Maps path on a
   Play-services emulator image or physical device (read-only phone was not touched).
3. Decision on D1 fix (one-line WebView settings change in `core:scrape` +
   `feature:flights`) — outside this validation agent's ownership.

## Artifacts

- `connected.log` (git-ignored) — final `connectedDebugAndroidTest` run, BUILD SUCCESSFUL.
- `docs/validation/*.png` — 15 screenshots referenced above.
