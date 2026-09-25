package com.indagium.capture

import com.indagium.debug.Json
import com.indagium.debug.str

// ── On-disk convention for a capture marker note ────────────────────────────────────────────────
//
// Same idea as `diagram3/Seq3Codec.kt` (see that file's own header for the fuller rationale): a
// "Mark issue" press is stored as an ORDINARY AnnBlock.Note whose text is a header comment
// (invisible in plain-text/Markdown rendering) followed by a plain heading line:
//
//   <!-- indagium:marker v1 {"id":"m3","at":32412,"rows":[1841,2043],"pre":5000,"post":5000,
//        "video":26100,"shot":"screenshots/marker-3.png","label":"Issue detected here"} -->
//   ## ▲ Marker 3 — Issue detected here
//
// This means no new AnnBlock variant and no new autosave positional field: the marker survives
// every .ann round-trip, every export, and every existing Notes UI code path for free — a marker
// note is edited/deleted exactly like a note a human typed by hand.
//
// The in-memory marker LIST is always DERIVED (never itself persisted):
//   tab.annotations.blocks.filterIsInstance<AnnBlock.Note>().mapNotNull { parseMarkerHeader(it.text) }
// — see CaptureStrip.kt's CaptureMarkerList.
//
// [parseMarkerHeader] must never throw and must return null for anything that isn't a complete,
// version-supported header at the START of the text — a plain user-written Note, a header with
// garbled/truncated JSON, or one stamped with a future version this build doesn't understand all
// degrade to "this is just a normal text note", exactly like Seq3Codec's contract. Unknown JSON
// keys are silently ignored (forward compatible with a future field), and every field this build
// DOES know about is read defensively — a malformed value for one field never fails the others.

private const val MARKER_HEAD = "<!-- indagium:marker "
private const val MARKER_TAIL = " -->"
private const val MARKER_VERSION = "v1"

// A marker header is a handful of scalars, nowhere near diagram3's multi-KB document — bounded
// generously above any real value (a hand-edited/corrupted file is the only way to exceed this).
internal const val MAX_MARKER_HEADER_CHARS = 8 * 1024
private const val MAX_MARKER_STRING_CHARS = 2 * 1024

/**
 * One "Mark issue" press. [firstOrdinal]/[lastOrdinal] are source capture-row ordinals (1-based,
 * matching [com.indagium.model.LogEntry.id] for a tab parsed from this same capture — see
 * LogParser.parseLogcatLines's identical separator-skipping numbering), null while the trailing
 * window is still being collected (see AppState.markIssue). [videoMs] is the position in the
 * attached recording at press time, or null when no video is attached/available. [screenshotPath]
 * is a forward-looking hint for Phase 4's archive export (`screenshots/marker-N.png`); nothing in
 * this phase resolves it to an actual file — the screenshot itself lives inline as bytes on the
 * neighboring AnnBlock.Image, not on disk under this path. [noteBlockId] is NOT part of the header
 * (it's the enclosing AnnBlock.Note's own id, known only to whichever caller is holding that
 * block) — callers that derive a marker from `tab.annotations.blocks` fill it in via `.copy(...)`
 * after parsing; a marker built directly from [markerHeader] never needs it.
 */
data class CaptureMarker(
    val id: String,
    val elapsedMs: Long,
    val firstOrdinal: Int?,
    val lastOrdinal: Int?,
    val videoMs: Long?,
    val label: String,
    val preMs: Long,
    val postMs: Long,
    val screenshotPath: String?,
    val noteBlockId: String? = null,
)

/** Encodes [marker] as the header comment alone (no trailing newline) — the caller composes the
 *  full note text by appending its own heading line, since only [AppState.markIssue] knows the
 *  marker's display ordinal ("Marker 3") at write time. */
fun markerHeader(marker: CaptureMarker): String {
    // LinkedHashMap (what mapOf(...) returns for >1 entries) preserves insertion order, which
    // keeps the encoded header's field order stable and matches this file's own doc comment above.
    val header = mapOf(
        "id" to marker.id,
        "at" to marker.elapsedMs,
        "rows" to if (marker.firstOrdinal != null && marker.lastOrdinal != null) {
            listOf(marker.firstOrdinal, marker.lastOrdinal)
        } else {
            null
        },
        "pre" to marker.preMs,
        "post" to marker.postMs,
        "video" to marker.videoMs,
        "shot" to marker.screenshotPath,
        "label" to marker.label,
    )
    return "$MARKER_HEAD$MARKER_VERSION ${Json.encode(header)}$MARKER_TAIL"
}

/** The heading line under a marker header — "## ▲ Marker 3 — Issue detected here" — pulled out
 *  so [AppState.markIssue] (which composes the full note text) and any future re-render of an
 *  existing marker's heading (e.g. after the trailing rescan updates [CaptureMarker.label]) share
 *  exactly one format. */
fun markerHeadingLine(displayOrdinal: Int, label: String): String = "## ▲ Marker $displayOrdinal — $label"

/**
 * Parses a note produced by [markerHeader] (plus any heading/body text after it — only the header
 * is read). Returns null for anything that isn't a complete, version-supported marker header at
 * the very start of [noteText], per this file's header doc. Never throws.
 */
@Suppress("ReturnCount")
fun parseMarkerHeader(noteText: String): CaptureMarker? {
    val trimmed = noteText.trimStart()
    if (!trimmed.startsWith(MARKER_HEAD)) return null
    val afterHead = trimmed.substring(MARKER_HEAD.length)
    val spaceIdx = afterHead.indexOf(' ')
    if (spaceIdx <= 0) return null
    val version = afterHead.substring(0, spaceIdx)
    if (version != MARKER_VERSION) return null
    val rest = afterHead.substring(spaceIdx + 1)
    val tailIdx = rest.indexOf(MARKER_TAIL)
    if (tailIdx < 0) return null
    val jsonText = rest.substring(0, tailIdx)
    if (jsonText.length > MAX_MARKER_HEADER_CHARS) return null
    @Suppress("UNCHECKED_CAST")
    val map = runCatching { Json.decode(jsonText) }.getOrNull() as? Map<String, Any?> ?: return null

    val id = boundedString(map.str("id")) ?: return null
    val at = map.longValue("at") ?: return null
    val pre = map.longValue("pre") ?: return null
    val post = map.longValue("post") ?: return null
    val rows = (map["rows"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
    val label = boundedString(map.str("label")) ?: ""
    return CaptureMarker(
        id = id,
        elapsedMs = at,
        firstOrdinal = rows?.getOrNull(0),
        lastOrdinal = rows?.getOrNull(1),
        videoMs = map.longValue("video"),
        label = label,
        preMs = pre,
        postMs = post,
        screenshotPath = boundedString(map.str("shot")),
    )
}

private fun boundedString(value: String?): String? = value?.takeIf { it.length <= MAX_MARKER_STRING_CHARS }

// Mirrors Seq3Codec's own `(map[...] as? Number)?.toLong()` idiom: this hand-rolled Json (unlike
// kotlinx.serialization, used elsewhere in this package) only ever produces Int or Double numbers
// (see Json.kt's parseNumber), so every Long-valued field decodes through Number rather than a
// dedicated `.long(key)` accessor.
private fun Map<String, Any?>.longValue(key: String): Long? = (this[key] as? Number)?.toLong()
