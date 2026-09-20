# feature:menu

The Menu tab: Settings (theme light/dark/system, checklist preset manager, optional
status-API keys stored in encrypted DataStore, Google account connect/disconnect with
incremental Calendar/Drive toggles), Backup & Restore (export/import entry points,
merge-on-import), and the About screen (version from `BuildConfig.VERSION_NAME`,
source link). Settings subscreens stay nested inside this tab's graph so the bottom
bar keeps the Menu tab highlighted. Depends only on `core:*` modules; heavy work
(backup, Google) is delegated to `core:data`/`core:google`. The skeleton ships the tab
route and empty state.
