# feature:checklist

The Checklist tab: per-trip packing checklists with check/uncheck, add/remove/reorder
and a progress indicator, plus the preset system — checklists can start from built-in
or user-managed preset templates ("Domestic trip", "International", "Trek"), and
Settings → Manage presets supports create/edit/duplicate/delete. Presets are versioned
data (ADR-003) and editing a preset never mutates checklists already created from it;
preset → checklist instantiation is unit-tested. Fastest-win milestone after the
skeleton: pure local Room data, no network. The skeleton ships the tab route and empty
state.
