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

## Re-verification after fixes — 2026-09-21 (final-verify agent)

Same AVD (`Android_16_AOSP_Medium`, resumed from the interrupted session), current
build re-installed via `installDebug`, app relaunched fresh (data was clean — flows
re-driven from scratch via adb, all commands timeout-wrapped).

### D1 (domStorageEnabled) — **FIXED, verified on device**

Flight AI 101 / 2026-09-21 → "Save & check status": the Air India FLIGHT STATUS page
fully rendered its form within ~12 s of load — heading, radio group, and inputs
prefilled `AI - 101` / `21 Sep 2026` from the query params. No "LOADING" stall, no
localStorage crash. `21-ai-webview-rendered.png`.

### D3 (dismissSelectors / OneTrust) — **FIXED, verified on device**

The OneTrust consent wall never blocked the page at any point in the run — no dark
filter, no dialog visible in any screenshot (the `#onetrust-accept-btn-handler`
dismiss fires on page-finish and on every poll tick). The flow was fully hands-free.

### D2 (failure banner + outcome line) — **FIXED, verified on device**

The real site had no data for AI 101 on this date ("THIS INFORMATION IS NOT
AVAILABLE" — zero `.flight-status-card` nodes), so the ready signal never fired and
the 5-minute timeout produced the new `ParseFailed` path:

- Error banner over the still-visible raw page: "Couldn't read the results — your
  saved data is unchanged. Retry, or read the page below and update the flight
  manually." with **Retry** and **Close** actions. `22-ai-parsefail-banner.png`.
- Close → detail sheet reopens showing the error-colored transient outcome line
  "Last check (21 Sep, 01:44): couldn't read the airline page — data unchanged",
  while the durable status stays "Status never checked" (data genuinely unchanged).
  `23-flight-sheet-failed-outcome.png`.

### FULL end-to-end extraction success (bonus)

A second flight AI 2425 / same day (a route the site had live data for) extracted
successfully in ~30 s: "Status updated" confirmation, then the detail sheet and list
card show status **Cancelled**, Estimated dep 10:30 / arr 12:50, durable "Checked
21 Sep, 01:49" line plus outcome line "Last check (21 Sep, 01:49): status updated".
The rule pipeline (query-param load → OneTrust dismissal → ready signal → rows
extraction → mapper → Room) works end to end against the live site.
`24-flight-sheet-updated.png`.

### Trains PNR spot-check post-D1

Ticket PNR 8524567890 → "Check PNR status": indianrail.gov.in enquiry page renders
normally with the DOM-storage change, PNR injected into the form, instruction banner
"Tap Submit on the page and solve the captcha" shown. Captcha untouched, closed.
`25-pnr-webview-post-fix.png`.

### Instrumented suite re-run on the fixed build

`connectedDebugAndroidTest`: **4/4 PASS** (Checklist / Flights / Trains / Trips e2e),
BUILD SUCCESSFUL. Emulator killed and confirmed gone afterwards.

| Defect | Verdict |
|---|---|
| D1 domStorageEnabled | FIXED — form renders, prefill works |
| D2 failure banner + outcome | FIXED — banner w/ Retry over raw page + timestamped sheet line |
| D3 OneTrust dismissal | FIXED — wall never blocks, flow hands-free |

## Route redesign + booking confirmation validation (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

Validation of commits f98ea7a / 071f490 / 12d1a26 / 5600867 plus the live-drift rule fix
(ixigo-route v2 — live ixigo serves the in-app WebView a MOBILE layout:
`table.train-route-cntr`, five columns Station · Arrives · Depart · Halt · PF, **no
Day column and no station-code cell**; the code is recovered from the station link's
href slug and the running day is inferred by `RouteMapper`'s midnight-crossing rule).
All UI verification was text-based (uiautomator dumps); screenshots below were
captured blind for human review only.

Quality gate before flashing: ktlint + lint + **563/563 unit tests** + assembleDebug +
assembleDebugAndroidTest — BUILD SUCCESSFUL.

### Live route fetch — train 22346 (single day)

Route pin on the ticket card opened the route flow; a fresh **live fetch against
ixigo.com succeeded with rule v2** ("Route fetched" timestamp advanced to the fetch
minute). Offline route page renders from Room: **7 stations** — Gomati Nagar
(Dep 15:20, Platform 2) → Ayodhya 17:15/17:20 → Varanasi Jn 19:50/19:55 →
Dd Upadhyaya Jn 20:45/20:50 → Buxar 21:50/21:52 → Ara Jn 22:33/22:35 →
Patna Jn (Arr 23:45, Platform 8), journey duration 8h 25m, derived halts shown
("5 min halt" etc.). Room cross-check: 7 non-tombstoned stops, day=1 throughout.
`27-route-22346-offline.png`, `28-route-22346-live-refetch.png`.

### Offline reopen proof — 22346

With **wifi + data disabled**, app force-stopped and relaunched: route pin → route
page still renders the full 7-station list purely from Room (no fetch). Network
re-enabled afterwards. `29-route-22346-offline-reopen.png`.

### Multi-day route — train 13151 (three running days)

Fresh live fetch also succeeded (timestamp advanced). Offline page shows **85
stations** over a **45h 5m** journey with **Day 1 / Day 2 / Day 3 section headers**:
Kolkata Chitpur (Dep 11:45) → Jammu Tawi (Arr 08:50). Room cross-check: 85
non-tombstoned stops, day counts 32/38/15; the inferred day switches at exactly the
recon-documented midnight crossings — **Dd Upadhyaya Jn (Arr 01:25 → Day 2)** and
**Yamunanagar Jud (Arr 00:06 → Day 3)** — confirming the mapper's inference on the
day-less mobile layout. `30-route-13151-day3-terminus.png`.

### Card actions

Trains list dump shows both `ExplainableIcon`s on every ticket card: content-desc
"Check PNR status" (refresh) and "Train route" (pin). Refresh → PNR check WebView
opens with the instruction banner "Tap Submit on the page and solve the captcha —
the app reads the result automatically"; closed without solving. Pin → offline route
page directly (route stored). `26-trains-list-card-icons.png`,
`31-card-refresh-pnr-webview.png`.

### Booking-confirmation import (ADR-017)

Synthetic e-ticket PNG (Air India / AI 101 / PNR X9K2LQ / DEL→BOM / Economy) pushed
to `/sdcard/Download`. Flights → FAB → "Import booking confirmation" → system picker
→ booking.png → form opened **prefilled from OCR**: flight number 101 (High
confidence), PNR X9K2LQ (Low confidence — review), cabin ECONOMY (High), From DEL /
To BOM (High), plus the "Filled from the scanned e-ticket — review carefully" banner.
Airline was OCR-misread as "AL" from the synthetic render (corrected to AI in-form;
extraction quality of a PIL-drawn PNG is not representative), date filled manually.
Saved → card "AI 101 · DEL → BOM · Tue, 22 Sep 2026". Detail sheet shows the
**Documents** section with a "Booking confirmation — Tap to view" row (and the
"Attach booking confirmation" action); tapping it opened the full-brightness viewer
titled "Booking confirmation". `32-booking-import-prefilled-form.png`,
`33-flight-sheet-documents-row.png`, `34-booking-confirmation-viewer.png`.

### Instrumented regression

`connectedDebugAndroidTest`: **4/4 PASS** (ChecklistE2eTest / FlightsE2eTest /
TrainsE2eTest / TripsE2eTest), BUILD SUCCESSFUL. Emulator killed after the run.

| Check | Verdict |
|---|---|
| 22346 live fetch (ixigo rule v2) | PASS — 7 stations, Gomati Nagar 15:20 → Patna Jn 23:45 |
| 22346 offline reopen (network off) | PASS — full list from Room |
| 13151 multi-day | PASS — 85 stations, Day 1/2/3 headers, inferred crossings at DDU 01:25 + YJUD 00:06 |
| Card icons (refresh + pin) | PASS — content-descs present; refresh opens PNR WebView w/ banner |
| Booking-confirmation import | PASS — picker → OCR prefill → save → Documents row → viewer |
| Unit tests | 563/563 |
| Connected e2e | 4/4 |

## Card redesign + share + file intake validation (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

Scope: ADR-020 — redesigned train ticket card, image + PNR-link share, PNR deep links,
share-sheet file intake. Fresh install (`pm clear`), all UI assertions taken from
`uiautomator dump` text (screenshots captured blind and downscaled to 800px, never
opened by the validator).

Gate: `ktlintCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest`
BUILD SUCCESSFUL — **601 unit tests, 0 failures, 0 skipped**. No fixes needed.

### Card redesign

Manual ticket PNR 8524567890 / train 22346 / Gomati Nagar → Patna Jn / passenger with
booking status "RAC 10", no date. Trains list dump right after save: header band
texts "Gomati Nagar", "Date not set", "Patna Jn" (`stationCode` passes bare names
through); title "22346" (name blank → exactly the number); "PNR 8524567890";
"Status never checked" (never fetched); pill **"RAC - 10"** (`passengerPillLabel`);
and all four icon content-descs "Check PNR status", "Seat details", "Train route",
"Share ticket". `35-card-redesign-list.png`.

### Share (image + caption)

Tap "Share ticket" → system chooser (`com.android.intentresolver/.ChooserActivity`)
with heading **"Sharing image"** and the caption text
"Check out my train ticket (PNR 8524567890): https://itsluminous.github.io/ClearTravel/pnr/8524567890";
`dumpsys activity` shows the CHOOSER intent carrying `clip={text/uri-list {U(content)}}`;
`run-as` lists `cache/share/ticket-8524567890.png` (40 218 B, header bytes = PNG
IHDR 1080×523). ClearTravel itself is listed as a target (intake filter, by design).
`36-share-chooser.png`.

### PNR deep links

- `am start -a VIEW -d https://itsluminous.github.io/ClearTravel/pnr/8553674906`
  (implicit) → opens the default browser (WebView Browser Tester): expected, the
  filter is **not autoVerify** (ADR-020). `pm query-activities … BROWSABLE` lists
  `com.itsluminous.cleartravel.MainActivity` as a candidate; re-issued with the
  package → "Add train ticket" form with PNR field "8553674906" and the
  "Auto-filled from your ticket — review before saving" banner.
  `37-deeplink-https-form.png`.
- `cleartravel://pnr/8553674906` → same form, PNR prefilled, hot launch and cold
  launch (after `force-stop`). `38-deeplink-custom-scheme-form.png`.

### Share-sheet file intake

Share-sheet visibility proof: `pm query-activities -a android.intent.action.SEND -t image/png`
and `-t application/pdf` both list `com.itsluminous.cleartravel.MainActivity`
(alongside Messaging / Bluetooth / Print); `text/plain` still resolves to the app.

Verification paths used:
1. **Shell path** (synthetic 200×120 PNG pushed to `/sdcard/Download`, MediaStore
   `content://media/external/images/media/<id>` via
   `am start -a SEND -t image/png --eu android.intent.extra.STREAM … --grant-read-uri-permission`)
   → the **"What's this file?"** dialog appeared with the three options Train ticket /
   Flight boarding pass / Flight booking confirmation + Cancel / Continue, no
   preselection. Note: the shell (uid 2000) cannot actually grant MediaStore URIs
   (`SecurityException: … has no access to content://media/…` in logcat), so the
   probe saw an unreadable file — the dialog degraded correctly to "no suggestion"
   and Train ticket → Continue opened a blank add form. `39-intake-dialog.png`.
2. **Real share path** (Files app, `VIEW_DOWNLOADS`, long-press `card-share.png` — the
   app's own share PNG copied out of its cache — → Share): the system sheet
   ("Sharing image") lists **ClearTravel**; tapping it → dialog with "Train ticket"
   radio **checked** and the **"Suggested"** label under it (OCR probe finished in
   ~2 s); Continue → "Add train ticket" form with PNR **8524567890** read from the
   image and the auto-filled banner. `41-files-app-share-sheet-cleartravel.png`,
   `42-intake-dialog-suggested-train.png`, `43-intake-form-ocr-prefilled.png`.

### Instrumented regression

`connectedDebugAndroidTest`: **4/4 PASS** (ChecklistE2eTest / FlightsE2eTest /
TrainsE2eTest / TripsE2eTest), BUILD SUCCESSFUL, no androidTest changes needed.
Emulator killed after the run.

| Check | Verdict |
|---|---|
| Card: band stations, title 22346, PNR line, RAC pill, 4 icon content-descs | PASS |
| Share: chooser "Sharing image" + caption with PNR link, content URI clip, PNG in cache | PASS |
| Deep link https (implicit → browser; app is a candidate; explicit → form w/ PNR) | PASS (browser default expected, no autoVerify) |
| Deep link cleartravel:// hot + cold → form w/ PNR | PASS |
| Share-sheet visibility (`query-activities` SEND image/png + application/pdf) | PASS |
| Intake dialog 3 options (shell path, unreadable URI → no suggestion) | PASS |
| Intake real share from Files app → Suggested Train ticket → OCR-prefilled form | PASS |
| Unit tests | 601/601 |
| Connected e2e | 4/4 |

## Checklist UX rework (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

Scope: ADR-021 — editable built-in presets, drag-to-reorder handles replacing the
up/down arrows, per-row edit/delete icons on checklist and preset items. Fresh
install (`installDebug` after uninstall), verified through `uiautomator dump` text
only; screenshots captured blind (`screencap` → `sips -Z 800`, never opened).

### Built-in preset is editable and the edit survives relaunch

1. Menu → Manage presets: the four built-ins still show the **Built-in** chip and a
   **Duplicate preset** icon, and (unchanged) NO delete icon — the delete guard is kept.
2. Open **Domestic trip**: top bar reads **"Edit preset"** (not "Preset"), the **Name**
   text field is present, every row has a **Reorder** handle on the left plus **Edit
   item** and **Delete item** on the right, and the **Add item** field with **Add item
   to preset** is at the bottom. No read-only banner text anywhere in the dump.
   `44-preset-builtin-editable.png`.
3. Pencil on "Phone charger" → **Edit item** dialog (field "Item", Cancel/Save) →
   replaced with "USB-C charger" → Save → row reads **USB-C charger**.
4. `am force-stop` + relaunch → Menu → Manage presets → Domestic trip: the row still
   reads **USB-C charger**, the preset is listed exactly once, and no "Phone charger"
   reappeared — the seeder (keyed on the fixed preset id, tombstone-aware) neither
   reverts nor duplicates edited built-ins. (Production seeding runs only from Room
   `onCreate`; the explicit re-seed path is covered by `PresetSeedingTest`.)

### Checklist detail: drag handles, drag persistence, per-row edit

1. Checklist tab → FAB → "Goa packing" from **Medicines** → detail: 6 rows, each with
   `Reorder` (x≈32–95, left edge), `Edit item` and `Delete item` content-descs; **no**
   "Move item up"/"Move item down" nodes. `45-checklist-detail-handles.png`.
2. Drag down: `input swipe 63 434 63 700 1500` on the first handle → order became
   Band-aids, Antiseptic cream, **Paracetamol**, ORS sachets, … (row 1 → row 3).
3. Drag up: `input swipe 63 812 63 420 1500` on the fourth handle → **ORS sachets**
   moved to row 1 (ORS sachets, Band-aids, Antiseptic cream, Paracetamol, …).
4. `am force-stop` + relaunch → Checklist → Goa packing: the dragged order is
   unchanged — the drop persisted through `saveItems`.
5. Pencil on "Band-aids" → Edit item dialog pre-filled with "Band-aids" → replaced
   with "Plasters" → Save → row 2 reads **Plasters**, position preserved.
   `46-checklist-edit-item-dialog.png`, `47-checklist-after-drag-and-edit.png`.

### Instrumented regression

`connectedDebugAndroidTest`: **4/4 PASS** (ChecklistE2eTest — extended to assert one
handle + edit + delete per row, zero move arrows, and the pencil → dialog → rename
flow — plus FlightsE2eTest / TrainsE2eTest / TripsE2eTest). Emulator killed after the
run.

| Check | Verdict |
|---|---|
| Built-in preset opens editable (Edit preset title, Name field, add-item, no banner) | PASS |
| Built-in item rename survives force-stop + relaunch, no duplicate preset | PASS |
| Built-in delete still hidden in Manage presets (guard kept) | PASS |
| Detail rows: Reorder handle left, Edit/Delete right, no up/down arrows | PASS |
| Handle drag down and drag up reorder live | PASS |
| Dragged order survives relaunch | PASS |
| Item text edit via pencil dialog | PASS |
| Unit tests | 617/617 |
| Connected e2e | 4/4 |

## Seat map validation (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

Scope: ADR-022 — `train_coaches` schema v2 + first Room migration, `ixigo-route`
`extraRows.coaches`, seat layouts as data, the seat-map screen and its entry points.
Verified through `uiautomator dump` text only; screenshots captured blind
(`screencap` → `sips -Z 800`, never opened). Gate before/after:
`ktlintCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` →
BUILD SUCCESSFUL.

### Migration smoke — real v1 → v2 upgrade

The install found on the emulator (16:15, the seatmap stage's own check) was already
schema v2 (`PRAGMA user_version` = 2, 20 coaches for 13151), so over-installing it is
a v2→v2 no-op: done anyway — app opens, 13151 / PNR 8524317690 / `CNF B4-32` still
listed. For a GENUINE v1→2 run the pre-ADR-022 commit `05eedaa` was built in a git
worktree and installed after an uninstall: on that v1 build (`user_version` 1, no
`train_coaches` table) ticket **22346 Hool Express**, PNR 4412345678, class CC,
passenger coach **C4** seat **32** was created through the UI (card shows
`CNF C4-32`, icon desc `Seat details`). `installDebug` of the v2 build over it →
launch: no `FATAL` in logcat, `user_version` 2, `train_coaches` present (0 rows),
ticket + passenger rows intact, card lists `22346 - Hool Express` / `CNF C4-32` and
the icon desc is now `Seat map`. **PASS.**

### Live 22346 — coach strip, class resolution, highlight

1. Seat icon on the fresh (migrated, no coaches) CC ticket → header **`C4 · AC Chair
   Car`** / `22346 · Hool Express`, chip `C4 - 32`, compact card *Coach positions not
   fetched* + **Fetch route & coaches**, warning banner, and the CC grid already
   rendered (class resolved from coach code `C` → CC; `Row 1`… cells `Berth 1,
   WINDOW` · `2, MIDDLE` · `3, AISLE` │ `4, AISLE` · `5, WINDOW`).
   `50-seatmap-cc-no-coaches-fetch-card.png`.
2. **Fetch route & coaches** → ixigo WebView → back on the map with `7 stations
   loaded`, but the strip still said *Coach positions not fetched*; DB: 7 route
   stops, **0 coaches**. Root cause (DOM inspected live through the WebView Shell's
   DevTools socket): the mobile page nests `.coach-boxes > .coach-box-cntr >
   (.coach-number = code, .coach-box = TYPE)`, while the desktop recon the fixture
   was spliced from has `.coach-position-container > .coach-box-container >
   .coach-box` = code — rule v3 matched nothing live. **Fixed as data** (see
   "Fixes" below), reinstalled, refresh icon on the seat map → strip now renders
   **Engine, C1 (1), C2 (2), C3 (3), C4 (4), C5 (5), E1 (6), C6 (7), C7 (8)** —
   content-descs `Coach C4 at position 4 — tap to view its seat map` etc.; DB
   `train_coaches` = `0|EN 1|C1 2|C2 3|C3 4|C4 5|C5 6|E1 7|C6 8|C7`; the strip
   opened auto-scrolled to C4 (C1 clipped at the left edge, Engine off-screen until
   swiped). Warning text: *Coach position may not be accurate for certain trains —
   verify at the station.* `51-seatmap-22346-strip-c4.png`.
3. Grid scrolled to Row 7: **`Berth 32, MIDDLE — your berth`** between `Berth 31,
   WINDOW` and `Berth 33, AISLE` — the highlight IS exposed as a content-desc
   (`trains_seatmap_cell_yours`), exactly one such node.
   `52-seatmap-22346-cc-grid-seat32.png`.
4. Tap **E1** → header `E1 · Executive Chair Car`, 2+2 EC rows (`Berth 1, WINDOW` ·
   `2, AISLE` │ `3, AISLE` · `4, WINDOW`), 20 cells composed, zero "your berth"
   nodes (passenger is in C4). Tap **C2** → `C2 · AC Chair Car`, CC 3+2 rows again,
   zero highlights. `53-seatmap-22346-coach-e1-ec.png`.
   Not verifiable via dump: the ticket-coach (filled) vs selected (outlined) strip
   state — the box content-desc carries code + position only, the state is colour.
   Observation (not fixed): the SEAT-kind cell desc still reads "Berth n, …" for CC/EC
   (`trains_seatmap_cell` is shared by both kinds).

### No-data path

Fresh ticket **12301 Rajdhani** (no class, no coach, no fetch) → seat icon → header
`Seat map` / `12301 · Rajdhani`, EmptyState *Coach positions not fetched* / *Fetch the
route once and the coach order is saved offline.* — but NO fetch button in the dump:
`EmptyState` fills max size and pushed the `Button` below the fold (only the top-bar
refresh icon was reachable). One-line fix (bounded height, same pattern as the
no-layout EmptyState in the same file) → dump shows **Fetch route & coaches** at
y≈1684; tapping it opens *Fetch train route* / *Loading the schedule from ixigo Train
Route…*. `54-seatmap-no-data-empty-state.png`. **PASS after fix.**

### Fixes (this validation)

- `core:scrape` `ixigo-route.json` **v4**: `extraRows.coaches.rowSelector` =
  `.coach-position-cntr .coach-box-container, .coach-position-cntr .coach-box-cntr`,
  `code` = `.coach-number, .coach-box` (first match in document order → code on
  both layouts). New fixture `mobile.html` (DevTools capture of the live mobile
  page) + `IxigoRouteMobileCoachesFixtureTest` (2 tests: codes not types; route +
  header intact); `page.html`/`multiday.html` unchanged and green. ADR-022 addendum.
- `feature:trains` `SeatMapScreen`: no-coaches/no-layout `EmptyState` bounded to
  320dp so the fetch button stays on screen.
- `app` androidTest: **`SeatMapE2eTest`** (hermetic — seeds ticket + passenger C4/32
  + the 22346 rake through the injected `TrainRepository`; asserts seat icon →
  `C4 · AC Chair Car`, coach box `C4 at position 4`, warning, `Row 1`, scrolls the
  bay `LazyColumn` to exactly one `Berth 32, MIDDLE — your berth`).

### Instrumented regression

`connectedDebugAndroidTest`: **5/5 PASS** (SeatMapE2eTest new, ChecklistE2eTest,
FlightsE2eTest, TrainsE2eTest, TripsE2eTest). Emulator killed after the run.

| Check | Verdict |
|---|---|
| v1→2 migration on real data (v1 build → v2 build, no crash, rows intact, `train_coaches` created) | PASS |
| v2→v2 over-install keeps existing tickets | PASS |
| Live 22346 fetch stores coaches `EN C1 C2 C3 C4 C5 E1 C6 C7` | FAIL on v3 → PASS after rule v4 |
| Coach strip: codes + positions 1..8, engine box, auto-scroll to C4 | PASS |
| Class resolution C4 → CC (`AC Chair Car`), E1 → EC (`Executive Chair Car`) | PASS |
| Warning banner text | PASS |
| Berth 32 highlighted (`— your berth` content-desc, exactly one) | PASS |
| Tap other coach re-renders its class layout | PASS |
| No-data path: EmptyState + fetch button reachable | FAIL → PASS after fix |
| Unit tests | 676/676 |
| Connected e2e | 5/5 |

## Remaining fixes (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

Method as before: every adb call timeout-wrapped, UI read via `uiautomator dump`
text (WebView page content included — the emulator exposes the Chromium a11y tree),
blind screencaps `sips -Z 800`'d into `docs/validation/55–59`. Device state on
arrival: real ticket PNR 8553674906 (20933, 2A, two RAC passengers, route stored),
present TWICE — the user's duplicate that motivated fix 5.

### Fix 1 — PDF ticket import (real ERS `8553674906.PDF.pdf`)

- Captured what ML Kit really sees via the `@Ignore`d `OcrCaptureHarnessTest`
  (`core/ocr` androidTest, PDF pushed to the test app's external files dir, logcat
  tag `OcrCapture`). BEFORE: `journeyDate=NONE, travelClass=NONE, passengers=[]` —
  ML Kit's `Text.text` had the ERS table shredded into one cell per line.
- AFTER (`OcrLayout` row reconstruction + extractor rework): shared the PDF into the
  app through its own FileProvider (`content://…fileprovider/share/ers.pdf`, copied
  in with `run-as`; a MediaStore URI is refused — `no access to
  content://media/external/file/129` — since a shell grant does not reach the
  app). Intake dialog auto-suggested **Train ticket**; the form dump shows PNR
  `8553674906`, train `20933` / `UDN DANAPUR EXP`, `Sep 29, 2026`, `UDN` → `DNR`,
  class `2A`, quota `GN`, passengers `BHUVNESHWAR SING` (booking `RAC/12`) and
  `ARTI DEVI` (booking `WL/1`) — coach/berth blank because RAC/WL have none yet
  (screenshot 59). Fixture `irctc-ticket-3` (anonymized) pins the same text.

### Fix 2 — share intake lands on Journeys

- Cold start, `am start -a SEND -t text/plain --es EXTRA_TEXT "PNR:4412345678,TRN:
  12951,DOJ:29-10-26,3A,NDLS-BCT,…"` → prefilled form → **Save ticket** → dump
  shows the Journeys segmented row (`Trains | Flights`) with the new ticket's detail
  sheet open (`4412345678`, `12951`, `NDLS → BCT`, passengers with `Coach B4 · 32`);
  back → the Trains list with card `PNR 4412345678` (screenshot 56). Before the fix
  the same flow ended on the Trips tab ("No trips yet").
- Cold start `cleartravel://pnr/1234567891` (PNR-only quick add) → Save → landed on
  Journeys/Trains AND the PNR check opened by itself (`Check PNR status`, indianrail
  page loaded with the PNR) — the in-tab ADR-023 chain now also runs for the link
  path. Cancel from a shared-file form → Journeys/Trains list.

### Fix 3 — shared image without the freshness line

Code-level change only (`TicketBodyLines(showFreshness = false)` from
`ShareTicketCard`); the list card still shows `Updated X ago` / `Status never
checked` in every dump above. No `ShareTicketCardTest` exists (off-screen bitmap
composable); unit coverage of `relativeAge` (`TrainCardFormatTest`) is unchanged.

### Fix 4 — WebView touch scroll (+ stale-state bug found on the way)

- **Could not reproduce the reported "swipes don't scroll".** On the CURRENT build
  (before any change) and again on the fixed build, all three input styles scrolled
  the live indianrail page inside the PNR-check WebView (bounds `[0,547][1080,2128]`):
  `input swipe 540 1800 540 800 400` moved `Submit` from y=1665 to y=568 and pulled
  the `Copyright © 2017 …` footer into view (screenshots 57 → 58); a 50 ms fling and
  an 8-step `motionevent DOWN/MOVE/UP` slow drag also moved the page (Submit
  1751 → 1345); scrolling worked with the IME open too. Page states covered: fresh
  load with the tall header, after the site's own error anchor, after focusing the
  PNR field. Swipes that START below y≈2128 land on the bottom NavigationBar, not
  the WebView — the most likely way an adb repro "hits" the bug.
- What DID reproduce, and blocked the scroll check twice: re-opening the PNR check
  (or the route fetch) after a completed one finished INSTANTLY — the ViewModel is
  scoped to the Journeys back-stack entry, its `Applied` state survived, and
  `LaunchedEffect(state)` re-fired `onApplied` before the page loaded (the fake
  ticket even got train 20933's route fetched for it). Fixed by starting and
  observing inside one effect (ADR-024 §3); verified: quick-add of `1234567891`
  now shows the check page and stays there.
- Defensive hardening shipped for every WebView host (ADR-024 §4):
  `WebView.configureTouchScrolling()` — scrollbars, over-scroll, non-consuming
  `requestDisallowInterceptTouchEvent(true)` on ACTION_DOWN.

### Fix 5 — duplicate PNR refused

- Cold start `cleartravel://pnr/8553674906` (a PNR already on device) → form → Save
  → landed on Journeys/Trains, snackbar **"Ticket with this PNR already exists"**
  with a **View** action, still exactly the two pre-existing `8553674906` cards
  (no third card; screenshot 55). Tapping **View** opened the existing ticket's
  detail sheet (`Ticket details`, `8553674906`).
- Found on the way: the snackbar sat ON the FAB and the FAB won taps meant for the
  action (a tap at the action's centre opened the add sheet). The Trains snackbar
  host now sits 80 dp above the FAB on the list screen (Material placement).
- The three test tickets created during this run (4412345678, 1234567890,
  1234567891) were deleted through the detail sheet; the user's two real cards were
  left untouched.

| Check | Verdict |
|---|---|
| PDF import fills date/class/quota/passengers (real ERS) | FAIL → PASS |
| Shared text → save → Journeys/Trains with the new card | FAIL → PASS |
| PNR link quick add → Journeys/Trains → PNR check auto-opens | PASS |
| PNR check re-entry no longer auto-completes (stale `Applied`) | FAIL → PASS |
| WebView touch scroll (swipe / fling / slow drag) | PASS before and after (not reproducible) |
| Duplicate PNR via link → notice + View → existing sheet, no new card | PASS |
| Snackbar action reachable above the FAB | FAIL → PASS |

### Instrumented regression (this wave)

`connectedDebugAndroidTest`: **6/6 PASS** (`TrainsE2eTest` gained
`addSamePnrTwice_isRefusedWithNotice_andKeepsOneCard`; ChecklistE2eTest,
FlightsE2eTest, SeatMapE2eTest, TripsE2eTest unchanged) + `core:ocr`'s capture
harness reported SKIPPED (`@Ignore`). Writing the new e2e exposed one more real
papercut: the previous save's "Ticket saved" snackbar still sat over the form's
Save/Cancel row when the next add started quickly and swallowed the tap
(`printToLog` showed the filled form frozen after "Save"); the Trains host now
dismisses the current snackbar when a form opens. Note: the Gradle connected run
UNINSTALLS the app afterwards — the emulator's real-ticket data did not survive it.
Unit tests 698/698; first gate run tripped a known-flaky lint-internal K2 error in
`:app:lintAnalyzeDebugAndroidTest` ("this is a bug in lint", ChecklistE2eTest.kt)
that passed on rerun and on a forced `--rerun`.

## Flight de-duplication (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

ADR-025 follow-up to the trains PNR guard. Hermetic instrumented run only (no live
airline site involved): `connectedDebugAndroidTest` **7/7 PASS** — `FlightsE2eTest`
gained `addSameFlightTwice_isRefusedWithNotice_andKeepsOneCard` (adds `AI 777`, then
`AI 0777` on the same date → "Flight already exists" notice, exactly one `AI 777`
card, no `AI 0777` card); Trains/Trips/Checklist/SeatMap e2e unchanged and green,
`core:ocr` capture harness SKIPPED (`@Ignore`). Full gate green: unit tests 710/710
(+8 `FlightFormViewModelTest` dedupe cases, +2 Robolectric `OfflineFlightRepositoryTest`
`findByFlight` cases, `JourneysDeepLinkTest` flights routing), ktlint + lint clean.
Not exercised on device: the share-sheet intake duplicate landing (covered by
`JourneysDeepLinkTest` + the same `FlightsContent` notice path the e2e drives).

## System back, Archived wording, action-row labels (2026-09-21, emulator Android_16_AOSP_Medium, API 36)

Root cause confirmed on device: Trains/Flights navigate by state, so back skipped
them and popped the shell NavHost to Trips. After wiring `BackHandler`
(`TrainsNavigation.kt` / `FlightsRoute.kt`), verified by `uiautomator` dumps with a
ticket added through the UI: add form + back → Trains list; detail sheet + back →
Trains list; seat map (card icon) + back → Trains list; seat map (detail button) +
back → detail sheet, back again → list; route fetch WebView + back → list; PNR check
WebView + back → list; Flights add form + back → Flights list; Checklist detail + back
→ checklist list (nested NavHost, unchanged); Checklist base list + back → Trips
(shell pop, unchanged); Trips base list + back → launcher (`dumpsys window` focus on
`QuickstepLauncher`) — back is never trapped. Note: a detail sheet that was scrolled
to full height collapses to half height on the first back and dismisses on the
second (Material 3 `ModalBottomSheet` behaviour, unchanged). Filter chips, card badge
and empty states read "Archived" again. Archived ticket → detail: "Unarchive" is a
single `TextView` node of the same height as its "Edit"/"Delete" siblings (53 px,
one line) — `60-unarchive-single-line.png` (blind capture). Full gate green: unit
tests 723/723 (+9 `TrainsNavigationTest`, +4 `FlightsRouteTest`), ktlint + lint clean;
`connectedDebugAndroidTest` **11/11 PASS** (+4 `BackNavigationE2eTest`:
`Espresso.pressBack()` from detail sheet, seat map via card, seat map via detail,
add form), `core:ocr` capture harness SKIPPED (`@Ignore`).

## System back re-verification + share-link form gap (2026-09-22, emulator Android_16_AOSP_Medium, API 36)

Re-walked every back transition with `uiautomator` dumps on a ticket seeded through
the `cleartravel://pnr/1234509876` share link (train 12951 filled in, route fetched
once, then airplane mode so the WebView fetch screens stay up): form → list; detail →
list; seat map (card) → list; seat map (detail) → detail → list; route fetch (card) →
list; route fetch from seat map → seat map → list; route fetch from route page → route
page → list; offline route page (detail) → detail → list; PNR check (card and detail)
→ list; edit form → list; Flights add form → Flights list; Flights status check (offline
error page) → Flights list; Checklist base → Trips (shell pop); Menu → Settings → Menu;
Trips new-trip form → Trips list; Trips base → launcher. Found and fixed one gap the
first pass missed: the add form rendered by the **external entry** (PNR share link,
share-sheet intake — `TrainsExternalEntry` / `FlightsExternalEntry`) sits over the
shell outside its NavHost, so system back finished the activity to the launcher while
its own Back/Cancel landed on Journeys. Both now own a `BackHandler` mirroring Cancel;
verified on device (share-link form + back → Journeys/Trains, nothing saved) and by the
new `pnrShareLinkForm_back_cancelsToTrainsList` e2e, which fails without the fix.
Archived filter → detail: "Unarchive" is still a single 53 px `TextView` beside
"Edit"/"Delete" in both the train and flight sheets (`61-unarchive-single-line-recheck.png`,
blind capture). Full gate green: unit tests 723/723, ktlint + lint clean;
`connectedDebugAndroidTest` **12/12 PASS**, `core:ocr` capture harness SKIPPED (`@Ignore`).

## Google flight-status fallback (2026-09-22, emulator Android_16_AOSP_Medium, API 36, ADR-026)

Flight added manually: **6E 2001, 2026-09-22** (IndiGo — no airline rule file), no
route/times. "Save & check status" → the new `google-flights` rule ran in the visible
WebView (banner "Looking up the flight on Google…"). Google served the **real rich
card** to the emulator's WebView — **no bot wall, no consent wall** (first anonymous
load; UA is the stock Android WebView).

- First run (`62-google-fallback-first-run.png`): rule extraction succeeded but the
  pure parser returned null → the D2 banner ("Google didn't show a flight status
  card…", Retry / Close) with the live card still readable underneath — the
  never-worse-than-today path worked as designed. Root cause from the dumped
  `outerHTML` (captured via a temporary log dump, removed before commit): the live
  DOM differs from the Playwright recon — all four `role=tabpanel`s are EMPTY and the
  day's card is rendered in a sibling async container; time tokens are `8:20am`
  (no space); header/caption read `Arrived`; the recon's `data-maindata`
  `flight_status` blob is absent. That dump is now the fixture
  `live-landed-6e2001.html` (feature:flights + core:scrape).
- Second run after the parser fix (`63-google-fallback-status-updated.png`): "Status
  updated" within ~4 s of tapping Check status; the detail sheet
  (`64-google-fallback-detail-sheet.png`, uiautomator dump) shows **Landed**,
  Departure Scheduled 08:20 / Estimated 08:09, Terminal — / Gate —, Arrival
  Scheduled 10:15 / Estimated 09:37, Terminal 2 / Gate —, "Checked 22 Sep, 09:47",
  "Last check (22 Sep, 09:47): status updated". Matches the card (8:20am struck →
  departed 8:09am; 10:15am struck → arrived 9:37am, T2, Cirium).
- Not observed live: consent wall, bot wall, a delayed/cancelled card (synthetic
  fixtures cover those states). Note for future runs: typing into the add form while
  the host machine was at load ~14 produced an emulator ANR ("Waited 22 s for
  FocusEvent") unrelated to the app; retyping at low load worked.

Blind screenshots used: 3 of ≤3. Emulator left running with the app installed and
the 6E 2001 journey (status Landed) present.

## 2026-09-22 — Cross-tab integration sanity (ADR-028, emulator-5554, Android 16 AOSP)

Quick sanity by the implementing stage — the full validation pass is owned by the
next stage. Hermetic e2e `CrossTabE2eTest` ran green on the emulator (1/1).
Manual round trip (adb taps, fresh install, `pm clear`):

- Trips → new trip "Goa" → FAB → Commute → "Link a journey":
  `65-crosstab-picker-add-rows.png` — the picker sheet shows "Add a new train
  ticket" / "Add a new flight" rows above "No existing journeys to pick from yet".
- "Add a new train ticket" → the shell lands on Journeys/Trains with the add-options
  sheet already open (Manual entry / Paste SMS / Import) → Manual entry → PNR
  8812345678, train 12627, SBC → NDLS → Save ticket.
- `66-crosstab-form-restored-linked.png` — back on the Trips tab with the SAME item
  form restored (Commute, Day 1), "Linked journey: Train 12627", From/To prefilled
  SBC / NDLS, mode switched to Train. Confirms the nested NavHost + form ViewModel
  survive the tab switch (no draft persistence needed).
- Save leg → Journeys → ticket card → `67-crosstab-part-of-row.png` — detail sheet
  shows "Part of — Goa · Day 1 / Open the trip in the Trips tab".

Still to verify on device (next stage): flights variant of the add hand-off (manual
+ boarding-pass import + "Save & check status" reporting after the check closes),
cancel paths (sheet dismissed, form Cancel, system back, manual tab tap → form
stays unlinked, no stale add sheet on re-entering Journeys), refused-duplicate PNR
in pick mode linking the EXISTING ticket, "Open in Journeys" from the leg sheet,
"Part of" tap landing on the trip detail, dark theme rendering of the new rows.

## Final validation of the wave (2026-09-22, emulator Android_16_AOSP_Medium, API 36)

Closing pass over the pending fixes, the Documents tab (ADR-027), the Google
flight-status fallback (ADR-026) and the cross-tab integration (ADR-028). Every
observation below is a `uiautomator` dump (text only); the five blind captures
(`68`–`72`, downscaled, never viewed) are references only. The emulator carried the
prior stage's install of `266f607` (built 10:25, installed 10:27); the connected suite
reinstalled the same source at the end.

| # | Check | Verdict | Evidence |
|---|---|---|---|
| 1 | Back: detail sheet → list; seat map → list; route page → list; add form (+typed PNR) → list, nothing saved; Journeys base → Trips (shell pop) → trip list → launcher (`QuickstepLauncher` focus); relaunch fine | PASS | dumps |
| 1 | "Archived" wording: filter chip, card badge, empty state ("No archived journeys"); "Unarchive" = one 53 px `TextView` beside Edit/Delete ([451,2164][629,2217]) | PASS | `68-final-unarchive-single-line.png` |
| 2 | Documents = 5th tab (Trips · Journeys · Checklist · **Documents** · Menu); `EmptyState` on first open | PASS | dump |
| 2 | Add: pushed `passport-scan.png` (178 B) + `pan-card.pdf` (341 B) to `/sdcard/Download`; system picker lists both; PNG → dialog pre-selects **Passport** with the name prefilled → Save → "Document added"; PDF → **ID card** preset, name retyped to **PAN card** → Save; list newest-first (PAN card / ID card, Passport / Passport) | PASS | `69-final-documents-list.png` |
| 2 | Viewer: PAN card → full-screen PDF page (`Stored document`, title "PAN card", Close) → back; Passport → image viewer → back to list | PASS | dumps |
| 2 | Rename: overflow → Edit details → "Passport old" → "Document updated". Delete: overflow → Delete → dialog "Delete document? “PAN card” and its stored file will be removed from this device." → Delete → "“PAN card” deleted"; `files/documents/` holds only the remaining `.png` | PASS | dumps + `run-as ls` |
| 2 | Backup: Menu → Backup & Restore → Export → system Save (`cleartravel-backup-20260922-1048.zip`) → "Last backup: Sep 22, 2026, 10:48 AM (8.2 kB)". Pulled zip: `entities/travel_documents.json` + `attachments/<docId>` (178 B) present; manifest `travel_documents: 2` (export ran before the delete), trips 2, train_tickets 1, train_coaches 20 | PASS | zip listing |
| 3 | Google fallback: 6E 2001 / 2026-09-22 re-added (prior stage's `pm clear` had wiped it) → "Save & check status" → **"Status updated" within 4 s** → sheet: **Landed**, Departure Scheduled 08:20 / Estimated 08:09, Terminal — / Gate —, Arrival Scheduled 10:15 / Estimated 09:41, Terminal 2, "Checked 22 Sep, 10:50", "Last check (22 Sep, 10:50): status updated". Extracted to the sheet, matching ADR-026 (arrival estimate moved 09:37 → 09:41 vs the earlier run — live data) | PASS | `70-final-google-6e2001-card.png` |
| 4 | Cancel paths: (a) "Add a new train ticket" → Journeys add sheet → system back → Trips form restored, "Link a journey" still unlinked; (b) → Manual entry → back from the train form → form restored unlinked; (c) manual **Trips** tab tap while the pick form was open → form unlinked, re-entering Journeys shows the LIST (no stale add sheet/form), a ticket then saved normally there (PNR 5555566666) did NOT link the waiting form | PASS | dumps |
| 4 | Hand-off: trip "Kerala" → Commute leg → picker rows "Add a new train ticket" / "Add a new flight" above the existing Trains/Flights → add train → Journeys/Trains add sheet already open → Manual entry → PNR 9876501234, train 12345 → Save ticket → **auto-return to Trips with the same form**: "Linked journey: Train 12345" (+ Remove link icon). From/To stay blank because the minimal ticket has none — the commute leg requires them (`ITEM_ROUTE_REQUIRED` snackbar on Save), filled SBC → ERS → Save → leg on the Day 1 timeline | PASS | `71-final-crosstab-form-linked.png` |
| 4 | Leg sheet → "Open in Journeys" → the 9876501234 ticket sheet opens on Journeys/Trains with **"Part of — Kerala · Day 1 / Open the trip in the Trips tab"** → tap → Trips tab selected, Kerala trip detail with the SBC → ERS leg | PASS | `72-final-crosstab-part-of-kerala.png` |
| 4+ | Flights variant + refused duplicate: new Commute leg → "Add a new flight" → Journeys/Flights add sheet open → Enter manually 6E 2001 / 2026-09-22 (already exists) → Save → back on the Trips form linked to the **existing** "Flight 6E 2001", planned time prefilled **08:20** from `schedDep`; Flights list still shows exactly one 6E 2001 | PASS | dumps |
| 5 | `ktlintCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL (tree unchanged since the prior gate, so style/lint were up-to-date); unit tests forced with `testDebugUnitTest --rerun`: **812/812**, 0 failures (app 32, core:data 87, core:database 37, core:designsystem 7, core:google 58, core:model 10, core:notifications 14, core:ocr 49, core:scrape 77, feature:checklist 21, documents 18, flights 123, itinerary 52, menu 49, trains 178) | PASS | `final-validate*.log` (git-ignored) |
| 5 | `connectedDebugAndroidTest`: **14/14 PASS** — BackNavigationE2eTest 5, ChecklistE2eTest 1, CrossTabE2eTest 1, DocumentsE2eTest 1, FlightsE2eTest 2, SeatMapE2eTest 1, TrainsE2eTest 2, TripsE2eTest 1; `core:ocr` OcrCaptureHarnessTest SKIPPED (`@Ignore`). No flakiness, no test edits | PASS | `connected.log` |

Notes: the "Import boarding pass" flights path and the dark theme of the new rows
were not re-walked (the former needs a real BCBP file and was validated in ADR-017's
run; colour cannot be judged from dumps). A modal add sheet covers the bottom bar,
so the "manual tab tap" case only arises from the non-modal form — that is the path
tested. No code fixes were needed in this pass. Emulator shut down at the end.

## Google flight-status fallback — real-flight validation (2026-09-22 11:15–11:50 IST, emulator Android_16_AOSP_Medium, API 36, ADR-026 v2)

Five user-supplied flights, all dated **2026-09-22**, run through the shipped flow
(`installDebug` of `main` 59df4e6, fresh `pm clear`): add the flight → "Save & check
status" → uiautomator dump of the detail sheet. Every mismatch was diagnosed by pulling
the fallback WebView's `outerHTML` over the devtools socket
(`webview_devtools_remote_<pid>`, `Runtime.evaluate`) and by navigating that same
WebView (`Page.navigate`) to candidate queries; the resulting DOMs are the new `live-*`
fixtures (untouched, trimmed to the panel container). No bot wall / consent wall on
any of the ~14 page loads (India egress, stock WebView UA).

### Per-vector verdict (final build)

| Vector | Expected (user) | Live Google card (DOM) | Extracted on the sheet | Verdict |
|---|---|---|---|---|
| **SG 128** IXL→DEL | **Cancelled** on 22 Sept; bare `SG128` search shows 23 Sept | Bare query: tab **Wed 23 Sept** pre-selected, *Scheduled* card 6:45→8:10 (T1) — 22 Sept card absent. Dated query: tab **Tue 22 Sept**, header **Cancelled**, captions `Scheduled departure/arrival` with NO time value, `<del>6:45 am</del>` / `<del>8:10 am</del>`, arrival T1D; `data-maindata` = `ARRIVED_DELAYED, CANCELED, SCHEDULED_STATUS, SCHEDULED_STATUS` | Before fix: D2 banner "Google didn't show a flight status card for this flight and date" (the date guard correctly refused the 23 Sept card). After fix: **Cancelled**, Dep Scheduled 06:45 / Est —, Arr Scheduled 08:10 / Est —, Arr Terminal **1D**, "Last check: status updated" | **PASS** (`73-…status-updated.png`, `74-…cancelled-sheet.png`) |
| **6E 541** BLR→IXE | Runway delay | Bare query again pre-selected **23 Sept** (Scheduled). Dated: header **Arrived late**, Departed **8:22 am** (`<del>7:15 am</del>`), Arrived 9:18 am (`<del>8:15 am</del>`), T1 gate **28** | **Landed**, Dep Sched 07:15 / Est **08:22**, T1 Gate 28, Arr Sched 08:15 / Est 09:18 | **PASS** — the 67-min departure delay is on the sheet; the flight had landed by check time (11:32) so LANDED is the right terminal status |
| **6E 6353** BLR→LKO | Early departure | Header **Arrived**, Departed **7:03 am** (`<del>7:10 am</del>`), Arrived 9:27 am (`<del>9:50 am</del>`), T1 gate 22 → T3 | **Landed**, Dep Sched 07:10 / Est **07:03** (7 min early, sign kept, not read as a delay), Gate 22, Arr Sched 09:50 / Est 09:27, Arr T3 | **PASS** |
| **6E 6144** BLR→TRV | Delay | Header **Departing late** (new wording), Estimated departure **11:40 am** (`<del>10:30 am</del>`), Estimated arrival 1:00 pm (`<del>11:50 am</del>`), T1 gate 25 → T1 | **Delayed**, Dep Sched 10:30 / Est 11:40, T1 Gate 25, Arr Sched 11:50 / Est 13:00, Arr T1 | **PASS** (`75-…delayed-sheet.png`) |
| **6E 9468** | Multiple matches | ONE date tab, **two cards**: AUH→BLR header **Diverted** (Departed 12:45 am `<del>12:30 am</del>`, Landed 6:26 am `<del>5:40 am</del>`, T A) and BLR→IXE header **Arrived late** (Departed 8:30 am `<del>7:35 am</del>`, Arrived 9:29 am `<del>8:30 am</del>`, T2, collapsed) | Saved WITHOUT a route: first leg applied — Landed 00:30/00:45 → 05:40/06:26, T A (documented fallback). Edited to **BLR → IXE** + re-check: **Landed**, Dep Sched **07:35** / Est 08:30, **T2**, Arr Sched 08:30 / Est 09:29 | **PASS** — airports disambiguate the leg (`76-…multicard-blr-ixe-sheet.png`) |

`77-google-validate-flights-list.png`: the Flights list after the run (SG 128
Cancelled, 6E 6353 / 6E 541 / 6E 9468 Landed with actual times, 6E 6144 Delayed
below the fold).

### What was wrong and what shipped

- **Wrong-day selection (root cause of the SG 128 miss).** Once the day's departure
  time has passed, a bare `<IATA> <no> flight status` query makes Google pre-select
  the NEXT operating day; the parser's date guard rightly refused that card, so the
  user saw the "no card" banner instead of *Cancelled*. Fix: the rule URL now carries
  `+{date}` and the feature renders it as `22+September+2026`; verified on all five
  vectors that the spelled-out date selects the journey's tab (`SG 128 flight status
  22 September`, `… September 22 2026` and `… 2026-09-22` all worked; the word form
  shipped).
- **Multi-card pages.** Card choice is now date → departure airport → arrival airport
  → saved scheduled-departure time → first (6E 9468).
- **Hidden duplicate card.** The live panel repeats every card inside an "About this
  result" `role=dialog`; skipped, so `parse` yields one `Card` per real card.
- **Cancelled card shape.** `Scheduled departure` caption with no time value; the
  `<del>` original is now the schedule (06:45 / 08:10 landed on the sheet).
- **Vocabulary.** `Departing late` → Delayed, `Arrived late` → Landed, `Diverted` falls
  through to the captions (→ Landed here). No `DIVERTED` status exists in the model —
  follow-up ADR if wanted.
- **Re-check bug (found on device, unrelated to Google).** Tapping "Check status" a
  second time on the same flight within one process replayed the previous outcome
  (host-scoped ViewModel + same-id guard) — the sheet kept the old "Checked 11:36"
  after the route edit. Fixed (`start` only short-circuits while that flight's check is
  running); verified: 11:44 → 11:45 re-check produced a fresh timestamp and "no changes
  found".

Tests: `GoogleFlightsExtractorTest` 14 → 22, `GoogleFlightsRuleTest` 7 → 8,
`FlightStatusFallbacksTest` 5 → 6, `FlightStatusCheckViewModelTest` 16 → 17; seven new
live fixtures (six in `feature:flights`, one mirrored in `core:scrape`). Blind
screenshots used: 5 of ≤5. Emulator left running with the app installed and the five
journeys present.

## Security wave — final on-device validation + full regression (2026-09-22 13:15–13:45 IST, emulator Android_16_AOSP_Medium, API 36, ADR-029/030/031)

Fresh install of `d1abc94` (`pm uninstall` → `adb install` of the gate's APK) walked
through the app lock, the encrypted round trip, the upgraded viewer, the Trips/intake
fixes, backup v2 and one live Google vector, then the full gate and the connected
suite. Every observation is a `uiautomator` dump (text only; `/sdcard/ui.xml` removed
before each dump after a stale file from a previous stage produced a phantom
screen); the twelve blind captures `78`–`90` (downscaled, never viewed) are references
only. The one image "look" is a numeric pixel probe of the raw capture, not a viewing.
Vault password on the emulator for this run: `Validate-Pass-2026`.

| # | Check | Verdict | Evidence |
|---|---|---|---|
| 1 | Fresh install: after the POST_NOTIFICATIONS system prompt the FIRST app screen is **"Protect your travel data"** — two password fields, live strength hint (`At least 8 characters` → `Strong`), red card **"If you forget this password, your data is lost"** + "There is no reset, no recovery e-mail … Save it in a password manager.", button disabled until both fields are filled; no tab bar, no content behind it. Create → Trips tab (5 tabs, empty) | PASS | `78-applock-setup-fresh-install.png`, dumps |
| 1 | Relaunch (`force-stop` → start): **"ClearTravel is locked"** unlock screen; wrong password → inline **"That password is not correct."**, still locked; correct password → Trips. `files/security/vault.json` (880 B) is the only key material on disk | PASS | `79-applock-unlock-screen.png`, dumps |
| 1 | **Biometrics (device-tested)**: enrolled a fingerprint on the AVD (`locksettings set-pin` → Settings enrolment → `adb emu finger touch 1` ×12 → "Fingerprint added"); Settings → Security → *Unlock with biometrics* → BiometricPrompt "Confirm to enable biometric unlock" → touch → toggle checked. Cold start → BiometricPrompt **"Unlock ClearTravel"** auto-shown → `finger touch 1` → Trips; wrong finger (`touch 2`) leaves the prompt up; **Use password** → password unlock screen with an "Unlock with biometrics" button → password unlocks. **Lock timing** "Immediately" → Home → relaunch → prompt again → finger → app | PASS | `81-applock-biometric-prompt.png`, dumps |
| 2 | Added train 12951 / PNR 4412998877 (manual) and a document (pushed 178 B `visa-scan.png`, type Visa). **DB is not plaintext SQLite**: `databases/cleartravel.db` header bytes `3c e1 b0 7c 8c 7f 2b fd b0 75 2b 46 a3 63 2f 16` (≠ `SQLite format 3\0`, SQLCipher salt); `strings` over db+WAL (284 288 B) finds **0** hits for `4412998877`/`Rajdhani`/`Asha`/`CREATE TABLE`/`train_tickets`. **Stored document is not the original**: `files/documents/<uuid>.png` = 211 B, header `43 54 45 46 01 00 01 00 …` (`CTEF` v1) vs source `89 50 4E 47 …` (`.PNG`), `cmp` → differ at byte 1 | PASS | `run-as` byte dumps |
| 2 | Viewer decrypts transparently: Visa → `Stored document` image node 1080×1080; pixel probe of the raw capture at the centre = **(200, 30, 30)** = the PNG's fill colour. **Share** → system sheet "Sharing image" (Clear SMS / ClearTravel / Messaging / Bluetooth / Print). **Save a copy** → SAF `Visa.png` → Downloads; pulled file is **byte-identical to the source** (`cmp` clean, `.PNG` header) | PASS | `82-viewer-encrypted-visa-rendered.png`, dumps, `cmp` |
| 3 | Viewer toolbar: `Close`, `Rotate 90°`, `Share file`, `Save a copy` `ExplainableIcon`s present; no page bar for an image; rotate tap keeps the same layout node (square source — rotation is screenshot-only evidence). Pinch-zoom not dump-verifiable (unit-covered, `DocumentViewerStateTest`) | PASS | `83-viewer-rotated-90.png`, dumps |
| 4 | Trips auto-sort: "Louvre 14:00" saved first, then "Cafe de Flore 09:00" → day order **Cafe de Flore 09:00, Louvre 14:00**; each row has a **`Reorder`** drag handle, no *Move up/down* nodes | PASS | `84-trips-auto-sorted-drag-handles.png`, dumps |
| 4 | Linked leg time: Commute BLR→CDG → *Link a journey* → **Add a new flight** → Journeys add sheet → Enter manually 6E 2001 / 2026-09-22 / dep 08:20 → Save → back on the Trips form **linked "Flight 6E 2001", Planned time prefilled 08:20** → saved leg sorts first (08:20 < 09:00 < 14:00) | PASS | `85-trips-linked-leg-time-from-journey.png` |
| 4 | Journeys never auto-opens a sheet: Trips ↔ Journeys ×3 → list only; leg sheet → **Open in Journeys** → 6E 2001 sheet opens (deep link) → close → Trips ↔ Journeys ×2 → no sheet; Trains ↔ Flights toggle → no sheet | PASS | dumps |
| 5 | `ACTION_SEND text/plain https://maps.google.com/?q=48.8584,2.2945` → **"Add place from Google Maps"** dialog: "Shared place · 48.85840, 2.29450", *Create new trip* / *Add to existing trip* (Paris Weekend preselected) → **existing** → item "Shared place" on Day 1, sheet: Coordinates 48.85840, 2.29450, Link = the URL. `/maps/place/Eiffel+Tower/@48.8583701,2.2922926,…!3d48.8583701!4d2.2944813` → dialog **"Eiffel Tower · 48.85837, 2.29448"** (pin coords, not the viewport) → **Create new trip** (name left blank) → new trip **"Eiffel Tower"** with the "Eiffel Tower" place item, coords + full link on the sheet | PASS | `86-maps-link-intake-dialog.png`, `87-maps-place-eiffel-new-trip-sheet.png` |
| 6 | Export → SAF `cleartravel-backup-20260922-1334.zip` → "Last backup: Sep 22, 2026, 1:34 PM (6.4 kB)". Pulled file (6 352 B): header `43 54 45 42 01 00 03 34 50 …` (`CTEB` v1, 210 000 iterations), `unzip -l` → "End-of-central-directory signature not found", `zipfile.is_zipfile` → False, `strings` → 0 hits for any record. Import the same file (own salt, **no password prompt**) → preview "Created … · 56 records … Trips: 2 · Journeys: 2 · Checklists: 0 · Attachments: 0" → Import → snackbar **"Import finished: 0 added, 0 updated, 56 unchanged"**; Documents tab still shows exactly one Visa | PASS | `88-backup-v2-import-merge-summary.png`, byte dump |
| 7 | Google spot-check (6E 2001 / 22 Sep, the fresh flight): first check hit a Google **bot wall** ("unusual traffic", IP 49.207.62.35 — the egress is rate-limited after the prior stage's ~14 loads) → the app showed the failure banner with **Retry / Close** over the page; Retry after ~60 s → **"Status updated"** → sheet **Landed**, Dep Scheduled 08:20 / Estimated **08:09**, Arr Scheduled 10:15 / Estimated **09:41**, Terminal **2**, "Checked 22 Sep, 13:37" — identical to the 10:50 capture of the previous stage. SG 128 / 6E 541 / 6353 / 6144 / 9468 were 22-Sept vectors validated at 11:15–11:50 (captures `73`–`77`); not re-run to avoid re-tripping the wall | PASS | `89-google-botwall-banner-retry.png`, `90-google-6e2001-live-landed-sheet.png` |
| 8 | `ktlintCheck lintDebug testDebugUnitTest --rerun-tasks assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL, 1 202 tasks executed; unit **949/949**, 0 failures, 0 skipped (app 48, core:data 99, core:database 37, core:designsystem 27, core:google 61, core:model 10, core:notifications 14, core:ocr 49, core:scrape 78, core:security 27, feature:applock 7, checklist 21, documents 18, flights 132, itinerary 88, menu 55, trains 178) | PASS | `validate2.log` (git-ignored) |
| 8 | `connectedDebugAndroidTest` → **15/15 PASS**: AppLockSetupE2eTest 1, BackNavigationE2eTest 5, ChecklistE2eTest 1, CrossTabE2eTest 1, DocumentsE2eTest 1, FlightsE2eTest 2, SeatMapE2eTest 1, TrainsE2eTest 2, TripsE2eTest 1; `core:ocr` OcrCaptureHarnessTest SKIPPED (`@Ignore`). No flakiness, no test edits, `TestSecurityModule` untouched | PASS | `connected-validate2.log` |

Notes and open items (no code changes were needed in this pass):

- The POST_NOTIFICATIONS system prompt is requested at activity start, i.e. it shows
  over the password-setup screen on first run. It is a system dialog, not app content,
  but asking after the first unlock would read better — follow-up, not a defect.
- The itinerary item **sheet** labels a linked journey by id prefix ("Flight d72275b9")
  while the **form** shows the journey label ("Flight 6E 2001"); pre-existing
  (`ItineraryItemSheet` uses `journeyId.take(8)`), cosmetic, not part of this wave.
- Backup preview says "Attachments: 0" although one document was bundled (the record
  count and the round trip are right; the counter reads only booking-confirmation
  attachments). Cosmetic follow-up.
- Biometric enrolment left the AVD with a lock-screen PIN; cleared with
  `locksettings clear --old 1234` before shutdown so future e2e runs boot unlocked.
- Emulator shut down at the end of the run.

## Onboarding wizard — on-device validation + full regression (2026-09-22 13:55–14:40 IST, emulator Android_16_AOSP_Medium, API 36, ADR-032)

Goal: prove the four-step first-run wizard end to end, INCLUDING a restore of a
backup made by a *different install with a different password* — the case the
step-4 copy is written for. Method: dumps only (`uiautomator dump` → text; the
`/sdcard/ui.xml` removed before each dump), five blind captures `91`–`95`
(downscaled to 540 px, never viewed). No Play services on the AVD, no
`GOOGLE_WEB_CLIENT_ID` in the build, no biometrics enrolled — so the Google-linked
and fingerprint branches are covered by the unit tests (fakes) and the Settings
biometric path validated on 2026-09-22 13:15 (capture `81`), and the device run
exercises the offline branches and the disabled states.

Set-up of the foreign backup (the "previous install"): `pm uninstall` → install the
pre-wizard build (`0726934`) → password **`OldDevicePass1`** → trip
"ForeignBackupTrip / Lisbon" → Menu → Backup & Restore → Export →
`/sdcard/Download/cleartravel-backup-20260922-1414.zip` (4 343 B, header
`43 54 45 42 01 00 03 34 50` = CTEB v1, 210 000 iterations). Then `pm uninstall` →
install the wizard build.

| # | Check | Verdict | Evidence |
|---|---|---|---|
| 1 | **Step 1 of 4** is the first screen: "Protect your travel data", *Password* + *Confirm password* fields, red "If you forget this password, your data is lost" card, **"Unlock with fingerprint"** row with the hint *"No fingerprint or face is set up on this device. You can turn this on later in Settings → Security."*, its Switch `checkable=true checked=false enabled=false`; no tab bar. `NewDevicePass2` / `NewDevicePassX` → Create → inline **"The two passwords don't match."**, vault still not set up | PASS | `91-onboarding-step1-password-toggle.png`, dumps |
| 2 | Corrected confirmation → Create → **Step 2 of 4** "Back up to Google Drive?" with *Connect Google account for backups*, the line *"Google features are not configured in this build, so connecting is unavailable. Everything else works offline."* and *Use offline*. Tapping Connect does nothing (disabled; the Compose semantics assertion lives in `AppLockSetupE2eTest`) | PASS | `92-onboarding-step2-google.png`, dumps |
| 3 | **Resume after death**: `force-stop` on step 2 → relaunch → **"ClearTravel is locked"** (the password is asked to *open* the store, never to be *created* again) → `NewDevicePass2` → lands on **Step 2 of 4** directly | PASS | dumps |
| 4 | *Use offline* → **Step 3 of 4** "Restore a backup or start fresh?" — *Restore from a file* card with *Choose backup file*, **no Drive card** (offline), *Start fresh*. Choose → SAF Downloads → the 14:14 backup | PASS | `93-onboarding-step3-restore.png`, dumps |
| 5 | **Step 4 of 4** "Backup password": *"This may differ from the password you just created: a backup is sealed with the password of the ClearTravel install that made it. Use that one here."*, `Backup: cleartravel-backup-20260922-1414.zip`, one `Password` field, *Restore backup*, *Choose a different backup*. Typed the NEW app password → inline **"That password does not open this backup. Try again."**, still on step 4 (nothing written). Typed **`OldDevicePass1`** → Restore → **Trips tab with "ForeignBackupTrip / Lisbon"** — the foreign-password restore merged into the new install's store (vault salt ≠ backup salt, key derived from the typed password, ADR-031 §4). Repeated on the final build after the display-name fix: same outcome | PASS | `94-onboarding-step4-backup-password.png`, `95-onboarding-restored-foreign-backup.png`, dumps |
| 6 | Relaunch after the wizard: unlock screen → password → Trips with the restored trip, **no wizard** (flag cleared) | PASS | dumps |
| 7 | **Settings parity** (dumps): Settings → Security: *Change password* button, *Unlock with biometrics* toggle (+ "No strong biometrics are set up on this device."), *Lock when in the background for* radios; Settings → Google account: not-configured explanation (the *Connect* button and the three toggles render when a client id is present — `GoogleAccountSection`); Menu → Backup & Restore: *Export backup*, *Import backup* (SAF), Drive card ("Link a Google account in Settings to keep backups in Drive…" when unlinked; list + restore when linked). All four operations reachable outside the wizard | PASS | dumps |
| 8 | `pm clear` → **start-fresh path**: Step 1 (`FreshStartPass3`) → Step 2 → *Use offline* → Step 3 → *Start fresh* → Trips **"No trips yet"** (empty app) | PASS | dumps |
| 9 | Bug found & fixed in the run: step 4 showed `Backup: 23` — a SAF `content://` URI's last segment is an opaque document id. `OnboardingViewModel` now resolves `OpenableColumns.DISPLAY_NAME` (falls back to the last segment); the in-place upgrade `adb install -r` over the mid-wizard install resumed at the unlock screen → step 2 as designed | FIXED | dump after fix: `Backup: cleartravel-backup-20260922-1414.zip` |
| 10 | `ktlintCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL (1 202 tasks); unit **967/967**, 0 failures (was 949: feature:applock 7 → 24, core:data 99 → 100) | PASS | `onboarding.log` (git-ignored) |
| 11 | `connectedDebugAndroidTest` → **15/15 PASS** (AppLockSetupE2eTest now walks step 1 short → mismatch → valid, step 2 connect-disabled → offline, step 3 no-Drive → start fresh → Trips; the other 14 unchanged and green with the flag defaulting to false in `FakeSettingsRepository`); `core:ocr` harness SKIPPED (`@Ignore`) | PASS | `connected-onboarding.log` |

Notes:

- Not device-tested here (covered by unit tests with fakes): Google linking (no Play
  services on the AVD, no client id), Drive listing found/none, the consent-sheet
  round trip, and the step-1 fingerprint enrolment (the Settings enrolment path was
  device-tested in the security wave; the wizard reuses `BiometricKeyWrapper` +
  `KeyVault.enableBiometric` and its wiring is asserted in `AppLockViewModelTest`).
- `adb shell input text` mangles `-`; the run used dash-free passwords.
- The POST_NOTIFICATIONS prompt was pre-granted with `pm grant` for the walk; it
  still fires over step 1 on a real first run (pre-existing follow-up).
- Emulator shut down at the end of the run (`emu kill`, `adb devices` empty).
