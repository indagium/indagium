package com.indagium.testing.store

import com.indagium.testing.limits.LimitDecision

/** Outcome of a [TestLibraryStore] operation. Operations never throw for an expected problem. */
sealed interface StoreResult<out T> {
    /** [warnings] are for the user (for example "2 cases arrive locked"); the operation did succeed. */
    data class Ok<T>(val value: T, val warnings: List<String> = emptyList()) : StoreResult<T>

    /** The edition limits refused the operation; nothing changed. */
    data class LimitReached(val decision: LimitDecision.Refused) : StoreResult<Nothing>

    data class NotFound(val kind: String, val id: String) : StoreResult<Nothing> {
        val message: String get() = "No $kind with id '$id'."
    }

    data class Invalid(val reason: String) : StoreResult<Nothing>
}

fun <T> StoreResult<T>.valueOrNull(): T? = (this as? StoreResult.Ok<T>)?.value
