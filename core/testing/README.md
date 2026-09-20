# core:testing

Shared test infrastructure, exposed as MAIN source so every module can
`testImplementation(project(":core:testing"))`: `MainDispatcherRule` (swaps
`Dispatchers.Main` for a test dispatcher — required by any ViewModel test), a generic
`inMemoryDatabase<T>()` helper for Robolectric/instrumented DAO tests against any Room
database class, and the `Fixtures` builder object (every fixture field has a sensible
default so tests override only what they assert on; fixture strings never reach the
UI). junit, truth, and kotlinx-coroutines-test are `api` dependencies so consumers get
them transitively. This module never contains production code.
