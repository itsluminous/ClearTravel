# core:database

Room persistence: the `ClearTravelDatabase` class, entities, DAOs, and type
converters. Room is the single source of truth for the whole app (offline-first) —
every screen renders from data observed here via Flow, and status fetches write back
here. Every entity carries a UUID `id`, an `updated_at` timestamp, and a soft-delete
tombstone (`deleted_at`) per ADR-002; DAOs are tested with Robolectric against the
in-memory database from `core:testing`. Only `core:data` repositories may touch DAOs —
UI and feature modules never import this module's DAOs directly. The concrete
`@Database` class lands with the first entity (Checklist milestone); this skeleton
ships the module wiring and shared constants.
