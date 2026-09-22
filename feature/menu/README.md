# feature:menu

The Menu tab (nested NavHost, ADR-010): **Settings** — theme (light/dark/system),
**Security** (change password = re-wrap only, biometric unlock toggle, lock timing incl.
the `FLAG_SECURE` *Immediately* option; ADR-031/034/036), Manage presets (built-ins
editable, delete guarded; ADR-021), Google account connect/disconnect with the
incremental Calendar / Drive uploads / Drive backup toggles (ADR-016);
**Backup & Restore** — automatic-backup schedule Off/Daily/Weekly/Monthly (ADR-037),
export via SAF, import with preview-then-confirm (counts incl.
travel documents), local + Drive restore with the source-password dialog for foreign
envelopes and the fresh-install Drive prompt (ADR-015/031/032); **About**. Heavy work is
delegated to `core:data` (`BackupManager`) and `core:google`; this module holds the
ViewModels and screens only. Strings are `menu_*`.
