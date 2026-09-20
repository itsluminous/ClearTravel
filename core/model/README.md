# core:model

Pure domain models and enums shared by every layer: entity contracts (`SyncableEntity`
— UUID id + `updatedAt` + tombstone, required by the backup merge semantics), ID
generation helpers, and app-wide enums such as `ThemeMode`. No Android UI, no
persistence, no network — anything here must be trivially unit-testable and free of
framework dependencies beyond kotlinx-serialization annotations. This module is a
contract: once feature milestones land, changes here require an ADR entry in
`docs/decisions.md`.
