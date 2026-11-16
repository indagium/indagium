package com.indagium.ui

import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogTab

/** Returns null for legacy tabs that predate profile snapshots, preserving their existing controls. */
internal fun LogTab.profileCapability(capability: LogCapability): LogCapabilityStatus? {
    return profileCapability(capability, logCapabilities)
}

/** Profile-aware override used when the caller has a freshly resolved capability snapshot. */
internal fun LogTab.profileCapability(
    capability: LogCapability,
    capabilities: Map<LogCapability, LogCapabilityStatus>,
): LogCapabilityStatus? {
    if (logFormatProfileId == null && logFormatProfileSnapshot == null) return null
    return capabilities[capability] ?: LogCapabilityStatus(
        CapabilityAvailability.UNAVAILABLE,
        "This format has no capability details for ${capability.name.lowercase().replace('_', ' ')}.",
    )
}

/** Partial coverage remains usable; only explicit unavailability blocks an action. */
internal fun LogTab.canUseProfileCapability(capability: LogCapability): Boolean =
    profileCapability(capability)?.availability != CapabilityAvailability.UNAVAILABLE

internal fun LogTab.canUseProfileCapability(
    capability: LogCapability,
    capabilities: Map<LogCapability, LogCapabilityStatus>,
): Boolean = profileCapability(capability, capabilities)?.availability != CapabilityAvailability.UNAVAILABLE

/** Explains only a blocked action, so callers can keep the expected control visible when appropriate. */
internal fun LogTab.profileCapabilityUnavailableReason(capability: LogCapability): String? =
    profileCapability(capability)?.takeIf { it.availability == CapabilityAvailability.UNAVAILABLE }?.reason

internal fun LogTab.profileCapabilityUnavailableReason(
    capability: LogCapability,
    capabilities: Map<LogCapability, LogCapabilityStatus>,
): String? = profileCapability(capability, capabilities)
    ?.takeIf { it.availability == CapabilityAvailability.UNAVAILABLE }?.reason

/** Provides the coverage reason for controls that remain available with partial data. */
internal fun LogTab.profileCapabilityPartialReason(capability: LogCapability): String? =
    profileCapability(capability)?.takeIf { it.availability == CapabilityAvailability.PARTIAL }?.reason

internal fun LogTab.profileCapabilityPartialReason(
    capability: LogCapability,
    capabilities: Map<LogCapability, LogCapabilityStatus>,
): String? = profileCapability(capability, capabilities)
    ?.takeIf { it.availability == CapabilityAvailability.PARTIAL }?.reason

/** Resolves all profile and runtime overlays once from the shared state helper. */
internal fun AppState.logCapabilitiesForTab(tab: LogTab): Map<LogCapability, LogCapabilityStatus> {
    return this.logCapabilitiesForTab(tab.id)
}
