# Scrape-rule recon follow-up

Status after cross-checking `core/scrape` against `docs/recon/` (captured 2026-09-20).

## indianrail-pnr v1 — aligned with recon ✅ (with remaining live-verification TODOs)

The rule was built from the SAME primary sources the recon agent captured (live page
skeleton + `pnrEnquiryJS.js?version=18` `showPnr()`/`drawRow()`), so the recon confirmed
rather than changed it:

- `#inputPnrNo` prefill — confirmed (numeric-only, maxlength 10).
- `submitSelector: null` — confirmed CORRECT: captcha was LIVE at recon time
  (`GET /enquiry/CaptchaConfig` → `"1"`); auto-submit is unsafe. The user taps
  `#modal1`, solves the drawn captcha in `#myModal`, taps `#submitPnrNo`.
- readySignal — recon stresses the result is a **show/hide of static markup**
  (`#pnrOutputDiv` shown, `#inputPnrNoDiv` hidden), so presence checks are useless;
  the rule's `jsCondition` checks computed visibility of `#pnrOutputDiv` AND >1 `tr`
  in `#psgnDetailsTable` (rows only exist after `showPnr()` ran). Aligned.
- Extraction selectors (`#journeyDetailsTable/#psgnDetailsTable/#otherDetailsTable`
  cells) — match the row-append code in `showPnr()`/`drawRow()` verbatim.

### TODO — needs a REAL post-captcha run (no valid PNR was available to either agent)

- [ ] Capture a real result-page `outerHTML` after a successful captcha submit and
      replace/augment `core/scrape/src/test/resources/fixtures/indianrail-pnr/page.html`
      (current fixture is a faithful reconstruction from the render JS, not a live dump).
- [ ] Confirm `passengerList[]` rendering for edge classes (`1A` coach handling differs
      in `showPnr()`) and for WL/CAN passengers (berth suffix rules in `drawRow()`).
- [ ] Verify the exact `journeyDate` cell format year-round (JS emits non-zero-padded
      `d-M-yyyy`; postProcess already accepts both padded and unpadded).
- [ ] Consider a prefill step or engine hook to auto-open the captcha modal
      (`#modal1` click) AFTER user confirmation — UX sugar only, keep auto-submit off.
- [ ] Ad iframes surround the form — instrumented test should confirm extraction scopes
      correctly (jsoup selectors are id-scoped, so iframe subtrees are ignored already).

## Airline rules (NOT in this wave — for the flights feature agent)

Recon findings that must shape those rule files (full detail in `docs/recon/NOTES.md`):
IndiGo is PNR-only (no flight-number search); Air India needs OneTrust consent dismissal
first and returns MULTIPLE `.flight-status-card` results (use `rows` extraction);
SpiceJet has no stable selectors (React-Native-Web atomic CSS) — treat as
WebView-manual-only; Akasa's date widget id (`#phoneCode`) is a recycled generic id —
anchor on `aria-label="Departure date"`.

## Airline rules — flights milestone status (2026-09-20, flights agent)

Rule inventory decision recorded in ADR-010; per-airline status:

| Airline | IATA | Rule file | Basis / reason |
|---|---|---|---|
| Air India | AI | `airindia.json` v1 ✅ **verified** | Selectors from the REAL captured result DOM (`docs/recon/airindia.html`). Uses `?fno={flightNumber}&on={date}` (yyyyMMdd) query params directly — no prefill/submit, so the OneTrust pointer-blocking overlay can't break automation (JS/DOM extraction is unaffected; the user can dismiss the banner in the visible WebView). Fixture covers the MULTI-CARD result shape (2 cards for one query); the feature mapper disambiguates by dep airport → dep date → first. |
| IndiGo | 6E | ❌ skipped | Status search is PNR-only AND the submit button stayed `disabled` in recon with the enable condition unresolved; the result DOM was never observed, so there is no credible extraction basis. Falls back to web search. Revisit with a real PNR run. |
| SpiceJet | SG | ❌ none BY DESIGN | React-Native-Web atomic CSS, zero stable selectors — registry returns null → web-search fallback (path unit-tested in `FlightStatusCheckViewModelTest`). |
| Akasa Air | QP | ❌ skipped | Form selectors recon'd (`#flightNumber` stable, date picker is a react-select portal with a recycled `#phoneCode` id) but submission never completed — result DOM never observed, no extraction basis. |
| Air India Express / Emirates / Qatar / Singapore / Etihad / Delta / American / Lufthansa / Cathay | IX EK QR SQ EY DL AA LH CX | ❌ skipped | curl probes (2026-09-20) returned JS app shells (8–14 KB, no result markup; only Delta exposes even a `flight-status` class on the shell) — no server-rendered structure to base research-based selectors on. Need live-browser recon with a submitted query each; until then registry returns null → web-search fallback. |

All 13 airlines DO ship check-in-window entries + web check-in URLs in
`feature/flights/src/main/assets/checkin-windows.json` (best-effort researched URLs,
data-file updatable).

### TODO — needs live result captures
- [ ] Re-recon IndiGo with a real PNR (resolve the submit-enable condition; capture the result container under `aria-label="Flight Status Result"`).
- [ ] Akasa: complete a submission in a mobile viewport; capture result DOM.
- [ ] Live-browser captures for the 9 JS-shell airlines, one rule + fixture each.
- [ ] Air India: confirm the `on=` param accepts all 5 dates of the ±2-day window and capture a DELAYED-status card (fixture currently has the early-arrival shape from the live capture).
