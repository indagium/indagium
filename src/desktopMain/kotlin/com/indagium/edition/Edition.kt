package com.indagium.edition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val FREE_MAX_SUITES = 1
const val FREE_MAX_CASES_PER_SUITE = 5

const val EDITION_PROPERTY = "indagium.edition"
const val EDITION_ENV = "INDAGIUM_EDITION"
const val DEV_PROPERTY = "indagium.dev"
const val PACKAGED_APP_PROPERTY = "jpackage.app-path"

enum class Edition(val label: String) {
    FREE("Free"),
    PREMIUM("Premium"),
    FRIENDS_FAMILY("Friends & Family"),
    UNLIMITED("Unlimited"),
    ;

    val limits: EditionLimits
        get() = when (this) {
            FREE -> EditionLimits.FREE
            PREMIUM, FRIENDS_FAMILY, UNLIMITED -> EditionLimits.UNLIMITED
        }

    companion object {
        /** Case-insensitive; `friends_family`, `friends-family` and `friends family` are all accepted. Null when unknown. */
        fun parse(raw: String?): Edition? {
            val normalized = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
            return entries.firstOrNull { it.name.lowercase() == normalized }
        }
    }
}

/** Null means unlimited. */
data class EditionLimits(val maxSuites: Int?, val maxCasesPerSuite: Int?) {
    companion object {
        val UNLIMITED = EditionLimits(maxSuites = null, maxCasesPerSuite = null)
        val FREE = EditionLimits(maxSuites = FREE_MAX_SUITES, maxCasesPerSuite = FREE_MAX_CASES_PER_SUITE)
    }
}

/**
 * Holds the active [Edition]. A plain instance (owned by AppState), not a global, so tests never leak
 * an edition into each other. The property/env lookups are injectable for the same reason.
 *
 * Builds default to [Edition.UNLIMITED]; [resolveStartup] can lower that with `-Dindagium.edition=free`
 * or `INDAGIUM_EDITION=free`, and [setForDev] can switch at runtime in an unpackaged or `-Dindagium.dev=true` run.
 */
class EditionService(
    initial: Edition = Edition.UNLIMITED,
    private val props: (String) -> String? = System::getProperty,
    private val env: (String) -> String? = System::getenv,
) {
    private val state = MutableStateFlow(initial)

    val current: StateFlow<Edition> = state.asStateFlow()

    fun limits(): EditionLimits = state.value.limits

    /**
     * Reads the edition from the system property, else the environment variable: the first non-blank
     * one wins, and an unrecognised value falls back to [Edition.UNLIMITED] rather than failing.
     * Publishes and returns the result.
     */
    fun resolveStartup(propLookup: (String) -> String? = props, envLookup: (String) -> String? = env): Edition {
        val raw = propLookup(EDITION_PROPERTY)?.takeIf { it.isNotBlank() } ?: envLookup(EDITION_ENV)?.takeIf { it.isNotBlank() }
        val resolved = Edition.parse(raw) ?: Edition.UNLIMITED
        state.value = resolved
        return resolved
    }

    /** True for an unpackaged run (no `jpackage.app-path`) or when `-Dindagium.dev=true` is set. */
    fun devSwitchAllowed(): Boolean = props(PACKAGED_APP_PROPERTY) == null || props(DEV_PROPERTY).equals("true", ignoreCase = true)

    /** Switches the edition for testing. Returns false, changing nothing, when [devSwitchAllowed] is false. */
    fun setForDev(edition: Edition): Boolean {
        if (!devSwitchAllowed()) return false
        state.value = edition
        return true
    }
}
