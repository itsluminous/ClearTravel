# core:data

The repository layer and cross-feature contracts. Repository interfaces (and their
Room-backed implementations) are the ONLY way features read or write data —
cross-feature interaction goes through contracts defined here, never through direct
feature-to-feature dependencies. This module also owns the pluggable status-provider
interfaces (`TrainStatusProvider`, `FlightStatusProvider`, bound via Hilt so
mock/manual implementations work without API keys) and the settings DataStore.
Repositories bump `updated_at` on every write and soft-delete via tombstones
(ADR-002). Concrete repositories land with their feature milestones; the skeleton
ships the provider contract stubs.
