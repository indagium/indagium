package com.indagium.testing.device

/** Only decides when extra observation is useful; it never decides whether a user's step passes. */
internal fun needsUiStructure(vararg text: String?): Boolean =
    text.filterNotNull().any { UI_STRUCTURE_RELEVANCE.containsMatchIn(it) }

private val UI_STRUCTURE_RELEVANCE = Regex(
    "\\b(?:home screen|launcher|recent apps|recents|overview|picture.in.picture|floating|app drawer)\\b",
    RegexOption.IGNORE_CASE,
)

/** Device-derived structural evidence, independent of any agent's claim. Unavailability must never be interpreted as absence. */
internal fun uiStructureEvidence(read: UiTreeRead, capturedAtMs: Long): Map<String, Any?> = when (read) {
    is UiTreeRead.Unavailable -> mapOf("status" to "unavailable", "capturedAtMs" to capturedAtMs, "reason" to read.reason.take(MAX_REASON_CHARS))
    is UiTreeRead.Available -> {
        val tree = read.tree
        val nodes = tree.nodes.filter { it.resourceId.isNotBlank() }
        val overview = nodes.any { node ->
            node.packageName == tree.packageName && node.resourceId.substringAfter(":id/") in OVERVIEW_CONTAINER_IDS
        }
        // A launcher package alone cannot distinguish Home from Recents. Require a visible overview container and task card together.
        val taskCards = nodes.filter { node ->
            node.packageName == tree.packageName && node.resourceId.substringAfter(":id/").startsWith("task_view")
        }
        mapOf(
            "status" to "available",
            "capturedAtMs" to capturedAtMs,
            "package" to tree.packageName.take(MAX_ID_CHARS),
            "surface" to if (overview && taskCards.isNotEmpty()) "recent_apps" else "unclassified",
            "screenWidth" to tree.screenWidth,
            "screenHeight" to tree.screenHeight,
            "coordinateSpace" to "physical-device-pixels",
            "truncated" to tree.truncated,
            "elements" to (taskCards + nodes.filter { it !in taskCards }).take(MAX_STRUCTURE_NODES).map { node ->
                mapOf("resourceId" to node.resourceId.take(MAX_ID_CHARS), "bounds" to listOf(node.left, node.top, node.right, node.bottom))
            },
        )
    }
}

private val OVERVIEW_CONTAINER_IDS = setOf("overview_panel", "recents_view")
private const val MAX_STRUCTURE_NODES = 24
private const val MAX_ID_CHARS = 160
private const val MAX_REASON_CHARS = 200
