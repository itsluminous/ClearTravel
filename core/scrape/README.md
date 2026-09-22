# core:scrape

The rule-driven WebView scraping engine shared by trains (Indian Railways PNR enquiry)
and flights (per-airline status pages). Parsing rules are **data, not code** (ADR-003,
ADR-008): each scraped site gets its own versioned JSON rule file in
`src/main/assets/scrape-rules/` and the engine — written once, unit-tested once —
executes any rule. Parse failures always fall back to the raw page — never a crash.

## Architecture (ADR-008)

```
feature module (owns the WebView composable)
   │  RuleRegistry.ruleById(..) / flightRuleFor("6E-2345")
   ▼
RuleDrivenScrapeSession(rule, params)   ← WebView-agnostic brain; emits events Flow
   ▲ onPageReady() / onHtmlDumped(html)
   │
ScrapeWebViewController(webView, session)  ← THIN host: load → prefill → poll → dump
   │  document.documentElement.outerHTML
   ▼
RuleExtractor.extract(rule, html)  ← PURE jsoup fn = the fixture-tested code path
```

## Public API surface

- `RuleRegistry(source: RuleSource)` — `all()`, `ruleById(id)`,
  `flightRuleFor(flightNumber)` (IATA prefix, e.g. `6E-2345` → the rule declaring
  `"6E"`; unknown airline → `null` so features fall back to a web-search URL).
  Runtime source: `AssetRuleSource(context)`.
- `RuleDrivenScrapeSession(rule, params)` — `events: Flow<ScrapeEvent>`, `startUrl`,
  `prefillJavaScript()`, `submitJavaScript()`, `readySignalJavaScript()`,
  `dumpHtmlJavaScript()`, callbacks `onPageReady()` / `onHtmlDumped(html)`.
- `ScrapeEvent` — `PageReady`, `NeedsUserAction(reason)` (captcha / manual submit),
  `Extracted(data: ScrapedData, rawHtml)` (the dump the rule ran on, for feature-side
  post-processing — ADR-026), `ParseFailed(reason, rawHtml)`.
- `ScrapeWebViewController(webView, session)` — `start()` / `stop()`; caller's
  composable owns the WebView lifecycle. Deliberately thin, instrumented-tested later.
- `RuleExtractor.extract(rule, html): ExtractionResult` — pure, never throws.

## Rule file schema (`assets/scrape-rules/<id>.json`)

| Field | Meaning |
|---|---|
| `id` | Stable id; MUST equal the file name and the fixture directory name |
| `displayName` | Human-readable site name |
| `version` | Bump whenever selectors change |
| `kind` | `train` or `flight` |
| `iataCodes` | Airline IATA codes served (flight rules only), e.g. `["6E"]` |
| `urlTemplate` | Page URL; placeholders `{pnr}` `{flightNumber}` `{date}` `{trainNumber}` `{airlineIata}` |
| `prefill` | `[{selector, valueTemplate}]` — form fields injected via JS after load |
| `submitSelector` | CSS selector auto-clicked after prefill; **`null` when unsafe (captcha)** → user submits manually |
| `readySignal` | `{selector}` (exists + visible) or `{jsCondition}` (JS expr) marking the result rendered |
| `extract` | field → `{selector, attribute?, regexChain?, required?}`; regex chain applied sequentially, capture group 1 wins |
| `rows` | `{rowSelector, fields, minRows}` — repeating extraction (per passenger etc.) |
| `postProcess` | field → `{type: date\|time\|trim, inputFormats, outputFormat}` (best-effort) |

Failure semantics: blank `required` field, fewer than `minRows` rows, or nothing
extracted at all ⇒ `ExtractionResult.Failure(reason, rawHtml)`.

## Adding an airline rule (fixture-harness contract — CI enforced)

1. Create `src/main/assets/scrape-rules/<airline>.json` with `kind: "flight"` and its
   `iataCodes`. Verify the status-page URL live while writing it.
2. Record a fixture: run the site once, dump `document.documentElement.outerHTML` of
   the RESULT page, save as `src/test/resources/fixtures/<airline>/page.html`.
3. Write `src/test/resources/fixtures/<airline>/expected.json`:
   `{"fields": {..}, "rows": [{..}]}` — exactly what `RuleExtractor` must produce.
4. Run `./gradlew :core:scrape:testDebugUnitTest`. `RuleFixtureTest` enumerates EVERY
   rule file in assets; a rule without its fixture pair **fails the suite** with an
   actionable message. Nothing else to register — the harness discovers the file.

## Shipped rules

- `indianrail-pnr` v1 — https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html
  (prefills `#inputPnrNo`; NO auto-submit — captcha; extracts journey/passenger/chart
  tables). Selectors derived from the live page skeleton + its render JS
  (`pnrEnquiryJS.js` `showPnr()`/`drawRow()`); see `docs/recon-followup.md` for the
  post-captcha live-DOM verification TODO.
- `erail-route` v1 — https://erail.in/train-enquiry/{trainNumber} (train schedule /
  full station route; ADR-018). Direct GET, no prefill/submit/captcha/consent banner —
  a fully hands-free flow. Extracts `trainNumber`/`trainName` from the
  `#divRouteList` header and one row per station from `table.RouteList`
  (code/name/arr/dep/halt/platform/distance/day). Quirks captured in the rule +
  fixture: times are dot-separated `HH.MM`; the origin's arrival and the terminus'
  departure cells hold the literals `First`/`Last` (normalized by `feature:trains`'
  `RouteMapper`, not by the rule). Selectors + fixture from the real captured DOM of
  train 22346 (recon 2026-09-21).
- `google-flights` v1 — https://www.google.com/search?q={airlineIata}+{flightNumber}+flight+status&hl=en
  (ADR-026). Airline-AGNOSTIC fallback: `iataCodes` is empty so `flightRuleFor` never
  selects it; `feature:flights` looks it up by id when no airline rule exists (or one
  failed). Direct GET, defensive consent `dismissSelectors`, ready when the results
  container (or a bot-wall captcha form) rendered. Extraction is class-free — the
  visible `h2` "Flight status" is a `required` sentinel (absent = Google shows no card
  = clean failure), plus ARIA/text anchors for the flight label, selected date tab,
  header status, data source and freshness. Per-card details are parsed from the same
  dump by the pure `GoogleFlightsExtractor` in `feature:flights` (label/value sibling
  pairing the schema cannot express). Fixtures: `page.html` = REAL recon capture
  (AI 101), `no-panel.html` = no-card page, `live-landed-6e2001.html` = REAL WebView
  dump from the emulator whose DOM differs from the recon (empty tabpanels, card in an
  async sibling, no `data-maindata` status blob — hence that blob is optional).
