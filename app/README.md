# app

The single-activity Compose shell: `ClearTravelApplication` (Hilt), `MainActivity`
(AndroidX splash screen, edge-to-edge), the four-tab bottom navigation scaffold
(Trips / Journeys / Checklist / Menu) hosting each feature's nav graph, and the
Journeys tab's Trains|Flights segmented composition (the only place `feature:trains`
and `feature:flights` meet — features never depend on each other). Also owns the
launcher icon, XML themes, DI wiring, the manifest (Maps API key via
`manifestPlaceholders` from `local.properties`), and the instrumented e2e suite in
`src/androidTest`.
