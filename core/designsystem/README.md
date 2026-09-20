# core:designsystem

The single home for shared UI: the ClearTravel Material 3 theme (Material You dynamic
color on Android 12+, curated `#0B57D0` seed fallback below, typography with body text
≥16sp) and the sanctioned reusable composables — `EmptyState`, `ChipRow` (single-line
scrollable filter pills), `ExplainableIcon` (icon-only control with a long-press
explanation toast), `ClearTravelCard`, and `ClearTravelFab`. Feature modules must reuse
these components instead of rolling their own; anything used by two or more features
belongs here and nowhere else. This module never depends on data, database, or feature
modules.
