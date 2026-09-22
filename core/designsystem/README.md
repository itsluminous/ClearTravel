# core:designsystem

The single home for shared UI: the Clear Travel Material 3 theme (Material You dynamic
color on Android 12+, curated `#0B57D0` seed fallback below, typography with body text
≥16sp) and the sanctioned reusable composables. Feature modules must reuse these
components instead of rolling their own; anything used by two or more features
belongs here and nowhere else. This module never depends on data, database, security
or feature modules — the only outward seams are `DocumentFileReader` (installed by the
app shell with the decrypting reader) and `ShellChromeController` (`LocalShellChrome`,
provided by the app so the viewer's fullscreen can hide the bottom bar).

| Component | Use |
|---|---|
| `EmptyState` | every empty screen/tab |
| `ExplainableIcon` | every icon-only control (long-press explanation) |
| `ClearTravelCard`, `ClearTravelFab` | content cards, the per-tab primary add action |
| `ChipRow` | single-line, horizontally scrollable multi-chip rows (type presets) |
| `FullWidthFilterRow` + `FullWidthFilterChip` | the equal-width Active\|Archived filter on the trains, flights and trips lists (ADR-033) |
| `LocalDatePickerDialog` | the one Material `DatePickerDialog` wrapper over `LocalDate` (trains, flights, trips, documents) |
| `DateFormats` | shared localized date / timestamp formatting (`formatDate`, `formatTimestamp`) |
| `TextEditDialog`, `PasswordField` | rename dialogs; password entry with autofill content types |
| `rememberReorderableListState`, `ReorderHandle`, `Modifier.reorderableItem` | drag-to-reorder lists (ADR-021/029) |
| `DocumentViewerScreen` + `DocumentViewerState` | the shared image/PDF viewer: zoom, rotate, share, save-a-copy, paging, fullscreen, landscape rail (ADR-030/031/034) |
| `AutoShrinkText` | single-line text that shrinks to fit |

Tests: pure state/helper classes (`DocumentViewerStateTest`, `DocumentFilesTest`,
`ReorderMathTest`, `DateFormatsTest`) run as plain JVM unit tests.
