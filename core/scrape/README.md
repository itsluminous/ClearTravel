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
  composable owns the WebView lifecycle. Deliberately thin; its sequencing (dismiss →
  prefill → poll with per-tick dismissal → dump, ready-signal timeout dump, stop,
  touch-scroll hardening) is pinned by `ScrapeWebViewControllerTest` on Robolectric's
  WebView shadow.
- `WebView.configureTouchScrolling()` — the shared touch/scroll setup every
  Compose-hosted WebView applies (ADR-024 §4).
- `di/ScrapeModule` — the single Hilt `@Provides` for `RuleRegistry` (shared by trains
  and flights, ADR-014).
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

| Rule | Version | Source | Notes |
|---|---|---|---|
| `indianrail-pnr` | 1 | indianrail.gov.in PNR enquiry | Prefills `#inputPnrNo`; NO auto-submit (captcha, user-solved); extracts journey/passenger/chart tables. Selectors from the live page skeleton + its render JS; live-verified on a real PNR (ADR-023). |
| `airindia` | 2 | airindia.com flight status | `?fno=&on=` (yyyyMMdd) direct query; v2 dismisses the OneTrust banner (`dismissSelectors`); multi-card result rows, disambiguated by `feature:flights`. |
| `ixigo-route` | 4 | ixigo.com train page | PRIMARY route source (ADR-019): direct GET, day column, `extraRows.coaches` for the seat-map strip (ADR-022). v4 pins the LIVE mobile coach markup as well as the desktop capture. |
| `erail-route` | 2 | erail.in train enquiry | FALLBACK route source ("Try another source"): v2 targets the MOBILE layout the WebView actually receives (`#divResult table.DataTable`, no day column — `RouteMapper` infers days). |
| `google-flights` | 2 | Google "flight status" card | Airline-AGNOSTIC fallback (ADR-026): `iataCodes` empty (looked up by id), consent `dismissSelectors`, `h2` sentinel; v2 adds `+{date}` to the query. Card details are parsed by `feature:flights`' pure `GoogleFlightsExtractor`. |

Each rule ships its `fixtures/<id>/{page.html,expected.json}` pair (plus extra live
captures where the served DOM differed from the recon); `docs/recon-followup.md`
tracks the remaining live-verification TODOs.
