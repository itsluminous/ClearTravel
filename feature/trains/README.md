# feature:trains

Train journeys: manual ticket entry (PNR, train, date, stations, class, coach/seat,
passengers, quota, booking status), the ticket detail bottom sheet (per-passenger
booking/chart status, seat details, train schedule/route), user-initiated PNR status
refresh via the rule-driven WebView scrape of the Indian Railways enquiry page
(foreground-only — captcha), SMS/email paste and OCR prefill paths, and past-journey
auto-archiving. Renders inside the Journeys tab (Trains segment) — the app module
composes it next to `feature:flights`, and this module depends only on `core:*`, never
on another feature. The skeleton ships the tab content stub (`TrainsContent`).
