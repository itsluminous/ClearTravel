# Google Flight-Status Panel — Recon Notes

Captured 2026-09-22, mobile viewport (390×844, Chromium via Playwright), `hl=en`.
Queries used: `AI+101+flight+status`, `6E+2001+flight+status`, `UA+2402+flight+status`,
plus fallback probes `ZZ+9999`, `ZH+9999`, `DL+89`. Raw trimmed panel HTML saved
alongside this file as `google-flights-AI101.html` and `google-flights-6E2001.html`.

## TL;DR strategy recommendation

**Reliably scrapeable, with caveats.** The panel embeds a structured JSON blob
(`data-maindata` on a `div[data-bkt="onebox"]`) containing parallel arrays of
`flight_status` enum values (`SCHEDULED_STATUS`, `IN_AIR_ON_TIME`, `ARRIVED`, likely
`CANCELLED_STATUS`/`DELAYED_STATUS` — not observed live but implied by the enum
naming pattern) plus signed delay-in-minutes fields. **This JSON blob is the single
best anchor** — parse it with a regex/JSON extraction rather than walking the DOM,
because it survives Google's CSS class rotation entirely (it's app data, not
presentation).

If the JSON blob parsing is judged too fragile (its exact key names/shape could
change without notice — it's undocumented/internal), the **second-best strategy**
is aria-label / structural anchors on the rendered card:
1. Anchor on the `<h2>Flight status</h2>` text (stable, visible, translated per `hl`)
   to locate the panel container — do not use `.MjjYud`/`.NdPIL` etc.
2. Within that container, anchor on `role="tablist"` / `role="tab"` for the date
   strip, and the `role="button"][aria-expanded]` header row for the flight card.
3. For each labeled field (Scheduled departure / Departed / Terminal / Gate / etc.),
   match on the **label text** in the small caption div (`cZmqkf` class today, but
   identifiable by being a short static English string) paired with its adjacent
   sibling holding the value — i.e. anchor on text content + DOM adjacency, not
   on the class name of either div.
4. Header status text (`On time` / `Scheduled` / `Departing on time` / etc.) should
   be extracted via a small controlled-vocabulary regex rather than by class,
   since Google does not expose a `data-status` attribute on the header.

Do NOT anchor on any class name observed in this recon (`ngrL4`, `cZmqkf`, `ZuFvNc`,
`Q1wtNe`, `Dzuqed`, `Ydpold`, etc.) — these are short, obfuscated, and rotate across
Google deploys (already differ in unrelated ways between the two capture sessions
in this recon, e.g. inline style diffs on `.XABdj`). Treat them as CI-fixture-only,
never as production selectors.

**Recommended hybrid**: try `data-maindata` JSON first (cheap, most robust to
markup changes) → fall back to the label-text/aria-structure DOM walk if the JSON
is absent/malformed → fall back to "raw page" (whole visible text regex for
airport codes/times) per ClearTravel's existing ready-signal-timeout pattern. This
mirrors the `core:scrape` engine's existing `dismissSelectors` + raw-page-fallback
design (ADR-003) — no architecture change needed, just a rule file.

## Container / anchor map

| Element | Anchor (recommended) | Stability |
|---|---|---|
| Panel container | `h2` with exact text `Flight status`, then walk to nearest ancestor containing `[role="tabpanel"]` | **High** — visible heading text, `hl`-localized but ClearTravel already fixes `hl=en` |
| Structured data | `div[data-bkt="onebox"][data-maindata]` (attribute presence), parse the `data-maindata` JSON string | **High** for presence check; **Medium** for the JSON *shape* (undocumented internal format, key names could shift) |
| Airline + flight number header | `h3` inside panel, text pattern `"{Airline} {ORIGIN_LETTERS?}\d+"` — e.g. `Air India AI 101` | **High** — plain text, stable structurally (first `h3` after the `h2`) |
| Date tabs | `[role="tab"]` list, `aria-selected="true"` marks current day | **High** — ARIA roles |
| Flight card header (collapsed/expanded toggle) | `[role="button"][aria-expanded]` whose text matches `HH:MM ... to {City} {CODE} ... {status text}` | **High** structurally (role+aria), **Medium** for parsing (need regex over concatenated text, no sub-selectors for the 4 sub-fields — see below) |
| — time (within header) | first `HH:MM am/pm`-shaped token in the button's text | Medium — text-pattern only, no dedicated attribute |
| — flight number (within header) | token matching `[A-Z0-9]{2,3}\s?\d{1,4}` in header text | Medium — text-pattern only |
| — destination city+code (within header) | text after `"to "` up to end/status keyword | Medium — text-pattern only |
| — header status text | remaining short trailing text (`On time`, `Scheduled`, `Departing on time`, `Delayed`, `Cancelled` expected) | Medium — free text, use a controlled vocabulary regex; case-sensitive English capitalization observed |
| Origin/destination airport codes | Two short (3-letter) uppercase tokens immediately followed by an `<a>` whose `aria-label` matches `Airport info for {CODE}` | **High** — the `aria-label="Airport info for XXX"` pattern is a very reliable anchor per side |
| Flight duration ("Xh Ym flight.") | `<span>` whose visible text ends in `flight.` (present) / **absent entirely once departed** | Medium — text-pattern; also a signal of pre- vs post-departure state |
| Departure/arrival block label | Short caption div immediately preceding the value div, text is one of a **closed set**: `Scheduled departure` / `Departed` / `Estimated departure` (inferred, not observed) / `Scheduled arrival` / `Estimated arrival` / `Landed` (inferred, not observed) | **High** as a matching strategy (fixed vocabulary), **Low** if anchored by class |
| Departure/arrival value | Sibling div immediately after the label div, text `HH:MM am/pm` | Medium — positional sibling of the label |
| Original (pre-delay) time strikethrough | `<del aria-hidden="true">HH:MM am/pm</del>` immediately after the current-value div, ONLY present when delay ≠ 0 | **High** — semantic `<del>` tag + `aria-hidden`, very likely to survive class rotation since it's a meaningful semantic element |
| "Originally scheduled X: HH:MM" caption | Sibling div with text prefix `Originally scheduled departure:` / `Originally scheduled arrival:` | High — fixed text prefix, redundant with the `<del>` — use as backup/confirmation |
| Terminal | Caption div with exact text `Terminal`, sibling holds value (`"3"`, `"-"` when absent) | **High** — fixed label text |
| Gate | Caption div with exact text `Gate`, sibling holds value (`"C10"`, `"-"` when unassigned) | **High** — fixed label text |
| City name + date (per side) | Div directly above the label/value rows, text pattern `"{City} · {Day}, {DD} {Mon}"` | Medium — text-pattern, `·` middot separator is consistent |
| Freshness | Div with text prefix `Updated ` + relative time (`0m ago`, `3h 1m ago`) | High — fixed prefix |
| Data source attribution | `<a aria-label=" {Source}, flight status data source ">` — sources seen: `OAG`, `Cirium` | **High** — the `aria-label` suffix `, flight status data source` is a reliable, stable anchor; also tells you which backend fed this result (may correlate with data reliability/latency) |
| Footer disclaimer | Div with exact text `Showing local airport times` | High — always present when the panel renders, useful as a "panel definitely rendered" sentinel independent of the `h2` |

## Field availability matrix

| Field | AI 101 (scheduled, not yet departed) | 6E 2001 (departed, running early) | UA 2402 (scheduled, on time) |
|---|---|---|---|
| Header status text | `Scheduled` | `On time` | `Departing on time` |
| Dep label | `Scheduled departure` | `Departed` | `Scheduled departure` |
| Dep value | `10:55 pm` | `8:09 am` (actual) | `9:57 am` |
| Dep original (struck) | absent | present: `8:20 am` | absent |
| Dep terminal | `3` | `-` (not shown) | `C` |
| Dep gate | `-` (unassigned) | `-` | `C10` |
| Arr label | `Scheduled arrival` | `Estimated arrival` | `Scheduled arrival` |
| Arr value | `4:40 am` | `9:44 am` | `2:38 pm` |
| Arr original (struck) | absent | present: `10:15 am` | absent |
| Arr terminal | `3` | `2` | `A` |
| Arr gate | `-` | `-` | `A26` |
| Duration text ("Xh Ym flight.") | present (`9h 15m`) | **absent** (already departed) | present (`3h 41m`) |
| Route subtitle | `2 flights found` (multi-leg AI101/AI102 group) | `Patna to New Delhi` (single-route label) | not captured (not screenshotted) but same pattern expected |
| Data source | OAG | Cirium | OAG |
| `data-maindata` flight_status enum | `SCHEDULED_STATUS` | `ARRIVED` (T-1 day) / `IN_AIR_ON_TIME` (current) / `SCHEDULED_STATUS` (future days) | not extracted |

Fields that appear **only sometimes**:
- **Terminal / Gate**: rendered as literal `-` when unassigned (both airports, both
  legs) rather than omitted — safe to always look for the label, never assume absence.
- **Duration ("Xh Ym flight.")**: disappears once the flight has departed (6E 2001
  case) — don't rely on it for post-departure state.
- **Struck-through original time + "Originally scheduled..." caption**: only
  present when actual/estimated time differs from the original schedule (i.e.
  meaningfully early/late) — absent for on-time or not-yet-updated flights. This is
  effectively a proxy delay signal (**presence of `<del>` ⇒ flight has deviated from
  schedule**), useful even without parsing exact minutes.
- **Multi-leg grouping ("N flights found" + tab strip spanning connecting legs)**:
  only appears for flights that are one leg of a published multi-leg itinerary
  (AI 101 DEL→FCO→JFK). Single-leg point-to-point flights show a plain route
  subtitle instead (`Patna to New Delhi`).
- **AI Overview panel**: present on every query tested, sits below the flight-status
  card, and independently echoes status/times in prose + a bulleted list — could
  serve as a low-confidence secondary source but is clearly LLM-generated summary
  text, not a structured feed; not recommended as a primary source since its
  freshness/accuracy is not guaranteed the same way as the flight-status card.
- **CANCELLED / DELAYED live examples**: not captured live in this session despite
  probing several current flights (AI 101, 6E 2001, UA 2402, DL 89 all on-time or
  ahead of schedule at capture time). One incidentally-found *reference* — Shenzhen
  Airlines `ZH 9999` — showed `Cancelled` status **only in the AI-Overview prose**,
  because Google's rich flight-status card itself did not render for that query (see
  fallback section below). This means the rich card's cancelled/delayed status text
  itself was not directly observed; the header-status text extraction should still
  treat `Delayed` / `Cancelled` as expected members of the controlled vocabulary
  based on Google's documented UI conventions, but this specific rendering has not
  been fixture-verified — **flag as a gap**, recommend a follow-up recon pass when a
  real cancelled/delayed flight is available, or synthetic fixture construction from
  the two captured HTML samples (swap status text + delay sign) for CI.

## Consent wall

**Not encountered in any of the 6 queries in this session** (`AI 101`, `6E 2001`,
`UA 2402`, `ZZ 9999`, `ZH 9999`, `DL 89`), including the very first cold navigation
of the browser session. No redirect to `consent.google.com` was observed in the
network request list. This is consistent with either: (a) the sandbox's egress
IP/cookie-jar already carrying an EU/UK-independent (India, per footer geolocation
"Bengaluru, Karnataka") region that Google does not show the interstitial consent
screen for, or (b) Playwright's default context not triggering it. **ClearTravel
should not assume this holds for all users** — the existing `core:scrape` engine's
`dismissSelectors` mechanism should still include a rule for the consent page (its
typical structure is a `#introAgreeButton` / button with visible text `I agree` /
`Accept all` on the `consent.google.com` domain) as a defensive measure, since EU
users hitting google.com directly (rather than a region-specific TLD) are known to
be redirected. No dismiss-selector could be captured/verified this session since
the wall never appeared.

## Renders without JS? (curl-equivalent check)

Verified via `fetch()` of `https://www.google.com/search?q=AI+101+flight+status&hl=en`
with a mobile Chrome UA string, comparing the **raw HTTP response body** (before any
client-side JS executes) against the rendered page:
- Response: `200`, `~448 KB` HTML.
- Raw body **already contains** the `Flight status` heading text, `Scheduled
  departure` label text, the full `data-maindata` JSON blob, and the flight number
  text `AI 101`.
- No `consent.google.com` / `CONSENT` markers in the raw body.

**Conclusion: the panel is server-side rendered.** It does not require JS execution
to appear in the DOM — Google sends the fully-populated card in the initial HTML
response. This is very good news for ClearTravel's WebView scraper: even if JS is
disabled or the ready-signal times out, the raw-page-fallback path should already
have the data available via a plain HTML parse (no need to wait for a JS-driven
"ready" mutation on this specific query type). The one caveat: this `fetch()` check
ran from within an already-loaded google.com page (so it shared cookies/session
with the browser), which is not a byte-for-byte equivalent to a cold, unauthenticated
`curl` — but structurally this matches Google SERP's well-known behavior of
server-rendering the primary content and progressively enhancing it with JS
(tab-switching, sharing, feedback widgets) — those interactive affordances are
inside `<script>` blocks and `jsaction` attributes layered on top of already-present
markup, not required to reveal the data fields ClearTravel needs.

## URL template stability

- `https://www.google.com/search?q={URL_ENCODED_QUERY}&hl=en` worked reliably for
  all six queries tested, including a query with a space-containing flight code
  (`AI 101`, `6E 2001`, `UA 2402` — encoded as `+`).
- `hl=en` **is recommended** — it pins the header/label vocabulary language
  (`Scheduled departure`, `Terminal`, `Gate`, etc.) independent of the account/
  region locale, which matters for the label-text-matching strategy above. Without
  it, Google may localize labels to the request's inferred locale (this session's
  footer showed an India/Bengaluru geolocation, so `hl=en` was likely already the
  effective default here, but pin it explicitly for determinism).
- Query format `"{CODE} {NUMBER} flight status"` (e.g. `AI 101 flight status`)
  reliably triggered the rich card for all three real, currently-operating flights
  tested. No `udm=`/`tbm=` parameter was needed.
- No pagination/session token (`sei=`, `ei=`) needed in the URL — Google appends its
  own tracking params to the *response* URL but the *request* URL is stable and
  reusable as a template: `search?q=<airline_code>+<flight_number>+flight+status&hl=en`.

## "Flight not shown" fallback shape

Tested with a deliberately invalid IATA code (`ZZ 9999`, not a real airline) and a
real-but-currently-non-operating flight number under a real airline (`ZH 9999`).
In **both** cases:
- **No `Flight status` heading/card renders at all** — this is the cleanest
  possible "absence" signal: just check for the presence/absence of the `h2`
  text `Flight status` (or, more robustly per above, the `data-bkt="onebox"`
  element) rather than trying to detect an explicit "not found" message, because
  Google does not show one for the rich card — it simply omits it.
- Google instead falls back to: an **AI Overview** synthesizing whatever it can
  find from indexed pages (sometimes correctly identifying real-world status like
  the `ZH 9999 → Cancelled` case, but sourced from crawled third-party pages, not
  the live flight-status feed) + standard **web results** (FlightAware, Ixigo,
  MakeMyTrip, etc. listings).
- For ClearTravel's scraper, the correct behavior is: treat absence of the
  `Flight status` heading (or `data-bkt="onebox"` container) as "no result from
  Google" and fall through to whatever the app's next provider/fallback tier is —
  do **not** attempt to parse the AI Overview prose as a structured status source
  (per the caveat above, it's unreliable/derived, not a live feed).

## Files written

- `docs/recon/google-flights-NOTES.md` — this file
- `docs/recon/google-flights-AI101.html` — trimmed panel HTML, scheduled multi-leg
  flight, no delay
- `docs/recon/google-flights-6E2001.html` — trimmed panel HTML, departed/ahead-of-
  schedule flight, showing the `<del>`-strikethrough delay-indicator pattern

Committed with the google-parse stage (ADR-026) as the fixture provenance.
