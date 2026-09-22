# feature:checklist

The Checklist tab: per-trip (or standalone) packing checklists with check/uncheck,
add, per-row edit/delete, drag-to-reorder and a packed-count progress line, built from
preset templates — append any number of presets onto one checklist, duplicates by text
are skipped (ADR-006). Checklist detail is a full screen inside the tab's nested
NavHost (ADR-010); reordering uses `core:designsystem`'s reorderable helpers and rename
uses the shared `TextEditDialog` (ADR-021). Built-in presets are seeded from
`core:data`'s versioned JSON asset and are fully editable; the preset manager lives in
`feature:menu`. Pure local Room data, no network; strings are `checklist_*`.
