# feature:flights

Flight journeys, rendered as the Flights segment of the Journeys tab (composed by the
app module next to `feature:trains`; depends only on `core:*`).

- **Add paths** (`form/`, `pass/`): manual form (date is picker-only via the shared
  `LocalDatePickerDialog`; the read-only field observes its own `InteractionSource`),
  boarding-pass import (BCBP barcode → OCR fallback, stored for offline gate display),
  booking-confirmation import as `Attachment` rows (ADR-017). `FlightFormViewModel.save`
  is the single funnel and refuses duplicates (`FlightIdentity`, ADR-025).
- **List + detail** (`list/`, `detail/`): cards, Active|Archived `FullWidthFilterRow`,
  detail sheet with status/times/gates/belt, documents list (boarding pass +
  confirmations → shared viewer), web check-in link, last-check outcome line, "Part of"
  trips (ADR-028).
- **Status check** (`status/`): visible WebView driven by `core:scrape` — airline rule
  (`airindia`) → Google flight-status card (`google-flights` rule + pure
  `GoogleFlightsExtractor`, ADR-026) → plain web search; pure `AirlineStatusMapper`,
  `FlightChangeDetector` → notifications; ParseFailed keeps the raw page with Retry /
  "Try web search".
- **Check-in windows** (`checkin/`): `assets/checkin-windows.json` (ADR-003/013) —
  per-airline window + display name + web check-in URL, fixture-tested.
- **Polling** (`polling/`): `FlightStatusWorker` self-chaining OneTimeWork with
  `NextPollDelay`'s escalating cadence, EntryPoint-injected (kept over `@HiltWorker`
  on purpose — see the class doc / ADR-013), no-ops with the "unlock to sync" nudge
  while the vault is locked (ADR-031).

## Public surface

- `FlightsContent(initialFlightId, initialAction, landingNonce, onLandingConsumed,
  addRequest, onAddRequestDone, onOpenTrip, …)` — segment root; hooks defaulted.
- `FlightsExternalEntry(request: FlightsEntryRequest.BoardingPass|BookingConfirmation,
  onDone: (FlightsEntryResult) -> Unit)` — share-sheet intake host.
- `FlightPollScheduler.ensureScheduled` — kicked by the app's startup housekeeping.
- `isPastFlight` lives in the app module (composes both features' aggregates).

Strings are `flights_*`. Live fixtures for the Google card (cancelled / delayed /
early / multi-card) live in `src/test/resources`.
