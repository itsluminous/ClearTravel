# feature:trains

Train journeys: manual ticket entry (PNR, train, date, stations, class, coach/seat,
passengers, quota, booking status), the ticket detail bottom sheet (per-passenger
booking/chart status, seat details, train schedule/route, derived journey duration),
user-initiated PNR status refresh via the rule-driven WebView scrape of the Indian
Railways enquiry page (foreground-only — captcha, ADR-011), SMS/email paste and OCR
prefill paths, and the archive list (auto-archive is a later polish stage; the pure
`isPastJourney(ticket, today)` predicate ships now). Renders inside the Journeys tab
(Trains segment) — the app module composes it next to `feature:flights`, and this
module depends only on `core:*`, never on another feature.

## Public surface (integration contract)

- `TrainsContent(modifier: Modifier = Modifier)` — the Trains segment root. Hosts the
  ticket list, add/edit form, detail sheet and PNR-check WebView behind internal
  navigation state; the app module only places it.
- `TrainsSharedTextEntry(sharedText: String, onDone: () -> Unit, modifier: Modifier = Modifier)`
  — share-sheet entry point for the DEFERRED `ACTION_SEND` text intent filter. The
  integrator passes `intent.getStringExtra(Intent.EXTRA_TEXT)` and a close callback;
  the composable opens the add form prefilled from the IRCTC SMS/email parser and
  calls `onDone` after save or cancel.
- `ManualTrainStatusProvider` — the Hilt-bound `TrainStatusProvider` (ADR-011): the
  interactive WebView refresh bypasses the provider seam by design (captcha), so this
  always-available stub keeps the seam alive for a future API-backed provider.
