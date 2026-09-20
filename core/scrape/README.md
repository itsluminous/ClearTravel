# core:scrape

The rule-driven WebView scraping engine shared by trains (Indian Railways PNR enquiry)
and flights (per-airline status pages). Parsing rules are **data, not code** (ADR-003):
each scraped site gets its own versioned JSON rule file in `assets/scrape-rules/`
(url template, prefill/submit selectors, ready signal, extract map), and the generic
`RuleDrivenScraper` engine — written once, unit-tested once — executes any rule file
inside an in-app WebView via a JS bridge. Every rule file ships with a recorded HTML
fixture + expected-output JSON and a parameterized test runs every rule against its
fixture; a rule without a fixture fails CI. Parse failures always fall back to showing
the raw page — never a crash, never a blocked UI. The skeleton ships the rule schema
stub; the engine lands with the Trains milestone.
