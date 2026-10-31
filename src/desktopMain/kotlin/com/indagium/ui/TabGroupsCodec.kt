package com.indagium.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** The fixed palette older builds stored by name in a group's `color` field. Kept only to read those
 * files and to write the nearest name back, so an older build still opens a file saved by this one. */
internal enum class LegacyTabGroupColor(val argb: Int) {
    BLUE(LEGACY_BLUE),
    GREEN(LEGACY_GREEN),
    YELLOW(LEGACY_YELLOW),
    ORANGE(LEGACY_ORANGE),
    RED(LEGACY_RED),
    PURPLE(LEGACY_PURPLE),
    PINK(LEGACY_PINK),
    GRAY(LEGACY_GRAY),
    ;

    companion object {
        /** The legacy name closest to [argb] by RGB distance. */
        fun nearest(argb: Int): LegacyTabGroupColor = entries.minBy { distance(it.argb, argb) }

        private fun distance(a: Int, b: Int): Int {
            fun channel(value: Int, shift: Int) = (value shr shift) and CHANNEL_MASK
            return listOf(RED_SHIFT, GREEN_SHIFT, BLUE_SHIFT).sumOf { shift ->
                val delta = channel(a, shift) - channel(b, shift)
                abs(delta * delta)
            }
        }
    }
}

private const val LEGACY_BLUE = 0xFF3B82F6.toInt()
private const val LEGACY_GREEN = 0xFF22A06B.toInt()
private const val LEGACY_YELLOW = 0xFFE3A008.toInt()
private const val LEGACY_ORANGE = 0xFFF97316.toInt()
private const val LEGACY_RED = 0xFFEF4444.toInt()
private const val LEGACY_PURPLE = 0xFF8B5CF6.toInt()
private const val LEGACY_PINK = 0xFFEC4899.toInt()
private const val LEGACY_GRAY = 0xFF8B949E.toInt()
private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 0

/** Keyed JSON payload for strip groups. Log ids are stable in their own autosave shells; diagram
 * members use their durable library item id because Seq3Session ids are regenerated on restore. */
internal fun AppState.tabGroupsJson(): String {
    val order = reconciledTabOrder()
    val libraryItemIdBySessionId = seq3Sessions.sessions.associate { it.id to it.libraryItemId }
    val encodedGroups = tabGroups.mapNotNull { group ->
        val encodedMembers = order.asSequence()
            .filter { it in group.members }
            .mapNotNull { ref ->
                when (ref) {
                    is TabRef.Log -> tabs.firstOrNull { it.id == ref.tabId && !it.isCaptureLauncher }
                        ?.let { tabGroupMemberJson("L", ref.tabId) }
                    is TabRef.Diagram -> libraryItemIdBySessionId[ref.sessionId]
                        ?.let { libraryId -> tabGroupMemberJson("D", libraryId) }
                    TabRef.Tests -> null
                }
            }
            .toList()
        if (encodedMembers.isEmpty()) return@mapNotNull null
        buildJsonObject {
            put("id", group.id)
            put("name", group.name)
            put("colorArgb", group.colorArgb)
            // Older builds read the palette name and know nothing of colorArgb.
            put("color", LegacyTabGroupColor.nearest(group.colorArgb).name)
            put("collapsed", group.collapsed)
            put("members", JsonArray(encodedMembers))
            put("icon", group.icon.name)
            put("tintMembers", group.tintMembers)
        }
    }
    return buildJsonObject { put("groups", JsonArray(encodedGroups)) }.toString()
}

private fun tabGroupMemberJson(kind: String, id: String): JsonElement = buildJsonObject {
    put("kind", kind)
    put("id", id)
}

/** Resolves groups only after log shells and diagram library items have been restored. Bad or stale
 * refs are dropped, the first membership wins if a ref appears more than once, and empty groups are
 * omitted. The final commit also makes every surviving group's strip run contiguous. */
internal fun AppState.restoreTabGroups(json: String) {
    val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        ?: return
    val serializedGroups = (root["groups"] as? JsonArray).orEmpty()
    val liveLogById = tabs.filterNot { it.isCaptureLauncher }.associateBy { it.id }
    val sessionIdByLibraryItemId = seq3Sessions.sessions.mapNotNull { session ->
        session.libraryItemId?.let { it to session.id }
    }.toMap()
    val seenGroupIds = HashSet<String>()
    val seenMembers = HashSet<TabRef>()
    val restoredGroups = serializedGroups.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.stringValue("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (!seenGroupIds.add(id)) return@mapNotNull null
        val name = item.stringValue("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        // colorArgb wins; a file from an older build has only the palette name. The old `textSize` is ignored.
        val colorArgb = item.intValue("colorArgb")
            ?: item.enumValueOrDefault("color", LegacyTabGroupColor.BLUE).argb
        val collapsed = item.booleanValue("collapsed") ?: false
        val icon = item.enumValueOrDefault("icon", TabGroupIcon.NONE)
        // Appended after the first release: a file without the key (or with an unreadable one) means tinted, as before.
        val tintMembers = item.booleanValue("tintMembers") ?: true
        val memberElements = item["members"] as? JsonArray ?: return@mapNotNull null
        val members = memberElements.mapNotNull { memberElement ->
            val member = memberElement as? JsonObject ?: return@mapNotNull null
            val stableId = member.stringValue("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ref = when (member.stringValue("kind")) {
                "L" -> liveLogById[stableId]?.let { TabRef.Log(it.id) }
                "D" -> sessionIdByLibraryItemId[stableId]?.let(TabRef::Diagram)
                else -> null
            } ?: return@mapNotNull null
            ref.takeIf { seenMembers.add(it) }
        }.distinct()
        members.takeIf { it.isNotEmpty() }?.let {
            TabGroup(id, name, colorArgb, collapsed, it, icon = icon, tintMembers = tintMembers)
        }
    }
    synchronized(stateLock) {
        tabGroups = restoredGroups
        commitTabOrderLocked(tabOrder)
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private inline fun <reified E : Enum<E>> JsonObject.enumValueOrDefault(key: String, default: E): E =
    stringValue(key)?.let { value -> enumValues<E>().firstOrNull { it.name == value } } ?: default

private fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
