# app

The single-activity Compose shell: `ClearTravelApplication` (Hilt), `MainActivity`
(`FragmentActivity` for BiometricPrompt; AndroidX splash, edge-to-edge), the
`AppLockGate` wrapped around everything (ADR-031/032 — nothing touches Room before it
opens; startup housekeeping and the one-time notification-permission request run
inside it), the five-tab bottom navigation (Trips / Journeys / Checklist / Documents /
Menu) hosting each feature's nav graph, and the Journeys tab's Trains|Flights
segmented composition (the only place `feature:trains` and `feature:flights` meet).

Cross-tab coordination lives here and nowhere else: notification deep links and
share-sheet intake (text → train SMS form or Maps-link intake; files → "What's this
file?" → the feature entry composables), `JourneysDeepLink` / `JourneysLandings`
(once-only landings, ADR-029), `JourneyPickCoordinator` over the `JourneyAddRequestBus`
(ADR-028), `TripsLanding`, `ShellChromeController` for the viewer's fullscreen,
`AppStartupTasks` (auto-archive past journeys, flight-poll re-kick).

Also owns the launcher icon, XML themes, `FileProvider` paths, DI composition
(`AppBindingsModule`), the manifest (`MAPS_API_KEY` placeholder, `GOOGLE_WEB_CLIENT_ID`
`BuildConfig` field — both from the ROOT `local.properties`), and the hermetic
instrumented e2e suite in `src/androidTest` (`HiltTestRunner`, `TestDatabaseModule`,
`TestRepositoryModule`, `TestSecurityModule`; helpers in `E2eHelpers.kt`).
