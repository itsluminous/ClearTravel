package com.itsluminous.cleartravel.core.model

import java.util.UUID

/** ID generation for [SyncableEntity] rows: random UUID strings, generated client-side. */
object EntityIds {
    /** Returns a fresh random UUID string for a new entity row. */
    fun newId(): String = UUID.randomUUID().toString()

    /** True when [value] is a well-formed UUID string (validates imported/backup ids). */
    fun isValid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess && value.length == CANONICAL_UUID_LENGTH

    /** `UUID.fromString` accepts short forms; canonical ids are exactly 36 chars. */
    private const val CANONICAL_UUID_LENGTH = 36
}
