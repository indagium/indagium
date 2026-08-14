@file:Suppress("TooManyFunctions")

package com.indagium.diagram3

import com.indagium.debug.Json
import com.indagium.debug.int
import com.indagium.debug.intList
import com.indagium.debug.mapList
import com.indagium.debug.str
import com.indagium.debug.strList
import java.security.MessageDigest

// ── On-disk convention for a v3 diagram note ────────────────────────────────────────────────
//
// Same idea as `diagram/DiagramSpecCodec.kt` (see that file's own header): a generated diagram is
// stored as an ORDINARY AnnBlock.Note whose text is a header comment (invisible in plain-text/
// Markdown rendering) followed by a fenced code block (which actually renders in GitHub/GitLab/Jira
// Mermaid plugins):
//
//   <!-- indagium:diagram3 v1 {"dialect":"mermaid","sourceHash":"...","document":{...}} -->
//   ```mermaid
//   sequenceDiagram
//     ...
//   ```
//
// What's DELIBERATELY different from the v1 codec, per this phase's brief: this reimplements the
// BOUNDS DISCIPLINE (`DiagramSpecCodec.kt:42-73`) and the HASH/SNAPSHOT RULES (`:288-296`) against
// the much smaller v3 model — it does not port the v1-v5 migration machinery, because v3 never
// reads a legacy document (there is exactly one version constant below, not five). The header
// carries the whole [Seq3Document] (not a separate "spec" + "model" pair like v1's
// SeqDiagramSpec/SeqDiagram split) because v3's document already IS both the generation inputs and
// the current edited state — see Seq3Model.kt's own header for why there's no `editorVersion`
// discriminator to carry either.
//
// [parseSeq3Note] must never throw and must return null for anything that isn't a complete,
// version-supported header immediately followed by a matching fence — a plain user-written Note, a
// header with garbled/truncated JSON, one stamped with a future version this build doesn't
// understand, or one whose declared list sizes exceed this file's bounds. Every one of those
// degrades to "this is just a normal text note", exactly like the v1 codec's own contract.

private const val MARKER_HEAD = "<!-- indagium:diagram3 "
private const val MARKER_TAIL = " -->"
private const val SEQ3_VERSION = "v1"

// Bounds mirror `DiagramSpecCodec.kt`'s own constants (`MAX_DIAGRAM_MESSAGES`,
// `MAX_CODEC_HEADER_CHARS`, etc.) — see that file's header for why these exist: notes can be
// imported from arbitrary .ann/case-library files, so decode must stay well below a pathological
// renderer/generator allocation.
private const val MAX_SEQ3_LIFELINES = 128
private const val MAX_SEQ3_MESSAGES = 5_000
private const val MAX_SEQ3_OCCURRENCES_PER_MESSAGE = 5_000
private const val MAX_SEQ3_CAPTURES_PER_MATCH = 32
private const val MAX_SEQ3_FRAGMENTS = 128
private const val MAX_SEQ3_NOTES = 400
private const val MAX_SEQ3_MESSAGE_IDS_PER_FRAGMENT = 5_000
private const val MAX_SEQ3_HEADER_CHARS = 512 * 1024
private const val MAX_SEQ3_SOURCE_CHARS = 2 * 1024 * 1024
private const val MAX_SEQ3_STRING_CHARS = 16 * 1024

private fun fenceLanguage(dialect: Seq3Dialect): String = when (dialect) {
    Seq3Dialect.MERMAID -> "mermaid"
    Seq3Dialect.PLANTUML -> "plantuml"
}

/** Result of a successful [parseSeq3Note]. */
data class ParsedSeq3(
    val document: Seq3Document,
    val dialect: Seq3Dialect,
    /** The fenced body's exact text, verbatim. */
    val source: String,
    val sourceHash: String,
    /** False when the header's declared hash no longer matches [source] — e.g. the note was hand-
     *  edited in the fence, or written by a build that computed the hash differently. [document]
     *  is still returned in that case (it is what the header actually says), but [warning] is set
     *  so a caller can surface "this diagram's source has drifted from its model" instead of
     *  silently trusting a document that may no longer describe the visible text. */
    val sourceHashMatches: Boolean,
    val warning: String? = null,
    /** Index into the ORIGINAL text one past the header comment's closing `-->` (and its trailing
     *  newline, if any) — [stripSeq3NoteHeader]'s whole implementation is `text.substring(this)`. */
    val headerEndIndex: Int,
    val fenceRange: IntRange,
    /** Display label shown above the rendered/previewed note — the v3 counterpart of
     *  `diagram.DiagramAttachmentMetadata.caption`, flattened directly onto the header rather than
     *  nested under a separate attachment object (v3 has no snapshot/link attachment split — see
     *  this file's own header). Empty for a note that never had one set. */
    val caption: String = "",
    /** Which representation `ui/AnnotationPanel.kt`'s note card should keep beside the fenced
     *  source: a rasterized PNG or the source alone. Missing/unrecognized decodes as [DiagramExportMode.IMAGE],
     *  matching the v1/v2 codec's own "missing metadata means IMAGE" default. */
    val exportMode: DiagramExportMode = DiagramExportMode.IMAGE,
)

/** Stable lowercase hexadecimal SHA-256 of [source] — the exact fenced body, never the header. */
fun seq3SourceHash(source: String): String = MessageDigest.getInstance("SHA-256")
    .digest(source.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * Encodes [document] as a complete diagram note: the header comment (carrying [document] itself)
 * followed by a fenced [dialect] code block. Never throws; a document whose serialized form would
 * exceed this file's own bounds still encodes (bounds are enforced on DECODE — see this file's
 * header doc and `DiagramSpecCodec.kt`'s own note on why: the codec must never be the reason a
 * document a user is actively editing fails to SAVE, only the reason an untrusted file fails to
 * LOAD back in).
 */
fun encodeSeq3Note(
    document: Seq3Document,
    dialect: Seq3Dialect = Seq3Dialect.MERMAID,
    caption: String = "",
    exportMode: DiagramExportMode = DiagramExportMode.IMAGE,
    // Verbatim fenced-body override for a metadata-only rewrite (updateSeq3NoteCaption/
    // updateSeq3NoteExportMode below): re-deriving the source from [document] on every such edit
    // would silently "fix" a hand-edited/drifted fence back into agreement, exactly the failure
    // `diagram.updateDiagramAttachment` avoided by re-encoding from its own parsed [source] rather
    // than regenerating it. Null (the normal generate/confirm path) derives from [document] as before.
    sourceOverride: String? = null,
): String {
    // Normalized (no trailing newline) BEFORE hashing/embedding, then the closing fence's own "\n"
    // is added unconditionally below — mirrors `DiagramSpecCodec.encodeDiagramNote`'s identical
    // `source.trimEnd('\n')` normalization, which exists for exactly this reason: emitters always
    // end their last line with '\n', and without normalizing first, that trailing newline and the
    // fence's OWN required newline overlap, making the fenced body's end position ambiguous to
    // reparse (see [parseSeq3Note]'s symmetric extraction below).
    val source = (sourceOverride ?: document.toSource(dialect)).trimEnd('\n')
    val header = mapOf(
        "dialect" to dialect.name.lowercase(),
        "sourceHash" to seq3SourceHash(source),
        "caption" to caption,
        "exportMode" to exportMode.name,
        "document" to documentToMap(document),
    )
    return buildString {
        append(MARKER_HEAD).append(SEQ3_VERSION).append(' ').append(Json.encode(header)).append(MARKER_TAIL).append('\n')
        append("```").append(fenceLanguage(dialect)).append('\n')
        append(source).append('\n')
        append("```\n")
    }
}

/** Parses a note produced by [encodeSeq3Note]. See this file's header for the exact "return null,
 *  never throw" contract. */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun parseSeq3Note(text: String): ParsedSeq3? {
    val trimmed = text.trimStart()
    val leadingWs = text.length - trimmed.length
    if (!trimmed.startsWith(MARKER_HEAD)) return null
    val afterHead = trimmed.substring(MARKER_HEAD.length)
    val spaceIdx = afterHead.indexOf(' ')
    if (spaceIdx <= 0) return null
    val version = afterHead.substring(0, spaceIdx)
    if (version != SEQ3_VERSION) return null
    val rest = afterHead.substring(spaceIdx + 1)
    val tailIdx = rest.indexOf(MARKER_TAIL)
    if (tailIdx < 0) return null
    val jsonText = rest.substring(0, tailIdx)
    if (jsonText.length > MAX_SEQ3_HEADER_CHARS) return null
    @Suppress("UNCHECKED_CAST")
    val map = runCatching { Json.decode(jsonText) }.getOrNull() as? Map<String, Any?> ?: return null

    val dialect = when (map.str("dialect")) {
        "plantuml" -> Seq3Dialect.PLANTUML
        else -> Seq3Dialect.MERMAID
    }
    val declaredHash = map.str("sourceHash") ?: return null
    if (!declaredHash.matches(HEX64)) return null
    val documentMap = subMap(map, "document") ?: return null
    val document = documentFromMap(documentMap) ?: return null
    val caption = boundedString(map.str("caption")) ?: ""
    val exportMode = enumFromName(map.str("exportMode"), DiagramExportMode.IMAGE)

    val markerLen = MARKER_HEAD.length + spaceIdx + 1 + tailIdx + MARKER_TAIL.length
    val headerEndIndex = leadingWs + markerLen
    var cursor = headerEndIndex
    while (cursor < text.length && (text[cursor] == '\n' || text[cursor] == '\r')) cursor++

    val openFence = "```${fenceLanguage(dialect)}"
    if (!text.startsWith(openFence, cursor)) return null
    val fenceOpenStart = cursor
    val afterOpenLine = text.indexOf('\n', fenceOpenStart)
    if (afterOpenLine < 0) return null
    // Search strictly AFTER the opening line's own newline — see `DiagramSpecCodec.kt`'s identical
    // comment on `closeFenceIdx` for why searching from `afterOpenLine` itself would make a
    // zero-content fence throw on the substring below instead of being rejected as malformed.
    val closeFenceIdx = text.indexOf("\n```", afterOpenLine + 1)
    if (closeFenceIdx < 0) return null
    val fenceCloseLineEnd = text.indexOf('\n', closeFenceIdx + 1).let { if (it < 0) text.length else it + 1 }
    val source = text.substring(afterOpenLine + 1, closeFenceIdx)
    if (source.length > MAX_SEQ3_SOURCE_CHARS) return null

    val actualHash = seq3SourceHash(source)
    val matches = actualHash == declaredHash
    val warning = if (!matches) "Diagram source has changed since this note was generated; the model may be stale." else null
    return ParsedSeq3(
        document = document,
        dialect = dialect,
        source = source,
        sourceHash = declaredHash,
        sourceHashMatches = matches,
        warning = warning,
        headerEndIndex = headerEndIndex,
        fenceRange = fenceOpenStart until fenceCloseLineEnd,
        caption = caption,
        exportMode = exportMode,
    )
}

/** Returns [noteText] rewritten with a new caption, or null for a non-v3-diagram note. Mirrors
 *  `diagram.updateDiagramNoteCaption`: re-encodes the whole note from the parsed document/dialect/
 *  source so the fenced body — and therefore [Seq3Codec]'s own hash — is preserved verbatim. */
fun updateSeq3NoteCaption(noteText: String, caption: String): String? {
    val parsed = parseSeq3Note(noteText) ?: return null
    return encodeSeq3Note(parsed.document, parsed.dialect, caption, parsed.exportMode, sourceOverride = parsed.source)
}

/** Returns [noteText] rewritten with [exportMode], or null for a non-v3-diagram note. */
fun updateSeq3NoteExportMode(noteText: String, exportMode: DiagramExportMode): String? {
    val parsed = parseSeq3Note(noteText) ?: return null
    return encodeSeq3Note(parsed.document, parsed.dialect, parsed.caption, exportMode, sourceOverride = parsed.source)
}

/** Strips the leading header comment (and the blank line right after it, if any), leaving just the
 *  fenced code block — for Markdown export, where the JSON header would otherwise appear as a stray
 *  HTML comment. Returns [text] unchanged when it isn't a well-formed v3 diagram note, which is
 *  always safe: an ordinary Note has no header to strip in the first place. */
fun stripSeq3NoteHeader(text: String): String {
    val parsed = parseSeq3Note(text) ?: return text
    return text.substring(parsed.headerEndIndex).trimStart('\n')
}

private val HEX64 = Regex("[0-9a-f]{64}")

// ── Bounded string/list helpers — every decode path funnels through these ──────────────────────

private fun boundedString(value: String?): String? = value?.takeIf { it.length <= MAX_SEQ3_STRING_CHARS }

@Suppress("UNCHECKED_CAST")
private fun subMap(map: Map<String, Any?>, key: String): Map<String, Any?>? = map[key] as? Map<String, Any?>

private inline fun <reified E : Enum<E>> enumFromName(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

// ── Seq3Document <-> Map(JSON) ───────────────────────────────────────────────────────────────

private fun documentToMap(d: Seq3Document): Map<String, Any?> = mapOf(
    "title" to d.title,
    "sourceFile" to d.sourceFile,
    "range" to rangeToMap(d.range),
    "lifelines" to d.lifelines.map(::lifelineToMap),
    "messages" to d.messages.map(::messageToMap),
    "fragments" to d.fragments.map(::fragmentToMap),
    "notes" to d.notes.map(::noteToMap),
    "defaultRepeat" to d.defaultRepeat.name,
)

// Pulled out of documentFromMap purely to keep that function's own return-statement count under
// detekt's limit — this is the top-level "does the declared shape even fit our caps" gate, checked
// BEFORE any of the four lists are actually decoded.
private fun withinSeq3DocumentBounds(lifelineMaps: List<*>, messageMaps: List<*>, fragmentMaps: List<*>, noteMaps: List<*>): Boolean =
    lifelineMaps.size <= MAX_SEQ3_LIFELINES && messageMaps.size <= MAX_SEQ3_MESSAGES &&
        fragmentMaps.size <= MAX_SEQ3_FRAGMENTS && noteMaps.size <= MAX_SEQ3_NOTES

private fun documentFromMap(map: Map<String, Any?>): Seq3Document? {
    val lifelineMaps = map.mapList("lifelines").orEmpty()
    val messageMaps = map.mapList("messages").orEmpty()
    val fragmentMaps = map.mapList("fragments").orEmpty()
    val noteMaps = map.mapList("notes").orEmpty()
    if (!withinSeq3DocumentBounds(lifelineMaps, messageMaps, fragmentMaps, noteMaps)) return null

    val messages = messageMaps.mapNotNull(::messageFromMap)
    val fragments = fragmentMaps.mapNotNull(::fragmentFromMap)
    val occurrencesWithinBounds = messages.all { it.occurrences.size <= MAX_SEQ3_OCCURRENCES_PER_MESSAGE }
    val fragmentIdsWithinBounds = fragments.all { it.messageIds.size <= MAX_SEQ3_MESSAGE_IDS_PER_FRAGMENT }
    if (!occurrencesWithinBounds || !fragmentIdsWithinBounds) return null

    return Seq3Document(
        title = boundedString(map.str("title")) ?: "",
        sourceFile = boundedString(map.str("sourceFile")),
        range = subMap(map, "range")?.let(::rangeFromMap) ?: Seq3Range.VisibleView,
        lifelines = lifelineMaps.mapNotNull(::lifelineFromMap),
        messages = messages,
        fragments = fragments,
        notes = noteMaps.mapNotNull(::noteFromMap),
        defaultRepeat = enumFromName(map.str("defaultRepeat"), Seq3Repeat.COLLAPSE_ABOVE),
    )
}

// ── Range ────────────────────────────────────────────────────────────────────────────────────

private fun rangeToMap(range: Seq3Range): Map<String, Any?> = when (range) {
    is Seq3Range.VisibleView -> mapOf("type" to "visible")
    is Seq3Range.Ids -> mapOf("type" to "ids", "from" to range.from, "to" to range.to, "selectedIds" to range.selectedIds.toList())
    is Seq3Range.Time -> mapOf("type" to "time", "fromTs" to range.fromTs, "toTs" to range.toTs)
}

private fun rangeFromMap(map: Map<String, Any?>): Seq3Range = when (map.str("type")) {
    "ids" -> Seq3Range.Ids(
        from = map.int("from") ?: 0,
        to = map.int("to") ?: 0,
        selectedIds = map.intList("selectedIds")?.toSet().orEmpty(),
    )
    "time" -> Seq3Range.Time(map.str("fromTs") ?: "", map.str("toTs") ?: "")
    else -> Seq3Range.VisibleView
}

// ── Lifeline ─────────────────────────────────────────────────────────────────────────────────

private fun lifelineToMap(l: Seq3Lifeline): Map<String, Any?> =
    mapOf("id" to l.id, "name" to l.name, "tagIds" to l.tagIds.toList(), "ordinal" to l.ordinal)

private fun lifelineFromMap(map: Map<String, Any?>): Seq3Lifeline? {
    val id = boundedString(map.str("id")) ?: return null
    return Seq3Lifeline(
        id = id,
        name = boundedString(map.str("name")) ?: id,
        tagIds = map.strList("tagIds")?.mapNotNull(::boundedString)?.toSet() ?: setOf(id),
        ordinal = map.int("ordinal") ?: 0,
    )
}

// ── Match / capture ──────────────────────────────────────────────────────────────────────────

private fun captureToMap(c: Seq3Capture): Map<String, Any?> = mapOf("name" to c.name, "source" to c.source.name)

private fun captureFromMap(map: Map<String, Any?>): Seq3Capture? {
    val name = boundedString(map.str("name")) ?: return null
    return Seq3Capture(name, enumFromName(map.str("source"), Seq3CaptureSource.POSITIONAL_RUN))
}

private fun matchToMap(m: Seq3Match): Map<String, Any?> =
    mapOf("tag" to m.tag, "template" to m.template, "captures" to m.captures.map(::captureToMap))

private fun matchFromMap(map: Map<String, Any?>): Seq3Match? {
    val template = boundedString(map.str("template")) ?: return null
    val captureMaps = map.mapList("captures").orEmpty()
    if (captureMaps.size > MAX_SEQ3_CAPTURES_PER_MATCH) return null
    return Seq3Match(
        tag = boundedString(map.str("tag")) ?: "",
        template = template,
        captures = captureMaps.mapNotNull(::captureFromMap),
    )
}

// ── Occurrence ───────────────────────────────────────────────────────────────────────────────

private fun occurrenceToMap(o: Seq3Occurrence): Map<String, Any?> = mapOf(
    "entryId" to o.entryId,
    "timestampMillis" to o.timestampMillis,
    "rawTimestamp" to o.rawTimestamp,
    "pid" to o.pid,
    "tid" to o.tid,
    "level" to o.level.toString(),
    "text" to o.text,
    "captureValues" to o.captureValues,
)

@Suppress("UNCHECKED_CAST")
private fun occurrenceFromMap(map: Map<String, Any?>): Seq3Occurrence? {
    val entryId = map.int("entryId") ?: return null
    val text = boundedString(map.str("text")) ?: return null
    val captureValues = (map["captureValues"] as? Map<String, Any?>)
        ?.mapNotNull { (k, v) -> boundedString(k)?.let { key -> boundedString(v as? String)?.let { key to it } } }
        ?.toMap().orEmpty()
    return Seq3Occurrence(
        entryId = entryId,
        timestampMillis = (map["timestampMillis"] as? Number)?.toLong(),
        rawTimestamp = boundedString(map.str("rawTimestamp")) ?: "",
        pid = map.int("pid") ?: 0,
        tid = map.int("tid") ?: 0,
        level = map.str("level")?.firstOrNull() ?: '?',
        text = text,
        captureValues = captureValues,
    )
}

// ── Order pin ────────────────────────────────────────────────────────────────────────────────

private fun orderPinToMap(p: Seq3OrderPin): Map<String, Any?> = mapOf("tiedTimestampMillis" to p.tiedTimestampMillis, "tieRank" to p.tieRank)

private fun orderPinFromMap(map: Map<String, Any?>): Seq3OrderPin? {
    val ts = (map["tiedTimestampMillis"] as? Number)?.toLong() ?: return null
    return Seq3OrderPin(ts, map.int("tieRank") ?: 0)
}

// ── Message ──────────────────────────────────────────────────────────────────────────────────

private fun messageToMap(m: Seq3Message): Map<String, Any?> = mapOf(
    "id" to m.id,
    "match" to matchToMap(m.match),
    "fromLifelineId" to m.fromLifelineId,
    "toLifelineId" to m.toLifelineId,
    "labelTemplate" to m.labelTemplate,
    "kind" to m.kind.name,
    "repeat" to m.repeat.name,
    "repeatThreshold" to m.repeatThreshold,
    "visibility" to m.visibility.name,
    "authoring" to m.authoring.name,
    "orderPin" to m.orderPin?.let(::orderPinToMap),
    "occurrences" to m.occurrences.map(::occurrenceToMap),
)

private fun messageFromMap(map: Map<String, Any?>): Seq3Message? {
    val id = boundedString(map.str("id")) ?: return null
    val from = boundedString(map.str("fromLifelineId")) ?: return null
    val match = subMap(map, "match")?.let(::matchFromMap) ?: return null
    val occurrenceMaps = map.mapList("occurrences").orEmpty()
    if (occurrenceMaps.size > MAX_SEQ3_OCCURRENCES_PER_MESSAGE) return null
    return Seq3Message(
        id = id,
        match = match,
        fromLifelineId = from,
        toLifelineId = boundedString(map.str("toLifelineId")),
        labelTemplate = boundedString(map.str("labelTemplate")) ?: match.template,
        kind = enumFromName(map.str("kind"), Seq3Kind.CALL),
        repeat = enumFromName(map.str("repeat"), Seq3Repeat.COLLAPSE_ABOVE),
        repeatThreshold = map.int("repeatThreshold") ?: DEFAULT_SEQ3_REPEAT_THRESHOLD,
        visibility = enumFromName(map.str("visibility"), Seq3Visibility.VISIBLE),
        authoring = enumFromName(map.str("authoring"), Seq3Authoring.AUTO),
        orderPin = subMap(map, "orderPin")?.let(::orderPinFromMap),
        occurrences = occurrenceMaps.mapNotNull(::occurrenceFromMap),
    )
}

// ── Fragment / note ──────────────────────────────────────────────────────────────────────────

private fun fragmentToMap(f: Seq3Fragment): Map<String, Any?> =
    mapOf("id" to f.id, "kind" to f.kind.name, "label" to f.label, "messageIds" to f.messageIds)

private fun fragmentFromMap(map: Map<String, Any?>): Seq3Fragment? {
    val id = boundedString(map.str("id")) ?: return null
    return Seq3Fragment(
        id = id,
        kind = enumFromName(map.str("kind"), Seq3FragmentKind.LOOP),
        label = boundedString(map.str("label")) ?: "",
        messageIds = map.strList("messageIds").orEmpty(),
    )
}

private fun noteToMap(n: Seq3Note): Map<String, Any?> = mapOf("id" to n.id, "text" to n.text, "messageIds" to n.messageIds)

private fun noteFromMap(map: Map<String, Any?>): Seq3Note? {
    val id = boundedString(map.str("id")) ?: return null
    val text = boundedString(map.str("text")) ?: return null
    return Seq3Note(id = id, text = text, messageIds = map.strList("messageIds").orEmpty())
}
