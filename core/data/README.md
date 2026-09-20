# core:data

The repository layer and cross-feature contracts. Repository interfaces (and their
Room-backed implementations) are the ONLY way features read or write data —
cross-feature interaction goes through contracts defined here, never through direct
feature-to-feature dependencies. This module also owns the pluggable status-provider
interfaces (`TrainStatusProvider`, `FlightStatusProvider`, bound via Hilt so
mock/manual implementations work without API keys) and the settings DataStore.
Repositories bump `updated_at` on every write and soft-delete via tombstones
(ADR-002). Provider result contracts are documented in ADR-005, checklist preset
append semantics in ADR-006, and API-key storage (EncryptedSharedPreferences) in
ADR-007. Built-in checklist presets are behavior-as-data: a versioned JSON asset
(`assets/presets/builtin-presets.json`, ADR-003) seeded idempotently on first run.
