# feature:documents

The Documents tab (ADR-027, search ADR-033): passport, visa, ID, driving licence,
insurance, vaccination, ticket and other scans as **encrypted local files** with a
typed label, optional expiry date and note.

- `DocumentListScreen` — cards (type icon as an `ExplainableIcon`, name, type, expiry
  line with *Expires soon* ≤ 180 days / *Expired* flags from the pure
  `DocumentExpiry`), `EmptyState`, `ClearTravelFab` → system `OpenDocument` picker
  (`image/*`, `application/pdf`) → `DocumentDetailsDialog` (type preset chips, name,
  expiry via the shared `LocalDatePickerDialog`, note). Long-press / overflow → Open,
  Edit details, Delete (confirm). A bottom-docked live search box (`DocumentSearch`:
  case-insensitive over name + type label) appears once documents exist.
- `DocumentsViewModel` — copies the picked file FIRST through the `DocumentFileStore`
  seam (`LocalDocumentFileStore`: `ContentResolver` → `LocalFileCipher` into
  `filesDir/documents/<id>.<ext>`), then saves the `TravelDocument` row; delete
  soft-deletes then removes the file.
- `DocumentViewerRoute` — Room-observed (`Loading` / `Missing` / `Ready`) host of the
  shared `core:designsystem` `DocumentViewerScreen`.

Local-only by design: `driveFileId` is reserved, nothing drains the Drive queue yet
(documented follow-up in ADR-027). Bytes travel in backups (ADR-015/031). Strings are
`documents_*`; presets are the `TravelDocumentType` enum itself, so adding a type is
model + strings, never UI.

Tests: `DocumentsViewModelTest`, `DocumentExpiryTest` (expiry, presets, extensions),
`DocumentSearchTest`, `LocalDocumentFileStoreTest`. E2e: `DocumentsE2eTest` (list,
viewer controls, fullscreen, landscape rail, search).
