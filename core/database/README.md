# core:database

Room persistence: `ClearTravelDatabase` (schema **v3**: v1 catalog ADR-004, v2
`train_coaches` ADR-022, v3 `travel_documents` ADR-027), entities, DAOs, converters and
the hand-written `DatabaseMigrations` (exported `schemas/` committed; `MigrationTest`
runs every step against real files). Room is the single source of truth (offline-
first). Every entity carries a UUID `id`, `updated_at` and a `deleted_at` tombstone
(ADR-002); read queries filter tombstones; `BackupDao` is the one sanctioned raw,
timestamp-preserving writer (ADR-015). Encryption lives BELOW this module (the SQLCipher
factory in `core:data`), so DAO tests keep the plain in-memory factory from
`core:testing`. Only `core:data` repositories touch DAOs; schema changes need an ADR.
