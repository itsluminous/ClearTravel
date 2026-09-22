# feature:trains

Train journeys, rendered as the Trains segment of the Journeys tab (the app module
composes it next to `feature:flights`; this module depends only on `core:*`).

- **Add paths** (`form/`, `prefill/`): manual form (journey date via the shared
  `LocalDatePickerDialog`), pasted IRCTC SMS/email, PDF/image via `core:ocr`, shared
  PNR link. Every save runs the ADR-024 duplicate-PNR guard (`TrainRepository.findByPnr`).
- **Cards + list** (`list/`): ADR-020 redesign — header band, status pills, action
  column (PNR check, seat map, route, share); Active|Archived via the shared
  `FullWidthFilterRow`; pure formatting in `TrainCardFormat`.
- **Detail sheet** (`detail/`): passengers/status, route + seat-map buttons,
  unconfirmed-seat hint, "Part of" trips (ADR-028).
- **PNR check** (`pnr/`): foreground WebView on the `indianrail-pnr` rule (user solves
  the captcha, ADR-011), pure `PnrStatusMapper`, `applyStatusResult` (inserts new
  passengers, ADR-023); injects the shared `RuleRegistry`.
- **Route** (`route/`): hands-free fetch — ixigo primary, erail fallback, "Try another
  source" (ADR-019); pure `RouteMapper`/`CoachMapper`; the OFFLINE `TrainRouteScreen`
  renders from Room only.
- **Seat map** (`seatmap/`): coach strip from `train_coaches`, bay/row grid from the
  `assets/seat-layouts/<class>.json` data files (ADR-022; `SeatLayoutAssetTest` pins
  every file), berth vs seat wording per `SeatKind`.
- **Share** (`share/`): off-screen card render → PNG via the app `FileProvider` + PNR
  deep link (`TicketShareLinks`).
- `provider/ManualTrainStatusProvider` keeps the `TrainStatusProvider` seam alive.

## Public surface (integration contract)

- `TrainsContent(initialTicketId, initialAction, landingNonce, onLandingConsumed,
  addRequest, onAddRequestDone, onOpenTrip, …)` — segment root with internal
  navigation (`TrainsNavigation.kt`); all hooks are defaulted.
- `TrainsExternalEntry(request: TrainsEntryRequest.Text|File|Pnr, onDone: (TrainsEntryResult) -> Unit)`
  — share-sheet / PNR-link intake host (`TrainsSharedTextEntry` is a thin wrapper);
  reports `Saved` / `DuplicatePnr` / `Cancelled`.
- `isPastJourney(ticket, today)` — used by the app's auto-archive housekeeping.

Strings are `trains_*`; scrape rules live in `core:scrape`; tests cover every mapper,
ViewModel, layout file and the navigation targets.
