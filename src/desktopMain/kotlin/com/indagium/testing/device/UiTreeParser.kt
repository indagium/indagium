package com.indagium.testing.device

// Parses the XML `uiautomator dump` writes into the compact node list a test agent reads. A regular-expression
// scan, not an XML parser, on purpose: the dump is device-produced text of unknown quality (a stray invalid
// character would fail a strict parser for the whole screen) and a scan can never expand entities or fetch
// anything. Only `<node .../>` attribute lists are read; values are unescaped by hand.

const val MAX_UI_TREE_NODES = 250
const val MAX_UI_NODE_TEXT_CHARS = 120
private const val ELLIPSIS = "…"

/** One screen element, bounds in the coordinates the dump used (physical device pixels). */
data class UiNode(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** [screenWidth]/[screenHeight] come from the root node's bounds; [nodes] holds at most [MAX_UI_TREE_NODES] relevant ones. */
data class UiTreeParse(
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<UiNode>,
    val totalNodes: Int,
    val truncated: Boolean,
)

private val NODE_TAG = Regex("<node((?:\\s+[\\w:.-]+=\"[^\"]*\")*)\\s*/?>")
private val ATTRIBUTE = Regex("([\\w:.-]+)=\"([^\"]*)\"")
private val BOUNDS = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|\\d+);")
private const val HEX_RADIX = 16
private const val DECIMAL_RADIX = 10

/** Null when [output] holds no hierarchy at all (uiautomator failed or printed only an error). */
fun parseUiAutomatorDump(output: String, maxNodes: Int = MAX_UI_TREE_NODES): UiTreeParse? {
    val start = output.indexOf("<hierarchy").takeIf { it >= 0 } ?: return null
    val end = output.indexOf("</hierarchy>", start).let { if (it < 0) output.length else it }
    var screenWidth = 0
    var screenHeight = 0
    var total = 0
    val kept = ArrayList<UiNode>()
    var truncated = false
    NODE_TAG.findAll(output.substring(start, end)).forEach { tag ->
        val attrs = ATTRIBUTE.findAll(tag.groupValues[1]).associate { it.groupValues[1] to unescapeXml(it.groupValues[2]) }
        val node = nodeFrom(attrs) ?: return@forEach
        total++
        if (total == 1) {
            screenWidth = node.right
            screenHeight = node.bottom
        }
        if (!isRelevant(node)) return@forEach
        if (kept.size < maxNodes) kept += node else truncated = true
    }
    if (total == 0) return null
    return UiTreeParse(screenWidth, screenHeight, kept, total, truncated)
}

private fun nodeFrom(attrs: Map<String, String>): UiNode? {
    val match = BOUNDS.matchEntire(attrs["bounds"].orEmpty()) ?: return null
    val bounds = match.groupValues.drop(1).map(String::toInt)
    val password = attrs["password"] == "true"
    return UiNode(
        left = bounds[0],
        top = bounds[1],
        right = bounds[2],
        bottom = bounds[3],
        text = if (password) "" else clip(attrs["text"].orEmpty()),
        contentDesc = clip(attrs["content-desc"].orEmpty()),
        resourceId = attrs["resource-id"].orEmpty(),
        className = attrs["class"].orEmpty().substringAfterLast('.'),
        clickable = attrs["clickable"] == "true",
        enabled = attrs["enabled"] != "false",
        scrollable = attrs["scrollable"] == "true",
    )
}

/** Containers that say nothing and do nothing are left out; an element with a label, an id or an action stays. */
private fun isRelevant(node: UiNode): Boolean {
    if (node.right <= node.left || node.bottom <= node.top) return false
    return node.text.isNotEmpty() || node.contentDesc.isNotEmpty() || node.resourceId.isNotEmpty() || node.clickable || node.scrollable
}

private fun clip(value: String): String =
    if (value.length <= MAX_UI_NODE_TEXT_CHARS) value else value.take(MAX_UI_NODE_TEXT_CHARS) + ELLIPSIS

private fun unescapeXml(value: String): String {
    if (!value.contains('&')) return value
    val numeric = NUMERIC_ENTITY.replace(value) { match ->
        val body = match.groupValues[1]
        val code = if (body.startsWith('x')) body.drop(1).toIntOrNull(HEX_RADIX) else body.toIntOrNull(DECIMAL_RADIX)
        if (code != null && Character.isValidCodePoint(code) && code != 0) String(Character.toChars(code)) else ""
    }
    return numeric.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
