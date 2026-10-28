package com.indagium.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
            put("color", group.color.name)
            put("collapsed", group.collapsed)
            put("members", JsonArray(encodedMembers))
            put("textSize", group.textSize.name)
            put("icon", group.icon.name)
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
        val color = item.enumValueOrDefault("color", TabGroupColor.BLUE)
        val collapsed = item.booleanValue("collapsed") ?: false
        val textSize = item.enumValueOrDefault("textSize", TabGroupTextSize.MEDIUM)
        val icon = item.enumValueOrDefault("icon", TabGroupIcon.NONE)
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
            TabGroup(id, name, color, collapsed, it, textSize = textSize, icon = icon)
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

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
