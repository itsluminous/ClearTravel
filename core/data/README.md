# core:data

The repository layer and every cross-feature contract. Repository interfaces (Room-
backed `offline/` implementations) are the ONLY way features read or write data; they
expose `core:model` types only, bump `updated_at` on every write and soft-delete via
tombstones (ADR-002). Also here:

- **Provider seams** `TrainStatusProvider` / `FlightStatusProvider` (ADR-005; Hilt-
  bound, manual/mock always available), `FlightIdentity` normalization (ADR-025).
- **Settings** (`SettingsRepository`): theme, provider ids, lock timing, onboarding
  flag in Preferences DataStore; status API keys in EncryptedSharedPreferences (ADR-007).
- **Backup engine** (`backup/`): `BackupManager` seam, format v2 password envelope
  around the versioned ZIP, generic LWW `BackupMerger`, DTOs decoupled from Room —
  `docs/backup-format.md`, ADR-015/031.
- **Security wiring below Room** (`security/`): `VaultKeyedOpenHelperFactory` (lazy
  SQLCipher), plaintext → encrypted database and file migrations,
  `SecureStorageInitializer`, `AppFileLayout` (the one list of user-file directories),
  `TravelDocumentStorage`.
- **Cross-tab seam** `crosstab/JourneyAddRequestBus` (ADR-028) in its own
  `CrossTabModule` so the hermetic e2e keeps the real bus.
- Built-in checklist presets as a versioned JSON asset seeded idempotently (ADR-006).

Contract changes here need an ADR entry (`docs/decisions.md`).
