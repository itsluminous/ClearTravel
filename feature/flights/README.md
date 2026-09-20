# feature:flights

Flight journeys: manual entry (airline + flight number + date with auto-derived
route/times), boarding-pass import (ML Kit BCBP barcode decode with OCR fallback,
stored for full-brightness offline display at the gate), the flight detail sheet
(status, scheduled vs estimated times, terminal/gate, baggage belt, live progress),
manual status/gate/check-in trigger buttons, and WorkManager polling with escalating
frequency driving check-in/gate-change/delay/belt notifications via
`core:notifications`. Renders inside the Journeys tab (Flights segment) — the app
module composes it next to `feature:trains`, and this module depends only on `core:*`.
The skeleton ships the tab content stub (`FlightsContent`).
