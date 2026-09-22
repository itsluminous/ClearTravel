# feature:itinerary

The Trips tab: trips (name, destination, date range, cover) holding day-grouped
itinerary items — places (coordinates, category, planned time, note, link) and commute
legs (mode, from → to, optional link to a train/flight journey through `core:data`
contracts only). Nested NavHost inside the tab (ADR-012).

- **Trips list** (`trips/`): Active|Archived `FullWidthFilterRow`, trip form dialog
  with the shared `LocalDatePickerDialog`.
- **Trip detail** (`detail/`): timeline ↔ Google Maps view (numbered per-day markers,
  no connecting lines — ADR-035; degrades to a notice without a key / Play services);
  days auto-sort by planned time, drag-to-reorder overrides (ADR-029); item sheet with
  the resolved linked-journey label, "Open in Journeys", "View in map".
- **Item form** (`form/`): location picker with OpenStreetMap **Nominatim** place
  search + platform `Geocoder` fallback (`logic/PlaceGeocoder`, ADR-035), map tap,
  typed `lat, lng`; journey picker incl. "Add a new train/flight" through the
  `JourneyAddRequestBus` (ADR-028); a linked leg takes its time from the journey.
- **Maps-link intake** (`intake/`): a shared Google Maps URL → pure `MapsLinks`
  parser, background short-link resolution, "add to trip" dialog (ADR-029 D).
- `logic/` is pure and JVM-tested: day grouping/sorting, map content, palette,
  `LatLng` parsing, `JourneyTimes`, `JourneyLabels`, `MapsLinks`, Nominatim parsing.

Public hooks: `tripsGraph(landing, onLandingConsumed, onOpenJourney)`, `TripsLanding`,
`MapsLinkIntakeHost`. maps-compose is pinned to 6.7.0 (ADR-012 note). Strings are
`itinerary_*`.
