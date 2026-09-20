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
