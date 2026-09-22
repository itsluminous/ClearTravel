# Clear Travel backup format (schema version 2)

The frozen external contract behind `BackupManager` (ADR-015, encrypted since
ADR-031). Everything here is versioned by `manifest.json`'s `schemaVersion`; readers
MUST reject files whose `schemaVersion` is greater than the newest they know (typed
`BackupException.UnsupportedSchemaVersion`) and MUST accept and migrate older ones.

## Outer container: the portable envelope (v2, ADR-031)

Since schema version 2 a backup file is **not** a bare ZIP but a `CTEB` portable
envelope — the ZIP described below, encrypted with a key derived from the **app
password** (never from the per-install Data Encryption Key, so a backup restores on
any install that knows the password):

```
"CTEB" (4) | version = 1 (1) | iterations int32 BE (4) | salt (16) | chunkSize int32 BE (4) | nonce (8)
| body: AES-256-GCM chunks of `chunkSize` plaintext bytes (last chunk shorter), each sealed with
        IV = nonce ‖ chunkIndex(4, BE) and AAD = header ‖ isLast(1); 16-byte tag per chunk
```

- Key = the last 32 bytes of `PBKDF2-HMAC-SHA256(password, salt, iterations, 64 bytes)`
  (the first 32 bytes are the vault's password KEK; both come from the single
  derivation the app performs at unlock). `iterations` is 210 000 today; readers
  honour whatever the header says.
- The salt is the writing vault's salt, so an install restoring **its own** backups
  decrypts silently. Any other salt — another device, a fresh install, a changed
  password — surfaces as typed `BackupException.PasswordRequired`; the caller passes
  the source password to `importPreview`/`importApply`, a wrong one is typed
  `WrongPassword` (GCM tag failure on the first chunk), and a proven key is remembered
  for the process so preview → apply prompts once.
- A file **without** the `CTEB` magic is read as a v1 plain ZIP, so pre-encryption
  exports stay importable forever. Writers never produce plain ZIPs any more.
- Bundled attachment/document bytes are **plaintext inside** the envelope (they are
  CTEF-encrypted on disk with the install's key; the exporter decrypts while
  bundling, the importer re-encrypts under the restoring install's key on extraction).
  Only the envelope protects them — by design, so the same password opens the whole
  backup.

## Inner container layout

Inside the envelope (or, for v1 files, the file itself) is a plain ZIP:

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
│   ├── train_coaches.json             # added ADR-022 — absent in older backups (read as empty)
│   ├── flight_journeys.json
│   ├── attachments.json
│   └── travel_documents.json          # added ADR-027 — absent in older backups (read as empty)
└── attachments/
    └── <attachmentId | documentId>    # raw file bytes, local-only attachments/documents only
```

Every entity file is a **full dump including tombstoned rows** — deletions must
replicate to other devices, so soft-deleted rows travel with their `deletedAt` set.

## manifest.json

```json
{
  "schemaVersion": 2,
  "appVersion": "0.1.0",
  "createdAt": 1789344000000,
  "entityCounts": { "trips": 4, "train_tickets": 2, "...": 0 }
}
```

- `schemaVersion` — format version this file was written with (currently `2`; `1`
  = plain ZIP with plaintext bundles, still readable).
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

### Travel documents (ADR-027)

`entities/travel_documents.json` rows (`TravelDocumentDto`: `id`, `name`, `type`
storage value, `filePath`, `mimeType`, `addedAt`, `expiryDate` ISO date or null,
`note`, `driveFileId`, `bundled`, sync fields) follow the SAME bundling rule: a
local-only row (`driveFileId == null` — every document today) whose file exists is
bundled at `attachments/<documentId>` (ids are UUIDs, so the two id spaces never
collide) with `"bundled": true`. On import a winning bundled row is extracted to
`filesDir/documents/<documentId>.<ext>` — the extension of the original `filePath` is
kept because the viewer decides PDF-vs-image by it — and `filePath` re-pointed. No
`schemaVersion` bump: pre-ADR-027 backups import with zero documents.

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
    suspend fun exportToUri(uri: Uri): ExportResult                                   // SAF export + app-storage copy
    suspend fun exportLatestToAppStorage(): ExportResult                              // filesDir/backups, pruned to 3
    suspend fun importPreview(uri: Uri, sourcePassword: CharArray? = null): ImportPreview  // manifest only, no writes
    suspend fun importApply(uri: Uri, sourcePassword: CharArray? = null): MergeSummary     // the LWW merge
    suspend fun latestLocalBackup(): LocalBackupInfo?                                 // "last backup" UI info
}
```

- Errors are typed: `BackupException.UnsupportedSchemaVersion`, `.CorruptedBackup`,
  `.Io`, and since v2 `.PasswordRequired` (envelope from another password/salt — retry
  with `sourcePassword`), `.WrongPassword`, `.Locked` (export attempted while the vault
  is locked — never reachable from the UI, which sits behind the app lock).
- `exportToUri` also refreshes the app-storage copy, so the newest backup is always
  available for the Drive upload queue.
- Drive integration (next milestone) composes these methods: upload the file produced
  by `exportLatestToAppStorage`, download a Drive backup to a local file/Uri and run
  it through `importPreview` → `importApply`. No engine changes required.

## Version history

| `schemaVersion` | Container | Bundled bytes | Introduced |
|---|---|---|---|
| 1 | plain ZIP | plaintext | ADR-015 |
| 2 | `CTEB` password envelope around the same ZIP | plaintext inside the envelope | ADR-031 |

Local backups written as v1 before the ADR-031 update are wrapped into envelopes by
the one-time storage migration (bytes unchanged, manifest still says 1 — imports as
v1 content through the v2 reader). Drive backups uploaded before the update stay plain
until pruned.

## Versioning policy

- Additive DTO fields with defaults → **no** version bump (readers ignore unknowns,
  writers' new fields default on old readers).
- Additive entity FILES (a new `entities/*.json`, e.g. `train_coaches.json` from
  ADR-022) → **no** version bump either: readers treat a missing entity file as an
  empty list, so a pre-ADR-022 backup imports with zero coaches and a newer backup
  imports into an older app minus the unknown file.
- Renames/removals/semantic changes → bump `schemaVersion`, add a migration in the
  reader, document here and in a new ADR.
