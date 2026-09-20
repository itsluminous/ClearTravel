# ClearTravel backup format (schema version 1)

The frozen external contract behind `BackupManager` (ADR-015). Everything here is
versioned by `manifest.json`'s `schemaVersion`; readers MUST reject files whose
`schemaVersion` is greater than the newest they know (typed
`BackupException.UnsupportedSchemaVersion`) and MUST accept and migrate older ones.

## Container layout

A backup is a plain ZIP:

```
cleartravel-backup-YYYYMMDD-HHmm.zip
├── manifest.json                      # version + summary — parsed alone for previews
├── entities/
│   ├── trips.json                     # JSON array of TripDto
│   ├── itinerary_items.json
│   ├── checklists.json
│   ├── checklist_items.json
│   ├── checklist_presets.json
│   ├── checklist_preset_items.json
│   ├── train_tickets.json
│   ├── train_passengers.json
│   ├── train_route_stops.json
│   ├── flight_journeys.json
│   └── attachments.json
└── attachments/
    └── <attachmentId>                 # raw file bytes, local-only attachments only
```

Every entity file is a **full dump including tombstoned rows** — deletions must
replicate to other devices, so soft-deleted rows travel with their `deletedAt` set.

## manifest.json

```json
{
  "schemaVersion": 1,
  "appVersion": "0.1.0",
  "createdAt": 1789344000000,
  "entityCounts": { "trips": 4, "train_tickets": 2, "...": 0 }
}
```

- `schemaVersion` — format version this file was written with (currently `1`).
- `appVersion` — writing app's `versionName`, informational only.
- `createdAt` — export wall-clock time, epoch millis.
- `entityCounts` — rows per entity file (tombstones included); drives the import
  preview dialog without parsing entity files.

## Entity JSON conventions

DTOs (`core/data/.../backup/BackupDtos.kt`) are deliberately decoupled from Room
entities and domain models — mappers translate in both directions with **timestamps
passing through unchanged**. Conventions match ADR-004 storage:

- `Instant` fields → epoch millis (`updatedAt`, `deletedAt`, `schedDep`, …).
- `LocalDate` fields → ISO-8601 strings (`"2026-09-20"`).
- Enums → their stable `storageValue` strings; unknown values parse to each enum's
  safe fallback, so a backup written by a newer app never crashes an older reader.
- Readers ignore unknown JSON keys and treat missing entity files as empty lists
  (forward-tolerant); a missing/unparseable `manifest.json` is a typed
  `CorruptedBackup`.

## Attachment bundling rules

- `driveFileId == null` (local-only) **and** the file exists at `localPath` at export
  time → bytes are bundled at `attachments/<attachmentId>` and the row's DTO carries
  `"bundled": true`.
- `driveFileId != null` → **never bundled**; the backup stores only the Drive file id.
- Tombstoned attachment rows are exported (row only, never bytes).

On import, a *winning* row (see merge below) with `bundled: true` has its bytes
extracted to `filesDir/attachments/<attachmentId>` and its `localPath` re-pointed at
the restored copy (paths are device-local by nature; `id`/`updatedAt`/tombstone are
preserved verbatim). Drive-id-only rows are restored **as-is**: path resolution is
deferred — this is the documented seam the Google milestone fills by downloading via
`driveFileId` and fixing `localPath` (local file → Drive download → placeholder
resolver ladder).

## Merge semantics (import is a MERGE, never a wipe)

Implemented once, generically, in `BackupMerger` and applied per entity type inside a
single Room transaction, writing through `BackupDao`'s **timestamp-preserving raw
upserts** (the one sanctioned place where `updatedAt` is NOT bumped on write):

| Case | Outcome | Counted as |
|---|---|---|
| Row only in backup | inserted verbatim (id, `updatedAt`, tombstone preserved) | `inserted` |
| Row only local | untouched | — |
| Same id, backup `updatedAt` newer | backup row replaces the local row **entirely**, including tombstone state | `updated` |
| Same id, local `updatedAt` newer **or equal** | local row kept | `skipped` |

Invariants:

- A newer backup tombstone deletes a local live row; a newer local edit survives an
  older backup tombstone.
- Ties keep local, so importing the same file twice is a no-op (idempotent by UUID).
- The merge never hard-deletes and never touches rows absent from the backup.

## Public API (the seam for the Google milestone)

`com.itsluminous.cleartravel.core.data.backup.BackupManager` (Hilt-bound singleton):

```kotlin
interface BackupManager {
    suspend fun exportToUri(uri: Uri): ExportResult          // SAF export + app-storage copy
    suspend fun exportLatestToAppStorage(): ExportResult     // filesDir/backups, pruned to 3
    suspend fun importPreview(uri: Uri): ImportPreview       // manifest only, no writes
    suspend fun importApply(uri: Uri): MergeSummary          // the LWW merge
    suspend fun latestLocalBackup(): LocalBackupInfo?        // "last backup" UI info
}
```

- Errors are typed: `BackupException.UnsupportedSchemaVersion`, `.CorruptedBackup`,
  `.Io`.
- `exportToUri` also refreshes the app-storage copy, so the newest backup is always
  available for the Drive upload queue.
- Drive integration (next milestone) composes these methods: upload the file produced
  by `exportLatestToAppStorage`, download a Drive backup to a local file/Uri and run
  it through `importPreview` → `importApply`. No engine changes required.

## Versioning policy

- Additive DTO fields with defaults → **no** version bump (readers ignore unknowns,
  writers' new fields default on old readers).
- Renames/removals/semantic changes → bump `schemaVersion`, add a migration in the
  reader, document here and in a new ADR.
