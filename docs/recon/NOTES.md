# ClearTravel Scrape-Rule Recon Notes

Captured 2026-09-20 via live browser (Playwright/Chromium), read-only public browsing. No logins,
no bookings/payments submitted. Flight-status queries were submitted where the site accepts a
flight number (a status lookup, not a booking/charge action); PNR-based sites were NOT submitted
since no valid PNR was available (per task instructions). Per-site trimmed HTML lives alongside
this file: `indianrail-pnr.html`, `indigo.html`, `airindia.html`, `akasa.html`.

**Top-line finding that should drive the architecture decision (ADR):** no two of the four sites
recon'd use the same UI framework or date-widget pattern, one has a live captcha, one has no
scrapeable selectors at all (SpiceJet — atomic/hashed CSS, no semantic hooks), and one has no
flight-number search mode at all (IndiGo — PNR only). This confirms AGENTS.md's existing design
call: `TrainStatusProvider`/`FlightStatusProvider` MUST be Hilt-bound interfaces with a **WebView
scrape provider as default and mock/manual always available** — a purely headless/background
scrape strategy will not reliably cover this rule set. Several sites need a **user-visible**
WebView specifically (captcha-solving, or DOM too unstable to trust blind automation).

---

## 1. Indian Railways PNR Enquiry

- **Final URL:** `https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html?locale=en` (no redirect)
- **Not submitted** (no valid PNR available). Flow fully reconstructed by reading the live
  `pnrEnquiryJS.js?version=18` source and probing `GET /enquiry/CaptchaConfig` directly.

**Input selectors:**
- PNR field: `#inputPnrNo` (`input[type=text]`, `maxlength=10`, numeric-only via `onkeypress` filter)
- Captcha answer field: `#inputCaptcha` (only present in DOM once the captcha modal is injected)

**Submit selectors:**
- Outer "Submit" (opens captcha modal): `#modal1`
- Modal's real submit (fires the actual query): `#submitPnrNo`

**Captcha:** **CONFIRMED LIVE at recon time.** `GET https://www.indianrail.gov.in/enquiry/CaptchaConfig`
returned `"1"` (non-`"0"` = active). This is a server-side toggle checked via AJAX on every page
load (`loadunloadData()` in the JS) — it can be on or off at any given moment, so a rule **cannot
assume captcha is absent**. When active, a hand-drawn CAPTCHA image (`GET /enquiry/captchaDraw.png`,
regenerated per request, not OCR-friendly) is shown in `#CaptchaImgID` inside modal `#myModal`.

**Auto-submit safe?** **No — not when captcha is active** (current live state). Requires a
user-visible WebView so a human can read and answer the captcha.

**Backend call (reconstructed, not directly observed with a real PNR):**
```
POST https://www.indianrail.gov.in/enquiry/CommonCaptcha
Body (form-encoded): inputCaptcha=<answer>, inputPnrNo=<10-digit PNR>, inputPage=PNR, language=en
```

**Response JSON fields (from JS source, field names only — not a captured real payload):**
`flag` ('NO' = wrong captcha), `errorMessage`, `generatedTimeStamp{day,month,year,hour,minute,second}`,
`trainNumber`, `chartStatus` ("Chart Prepared"/"Chart Not Prepared"), `isWL` (Y/N), `journeyClass`,
`passengerList[]` (per-passenger coach/berth; exact sub-field names unconfirmed — needs a real PNR
to observe).

**readySignal:** `#pnrOutputDiv` becomes visible / `#result` loses class `hidden` (and `#inputPnrNoDiv`
is hidden) — this is a **JS-driven show/hide of static markup**, not new DOM insertion, so a
`waitForSelector` on visibility (not mere presence) is required.

**Quirks:**
- Heavy Google/DoubleClick ad iframes around the form — scrape rule must scope strictly to
  `#inputPnrNo` / `#firstCondition` / `#myModal`, ignore iframe subtrees.
- Same captcha modal (`#myModal`/`#submitPnrNo`) is shared between the PNR flow and a separate
  "Train Running Status by train number" flow, disambiguated only by hidden `#flagLabel` value
  (`'PNR'` vs `'TRAIN'`) — a rule must set/check this correctly.

---

## 2. IndiGo Flight Status

- **Final URL:** `https://www.goindigo.in/check-flight-status.html` (no redirect)
- **Architecture surprise:** the page has **no flight-number or route search at all** — it is
  **PNR + travel date only.** The task brief's assumption ("try flight 6E 2001") does not match
  reality; ClearTravel's IndiGo provider must be keyed on PNR, and should declare
  `{pnr, date}` as its required-input contract, falling back to manual/mock when the user has no
  PNR captured (e.g. checking someone else's flight by number only).

**Input selectors:**
- PNR field: `input[name="png_search"]` (placeholder "Search by PNR", inside `form.fs-form`)
- Date: a styled button (`button.fs-select-dropdown__control__button`), opens a picker overlay —
  not a native date input.

**Submit selector:** `button.form-submit[type=submit]` — **stays `disabled` even after a
syntactically-plausible 10-digit PNR is typed** with real keyboard events in this recon session;
the enable condition was not fully identified (possibly requires the date widget to be re-confirmed
even when already showing "Today", or a stricter client PNR format check). Flag for follow-up with
a real PNR or interactive debugging.

**Captcha:** none observed.

**readySignal:** untested — page has an (empty at rest) `aria-label="Flight Status Result"`
container; presumably populates after a successful search, not confirmed.

**Quirks:**
- Cookie-consent dialog ("We respect your privacy") present on load; did not block this recon
  session's programmatic interaction, but production WebView rule should defensively dismiss it
  (button text "Accept All") in case some devices block clicks under it.
- `data-openreplay-obscured="true"` on the PNR input — site runs OpenReplay session recording and
  explicitly masks this field from its own analytics; informational (confirms real-user session
  monitoring / bot-detection posture — favor genuine WebView interaction over synthetic events).

---

## 3. Air India Flight Status

- **Final URL after submit:** `https://www.airindia.com/in/en/manage/flight-status.html?fno=101&on=20260920`
  (query params are the readySignal-adjacent state: `fno`=flight number, `on`=date as `YYYYMMDD`)
- **Successfully submitted and captured a real live result** for AI 101 on 2026-09-20.

**Input selectors:**
- Mode radios (`mat-radio-group-0`): `#mat-radio-3-input` (value `flight_number`, default-checked),
  `#mat-radio-4-input` (value `pnr`), `#mat-radio-5-input` (value `route`) — labelled "Flight number"
  / "PNR" / "Route" respectively.
- Flight number field: `#flight-number-ip-id` (`formcontrolname="flightNo"`, `maxlength=4`,
  numeric suffix only — no separate airline-code field; this endpoint is Air-India-only so "AI" is
  implied, not entered).
- Date: Angular Material `mat-select#mat-select-0` (`formcontrolname="dateOption"`) — **not a free
  date picker**, a fixed dropdown of only 5 discrete dates (today ±2 days) rendered as
  `mat-option#mat-option-0..4` with visible text like `"20 Sep 2026"`. Options render in a
  CDK overlay portal, not inside the form's DOM subtree, when open.

**Submit selector:** `button.form-btn.booking-flight-btn` ("Get Flight Status").

**Captcha:** none observed. **Blocking overlay:** a **OneTrust cookie-consent overlay**
(`#onetrust-consent-sdk`, specifically `.onetrust-pc-dark-filter`) intercepts ALL pointer events
until dismissed — clicks silently fail/timeout otherwise. Must click `#onetrust-accept-btn-handler`
(or remove the banner) before any interaction. This is a hard blocker, not cosmetic.

**readySignal:** presence of one or more `.card.flight-status-card` elements (see below — can be
**more than one card** for a single flight-number+date query).

**Extraction selectors (per `.flight-status-card`):**
| Field | Selector | Notes |
|---|---|---|
| Flight number + airline | `.list-inline-item` (first, contains airline logo `<img>` + text) | e.g. "AI 101" |
| Aircraft type | `.airCraftTypeName` | e.g. "Airbus A350-900" |
| Overall status | `.ontime-check-state` | e.g. "Scheduled" |
| Departure time (on-time) | `.departure .fsm-estimated-time` | |
| Departure/arrival time (delayed/early) | `.fsm-scheduled-time` (original) + `.fsm-estimated-time.fsm-is-early` or `.fsm-is-late` (revised) + `.fsm-early-time`/`.fsm-late-time` (delta) | both present only when there's a deviation |
| Departure/arrival date | `.sch-dep-date-status-time` | one under `.departure`, one under `.arrival` |
| Airport name + IATA code | `.aiport-name span` | e.g. "Indira Gandhi International Airport (DEL)" — note the site's own typo "aiport" |
| Terminal + Gate | `.terminal` | single `<p>`, pipe-separated free text e.g. `"Terminal 3 \| Gate 24"` or `"...\| Gate N/A"` — needs text-split parsing, not two separate elements |
| Flight duration | `.duration` | e.g. "8 h 53 m" |
| Inbound-aircraft origin/status | `.flight-from` block: `.flight-from-textb` (origin city), `.flight-from-airline` (inbound flight no.), `.tailnumber` | describes the incoming aircraft leg feeding this flight, distinct from the queried flight's own status |
| Last updated timestamp | `.last-updated span` | |

**Quirks:**
- **Multiple `.flight-status-card` results for one query** — recon returned 2 cards for `AI 101`
  on `20260920` (one DEL→FCO leg, one FCO→JFK leg the next day), because the flight number recurs
  across legs/dates near the query date. A rule must NOT assume `querySelector` (singular); it must
  iterate all cards and disambiguate by departure airport/date against the user's saved itinerary.
- Angular Material (`mat-*`, `ng-*` classes) — reactive forms, `ng-invalid`/`ng-valid`/`ng-dirty`
  classes are a reliable way to check field validity programmatically if needed.
- Date range is a hard 5-day window (today ±2); cannot query further out or in the past via this UI.

---

## 4. SpiceJet Flight Status (bonus — time permitted)

- **URL:** `https://www.spicejet.com/flight-status` (no redirect, not literally bot-walled/geo-blocked)
- **Not submitted** — did not get far enough to identify a submit action before concluding recon.

**Architecture finding (the real headline here):** the entire site is built with **React Native
for Web** — every element has only auto-generated atomic CSS classes (`css-1dbjc4n`, `r-<hash>`,
the signature RN-Web/Expo output). There are **no semantic class names, no relevant `data-testid`
attributes** (58 `data-testid`s found on the page, zero matching flight/status/search/date/pnr),
and class names are expected to change across SpiceJet's own deploys since they're compiler output,
not authored names.

**Practically observed:** a "Flight Status:" widget exists with date tabs "YESTERDAY / TODAY /
TOMORROW" and a route display showing literal text **"undefined to undefined"** at rest (an
unfilled origin→destination placeholder bug/state, suggesting this is a **route-based** search —
origin + destination + date — not flight-number or PNR based, though the exact input fields for
origin/destination were not isolated in the time available).

**Effective classification: unscrapeable via CSS selectors.** Any rule for this site would have to
anchor on **visible text content** ("Flight Status:", "TODAY", "Modify Search") and walk the DOM
structurally from there, which is fragile against any copy change and unverifiable without
per-release re-testing. Combined with having no stable hooks at all, this is functionally
equivalent to a bot-wall for automation purposes, even though no literal CAPTCHA/challenge page was
served — **recommend treating SpiceJet as WebView-with-manual-entry-only** (rule declares itself
unsupported for structured extraction) rather than investing in brittle text-matching selectors.

---

## 5. Akasa Air Flight Status (bonus — time permitted, partial)

- **URL:** `https://www.akasaair.com/flight-status` (no redirect)
- Typed flight number successfully; **could not complete submission** in this session — the date
  combobox click timed out ("element outside of viewport" even after scroll-into-view retries),
  likely a sticky header/overlay; not chased further given time budget. Needs follow-up recon,
  ideally in an actual mobile-viewport context since the layout is responsive and this is a
  phone-first app.

**Input selectors:**
- Three tabs: "Flight Number" / "PNR" / "Cities" — plain `<p>` elements, matched by exact text only
  (no id/data-testid); active tab has class `p-4 text-typography-primary`, inactive `p-4 false`.
- Flight number: `#flightNumber` (`maxlength=5`, numeric suffix only; "QP" carrier prefix is a
  **decorative overlay `<span>`**, not part of the submitted value — strip any user-entered "QP"
  before filling).
- Date: a `react-select`-style combobox (`mui-style-*` hashed classes for internals), trigger
  element has `id="phoneCode"` — **this id is almost certainly a recycled/generic id from a shared
  component library, not semantically stable**; prefer `aria-label="Departure date"` as the anchor.

**Quirks:**
- Third distinct date-widget implementation seen across the 4 airline sites (IndiGo: button+overlay
  picker; Air India: Angular Material `mat-select`+CDK overlay; Akasa: react-select portal). No
  shared "pick a date" helper will work across providers — expect bespoke per-site logic.
- Uses Tailwind utility classes for layout (readable, unlike SpiceJet) but MUI-generated hashed
  classes for the date-select internals — mixed stability within the same page.

---

## Cross-cutting takeaways for `core:scrape` rule design

1. **Captcha must be assumed possibly-present, not absent**, at least for Indian Railways — the
   toggle is live server-side state, confirmed ON at recon time. Rules touching this site need a
   foreground/visible WebView step, not headless background scraping.
2. **Cookie-consent overlays can be hard blockers, not cosmetic** — Air India's OneTrust overlay
   silently ate every click attempt with no error until dismissed. Every rule should have an
   explicit "dismiss known consent banners" step before any field interaction, site-specific
   (button ids/text vary: OneTrust `#onetrust-accept-btn-handler` vs IndiGo's plain-text "Accept All").
3. **No shared date-picker automation pattern exists** across airlines — native-ish button+overlay
   (IndiGo), Angular Material mat-select+CDK overlay with a fixed ±2-day option list (Air India),
   and react-select portal (Akasa) are all different. Each site's rule needs its own date-selection
   logic; there is no reusable "pick today" helper across providers.
2b. **Result shape can be multi-valued** — Air India returned 2 result cards for one flight-number
   query; rules must iterate, not assume a single result node, and disambiguate against the saved
   itinerary's airport/date.
4. **Some sites are structurally unscrapeable** — SpiceJet's React-Native-Web atomic CSS with zero
   relevant `data-testid`s means no durable selector exists; text-content matching is the only
   option and is fragile. Recommend flagging such providers as "WebView, manual-entry-only" in the
   provider registry rather than shipping brittle rules.
5. **Some sites don't support the input mode you'd expect** — IndiGo has no flight-number search at
   all (PNR-only); this must be reflected in each provider's declared required-input contract
   (per AGENTS.md's `TrainStatusProvider`/`FlightStatusProvider` Hilt-bound interface pattern) so the
   UI can gray out/hide fields the active provider can't use, and fall back to mock/manual per
   provider rather than one-size-fits-all input UI.
6. **Fixture tests (ADR-003) must capture the actual observed JSON/HTML shapes above**, especially
   Air India's `.flight-status-card` (multi-card) shape and the Indian Railways `CommonCaptcha`
   response field names — the latter still needs a real successful PNR query to confirm
   `passengerList[]` sub-field names precisely; treat as unconfirmed until then.
