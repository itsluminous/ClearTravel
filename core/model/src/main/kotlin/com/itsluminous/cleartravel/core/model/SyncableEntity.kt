package com.itsluminous.cleartravel.core.model

import java.time.Instant

/**
 * Contract every persisted entity MUST satisfy (ADR-002): a client-generated UUID
 * [id], an [updatedAt] timestamp bumped on every write, and a soft-delete tombstone
 * [deletedAt]. These three fields drive the backup merge semantics (import is a
 * last-write-wins MERGE keyed by UUID, deletions honored via tombstones) and keep a
 * future sync layer possible without schema breakage.
 */
interface SyncableEntity {
    /** Client-generated UUID string — never a server or auto-increment id. */
    val id: String

    /** Bumped on EVERY write; drives last-write-wins conflict resolution on import. */
    val updatedAt: Instant

    /** Soft-delete tombstone: non-null means deleted (rows are never hard-deleted). */
    val deletedAt: Instant?
}
