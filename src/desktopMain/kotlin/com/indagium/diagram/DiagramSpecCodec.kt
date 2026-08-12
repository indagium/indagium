package com.indagium.diagram

import com.indagium.debug.Json
import com.indagium.debug.bool
import com.indagium.debug.int
import com.indagium.debug.str
import com.indagium.model.LogLevel
import java.security.MessageDigest

// ── On-disk convention for a diagram note ───────────────────────────────────────────────────
//
// A generated diagram is stored as an ORDINARY AnnBlock.Note (see DiagramModel.kt's header
// comment — nothing about the .ann/autosave format changes for this feature) whose text is:
//
//   <!-- indagium:diagram v1 {"dialect":"mermaid","sourceFile":"...","range":{...},...} -->
//   ```mermaid
//   sequenceDiagram
//     ...
//   ```
//
// The HTML comment is invisible in every plain-text/Markdown rendering of the note (Jira, GitHub,
// a raw .md viewer) and carries the full SeqDiagramSpec so "Regenerate" (Phase 3) can rebuild the
// exact same diagram later without the user re-entering range/participants/rules. The fenced code
// block is what actually renders — GitHub/GitLab/many Jira Mermaid plugins render a ```mermaid
// fence natively, and even where they don't, the fence still reads as plain preformatted text
// rather than garbled markup. parseDiagramNote must never throw and must return null for anything
// that isn't a complete, version-supported header + fence pair — every caller downstream (Notes
// panel rendering, buildMd's export) treats null as "this is just a normal text note", which is
// always a SAFE fallback: worst case a diagram note displays as literal text instead of rendering.

private const val MARKER_HEAD = "<!-- indagium:diagram "
private const val MARKER_TAIL = " -->"
private const val LEGACY_SPEC_VERSION = "v1"
private const val SNAPSHOT_SPEC_VERSION = "v2"
private const val PREVIOUS_SPEC_VERSION = "v3"
private const val CURRENT_SPEC_VERSION = "v4"

// Notes can be imported from arbitrary .ann/case-library files.  Keep this boundary materially
// below a pathological renderer allocation while matching the public MCP's 400-arrow limit.
private const val MAX_DIAGRAM_MESSAGES = 5_000
private const val MAX_CODEC_PARTICIPANTS = 128

// internal, not private: validSpec only runs on *decode*, so the component editor
// (ui/SeqDiagramDialog.kt) is what has to stop a user building a spec that saves fine and then
// silently refuses to reopen. It needs the real numbers — a duplicated pair of literals over there
// would drift the moment either of these moves, in the direction that reintroduces the data loss.
internal const val MAX_CODEC_COMPONENTS = 128
private const val MAX_CODEC_ACTORS = 128
internal const val MAX_CODEC_TAG_IDS = 512
private const val MAX_CODEC_RULES = 128
private const val MAX_CODEC_OVERRIDES = 512
private const val MAX_CODEC_SOURCE_OVERRIDES = 512
private const val MAX_CODEC_MESSAGE_OVERRIDES = 512
private const val MAX_CODEC_MANUAL_INTERACTIONS = 5_000
private const val MAX_CODEC_MANUAL_GROUPS = 128
private const val MAX_CODEC_MANUAL_NOTES = 400
private const val MAX_CODEC_MANUAL_ACTIVATIONS = 400
private const val MAX_CODEC_PARAMETERS = 32
private const val MAX_CODEC_TRACE_EVENTS = 400
private const val MAX_CODEC_TRACE_CALLS = 400
private const val MAX_CODEC_TRACE_OPERATIONS = 1_600
private const val MAX_CODEC_TRACE_DIAGNOSTICS = 128

// validSpec only runs on *decode* (see this file's own note on MAX_CODEC_COMPONENTS above) — the
// options-panel SegmentedControl (ui/SeqDiagramInspector.kt) offers exactly "1".."4", well inside
// this, but the bound itself is deliberately generous rather than tied to that UI's own choices.
private const val MAX_LABEL_LINES = 8
private const val MAX_CODEC_HEADER_CHARS = 512 * 1024
private const val MAX_CODEC_SOURCE_CHARS = 2 * 1024 * 1024
private const val MAX_CODEC_STRING_CHARS = 16 * 1024

private fun fenceLanguage(dialect: DiagramDialect): String = when (dialect) {
    DiagramDialect.MERMAID -> "mermaid"
    DiagramDialect.PLANTUML -> "plantuml"
}

enum class DiagramAttachmentMode { SNAPSHOT, LINKED }

/** How an attached diagram is represented outside Indagium.  IMAGE is deliberately the default:
 * it works in Markdown and Jira even when the receiving system has no Mermaid/PlantUML support.
 * SOURCE is kept for reviews where the editable dialect text is the useful artifact. */
enum class DiagramExportMode { IMAGE, SOURCE }

/** Optional identity of the durable diagram this note was attached from.  The codec only records
 * metadata; resolving a linked diagram remains the library/UI's responsibility. */
data class DiagramAttachmentMetadata(
    val diagramId: String? = null,
    val mode: DiagramAttachmentMode = DiagramAttachmentMode.SNAPSHOT,
    val revision: Long? = null,
    val attachedAtEpochMs: Long? = null,
    /** Display label shown immediately above the exported/previewed attachment. */
    val caption: String = "",
    /** The export representation. Missing metadata on a legacy v1 note means [IMAGE]. */
    val exportMode: DiagramExportMode = DiagramExportMode.IMAGE,
)

/** Self-contained source/model fallback preserved with a v2 attachment.  It is intentionally
 * exposed even when the current fenced source was edited, but its [model] is never promoted to
 * [ParsedDiagram.model] unless its hash proves it still describes that source. */
data class DiagramNoteSnapshot(
    val source: String,
    val sourceHash: String,
    val model: SeqDiagram? = null,
)

/** [model] is stored inside a v2 source snapshot and guarded by a SHA-256 hash of the fenced
 * source.  An advanced source edit can therefore never leave a clickable stale model on screen.
 * [snapshot] is primarily for attachment/import callers; the default snapshots [source]/[model]. */
fun encodeDiagramNote(
    spec: SeqDiagramSpec,
    source: String,
    model: SeqDiagram? = null,
    attachment: DiagramAttachmentMetadata? = null,
    snapshot: DiagramNoteSnapshot? = null,
): String {
    val normalizedSource = source.trimEnd('\n')
    val sourceHash = diagramSourceHash(normalizedSource)
    // Every newly saved model is reopened as the one editable manual document.  A source-only
    // note remains a viewable legacy snapshot because it has no model from which to make a draft.
    val persistedSpec = if (spec.manualDocument.interactions.isNotEmpty()) spec else model?.let {
        spec.copy(
            authoringMode = DiagramAuthoringMode.MANUAL,
            manualDocument = manualDocumentFromDiagram(it),
        )
    } ?: spec
    val normalizedModel = model?.copy(spec = persistedSpec)
    val normalizedSnapshot = (snapshot ?: DiagramNoteSnapshot(normalizedSource, sourceHash, normalizedModel)).let {
        it.copy(model = it.model?.copy(spec = persistedSpec))
    }
    val json = Json.encode(
        specToMap(persistedSpec) + mapOf(
            "sourceHash" to sourceHash,
            "attachment" to attachment?.let(::attachmentToMap),
            "snapshot" to snapshotToMap(normalizedSnapshot),
        ),
    )
    val lang = fenceLanguage(spec.dialect)
    return buildString {
        append(MARKER_HEAD).append(CURRENT_SPEC_VERSION).append(' ').append(json).append(MARKER_TAIL).append('\n')
        append("```").append(lang).append('\n')
        append(normalizedSource).append('\n')
        append("```").append('\n')
    }
}

data class ParsedDiagram(
    val spec: SeqDiagramSpec,
    val source: String,
    val dialect: DiagramDialect,
    /** The built model, when the header carried one (see modelToMap). Null for a diagram note
     *  written by an older build, or hand-authored — such a note still shows its fenced source and
     *  still exports correctly, it just can't be drawn or clicked until it is regenerated. */
    val model: SeqDiagram?,
    /** v2 only. Null for a trusted v1 note, false when source was edited after generation. */
    val sourceHashMatches: Boolean? = null,
    val sourceHash: String? = null,
    val attachment: DiagramAttachmentMetadata? = null,
    /** The retained v2 snapshot. Its model is intentionally not rendered automatically when
     * [sourceHashMatches] is false; a UI may offer an explicit restore/detach action instead. */
    val snapshot: DiagramNoteSnapshot? = null,
    val warning: String? = null,
    // Index into the ORIGINAL text one past the header comment's closing "-->" (and its trailing
    // newline, if any) — stripDiagramSpecHeader's whole implementation is `text.substring(this)`.
    val headerEndIndex: Int,
    // The fenced block's full extent in the ORIGINAL text, opening ``` through closing ``` line
    // inclusive.
    val fenceRange: IntRange,
) {
    /** Attachment caption, with an empty caption for v1/pre-attachment notes. */
    val caption: String get() = attachment?.caption.orEmpty()

    /** Export representation.  This compatibility default makes every v1 diagram image-first. */
    val exportMode: DiagramExportMode get() = attachment?.exportMode ?: DiagramExportMode.IMAGE

    /**
     * The immutable picture that a read-only preview may show.  This intentionally differs from
     * [model]: the latter is only non-null when it is safe to treat the model as the current,
     * editable fenced source.  A v2 attachment retains its last known-good model in [snapshot],
     * and the Markdown Preview should still show that attachment as a clearly labelled snapshot
     * rather than silently dropping the whole diagram card.
     *
     * Interactive/editor surfaces must continue to use [model], so a manually edited fence can
     * never be presented as a current clickable diagram.
     */
    val snapshotPreviewModel: SeqDiagram?
        get() = model ?: snapshot?.model
}

/** Returns [noteText] rewritten as a current v2 diagram note with a new attachment caption, or
 * null when [noteText] is not a valid diagram note.  The visible source and retained snapshot are
 * preserved verbatim; this is intentionally the only codec boundary UI code needs for metadata
 * edits. */
fun updateDiagramNoteCaption(noteText: String, caption: String): String? =
    updateDiagramAttachment(noteText) { copy(caption = caption) }

/** Returns [noteText] rewritten with [exportMode], or null for a non-diagram note. */
fun updateDiagramNoteExportMode(noteText: String, exportMode: DiagramExportMode): String? =
    updateDiagramAttachment(noteText) { copy(exportMode = exportMode) }

private fun updateDiagramAttachment(
    noteText: String,
    change: DiagramAttachmentMetadata.() -> DiagramAttachmentMetadata,
): String? {
    val parsed = parseDiagramNote(noteText) ?: return null
    return encodeDiagramNote(
        spec = parsed.spec,
        source = parsed.source,
        model = parsed.model,
        attachment = (parsed.attachment ?: DiagramAttachmentMetadata()).change(),
        snapshot = parsed.snapshot,
    )
}

/** Parses a diagram note produced by [encodeDiagramNote]. Returns null — never throws — for
 *  anything not a complete, version-supported header immediately followed by a
 *  matching fenced code block: a plain user-written Note, a header with garbled/truncated JSON, a
 *  header with no fence after it, or a header stamped with a future version this build doesn't
 *  understand. Every one of those is expected input (any Note in the .ann format can reach this
 *  function) and must degrade to "not a diagram note", never crash a Notes-panel render.
 *
 *  This is deliberately a guard-clause parser: each early return rejects one untrusted boundary.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
fun parseDiagramNote(text: String): ParsedDiagram? {
    val trimmed = text.trimStart()
    val leadingWs = text.length - trimmed.length
    if (!trimmed.startsWith(MARKER_HEAD)) return null
    val afterHead = trimmed.substring(MARKER_HEAD.length)
    val spaceIdx = afterHead.indexOf(' ')
    if (spaceIdx <= 0) return null
    val version = afterHead.substring(0, spaceIdx)
    val supportedVersion = version == LEGACY_SPEC_VERSION ||
        version == SNAPSHOT_SPEC_VERSION ||
        version == PREVIOUS_SPEC_VERSION ||
        version == CURRENT_SPEC_VERSION
    if (!supportedVersion) return null
    val rest = afterHead.substring(spaceIdx + 1)
    val tailIdx = rest.indexOf(MARKER_TAIL)
    if (tailIdx < 0) return null
    val jsonText = rest.substring(0, tailIdx)
    if (jsonText.length > MAX_CODEC_HEADER_CHARS) return null
    @Suppress("UNCHECKED_CAST")
    val map = runCatching { Json.decode(jsonText) }.getOrNull() as? Map<String, Any?> ?: return null
    val spec = specFromMap(map) ?: return null

    val markerLen = MARKER_HEAD.length + spaceIdx + 1 + tailIdx + MARKER_TAIL.length
    val headerEndIndex = leadingWs + markerLen

    var cursor = headerEndIndex
    while (cursor < text.length && (text[cursor] == '\n' || text[cursor] == '\r')) cursor++
    val openFence = "```${fenceLanguage(spec.dialect)}"
    if (!text.startsWith(openFence, cursor)) return null
    val fenceOpenStart = cursor
    val afterOpenLine = text.indexOf('\n', fenceOpenStart)
    if (afterOpenLine < 0) return null
    // Search strictly AFTER the opening line's own newline, not at-or-after it — searching from
    // afterOpenLine itself would let that same newline double as both the open line's terminator
    // and a spurious "\n```" match for a fence with zero content lines, making closeFenceIdx ==
    // afterOpenLine and the substring below throw (start index > end index). A degenerate
    // "immediately closed" fence is rejected as malformed (falls through to the null return) —
    // safe, and this encoder never produces one (source is always non-empty rendered text).
    val closeFenceIdx = text.indexOf("\n```", afterOpenLine + 1)
    if (closeFenceIdx < 0) return null
    val fenceCloseLineEnd = text.indexOf('\n', closeFenceIdx + 1).let { if (it < 0) text.length else it + 1 }
    val source = text.substring(afterOpenLine + 1, closeFenceIdx)
    if (source.length > MAX_CODEC_SOURCE_CHARS) return null

    val declaredHash = map.str("sourceHash")
    if (declaredHash != null && !declaredHash.matches(Regex("[0-9a-f]{64}"))) return null
    val snapshot = if (version != LEGACY_SPEC_VERSION) {
        subMap(map, "snapshot")?.let { snapshotFromMap(it, spec) }
    } else {
        null
    }
    // v1 did not carry a source fingerprint and remains trusted for backward compatibility. v2
    // only exposes its model when the header's hash and snapshot both describe the visible fence.
    val hashMatches = if (version != LEGACY_SPEC_VERSION) {
        declaredHash != null && declaredHash == diagramSourceHash(source)
    } else {
        null
    }
    val snapshotMatchesHeader = snapshot?.let { it.sourceHash == declaredHash && it.source == source } == true
    val model = when {
        version == LEGACY_SPEC_VERSION -> subMap(map, "model")?.let { modelFromMap(it, spec) }
        hashMatches == true && snapshotMatchesHeader -> snapshot.model
        else -> null
    }
    val warning = if (version != LEGACY_SPEC_VERSION && model == null && snapshot?.model != null) {
        "Diagram source has changed since this snapshot was generated; showing source only."
    } else {
        null
    }
    val attachment = subMap(map, "attachment")?.let(::attachmentFromMap)
    if (attachment != null && !validAttachment(attachment)) return null
    return ParsedDiagram(
        spec = spec,
        source = source,
        dialect = spec.dialect,
        model = model,
        sourceHashMatches = hashMatches,
        sourceHash = declaredHash,
        attachment = attachment,
        snapshot = snapshot,
        warning = warning,
        headerEndIndex = headerEndIndex,
        fenceRange = fenceOpenStart until fenceCloseLineEnd,
    )
}

/** Strips the leading `<!-- indagium:diagram ... -->` header comment (and the blank line right
 *  after it, if any), leaving just the fenced code block — for Markdown export, where the JSON
 *  spec header would otherwise appear as a stray HTML comment in the rendered document. Returns
 *  [text] unchanged when it isn't a well-formed diagram note (see [parseDiagramNote]), which is
 *  always safe: an ordinary Note has no header to strip in the first place. */
fun stripDiagramSpecHeader(text: String): String {
    val parsed = parseDiagramNote(text) ?: return text
    return text.substring(parsed.headerEndIndex).trimStart('\n')
}

// ── SeqDiagramSpec <-> Map(JSON) ────────────────────────────────────────────────────────────
// Every *FromMap function below falls back to SeqDiagramSpec()'s own defaults for anything
// missing/malformed, and every enum lookup falls back rather than failing the whole parse on one
// bad token — the same "unknown fields ignored, missing fields default, never reorder" contract
// AutosaveCodec's token format holds itself to (see CLAUDE.md's append-last-token-versioning
// note), just expressed as JSON here instead of positional `|`-joined fields.

private inline fun <reified E : Enum<E>> enumFromName(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

// The one back-compat boundary for the ArrowMode rename (DiagramModel.kt's own doc): a note saved
// before EVIDENCE_FLOW existed carries the literal string "TAG_TRANSITION" as its persisted "mode"
// token. Saved library diagrams keep displaying identically either way — the BUILT model rides in
// the header's own "model" snapshot (modelFromMap) — only future REGENERATION picks up the new
// evidence-only shape, which is the intended fix.
private fun arrowModeFromName(name: String?): ArrowMode? =
    if (name == "TAG_TRANSITION") ArrowMode.EVIDENCE_FLOW else enumFromName<ArrowMode>(name)

/** Stable lowercase hexadecimal SHA-256 for the exact fenced source body. */
fun diagramSourceHash(source: String): String = MessageDigest.getInstance("SHA-256")
    .digest(source.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun attachmentToMap(a: DiagramAttachmentMetadata): Map<String, Any?> = mapOf(
    "diagramId" to a.diagramId,
    "mode" to a.mode.name,
    "revision" to a.revision,
    "attachedAtEpochMs" to a.attachedAtEpochMs,
    "caption" to a.caption,
    "exportMode" to a.exportMode.name,
)

private fun attachmentFromMap(map: Map<String, Any?>): DiagramAttachmentMetadata = DiagramAttachmentMetadata(
    diagramId = map.str("diagramId"),
    mode = enumFromName<DiagramAttachmentMode>(map.str("mode")) ?: DiagramAttachmentMode.SNAPSHOT,
    revision = (map["revision"] as? Number)?.toLong(),
    attachedAtEpochMs = (map["attachedAtEpochMs"] as? Number)?.toLong(),
    caption = map.str("caption") ?: "",
    exportMode = enumFromName<DiagramExportMode>(map.str("exportMode")) ?: DiagramExportMode.IMAGE,
)

private fun snapshotToMap(snapshot: DiagramNoteSnapshot): Map<String, Any?> = mapOf(
    "source" to snapshot.source,
    "sourceHash" to snapshot.sourceHash,
    "model" to snapshot.model?.let(::modelToMap),
)

private fun snapshotFromMap(map: Map<String, Any?>, spec: SeqDiagramSpec): DiagramNoteSnapshot? {
    val source = map.str("source") ?: return null
    val sourceHash = map.str("sourceHash") ?: return null
    if (source.length > MAX_CODEC_SOURCE_CHARS || !sourceHash.matches(Regex("[0-9a-f]{64}"))) return null
    return DiagramNoteSnapshot(source, sourceHash, subMap(map, "model")?.let { modelFromMap(it, spec) })
}

// ── The built model, carried alongside the spec ─────────────────────────────────────────────
//
// The fenced block holds Mermaid/PlantUML TEXT, which is what renders in Jira/GitHub — but text
// alone can't drive the in-app picture: SeqDiagramRenderer needs a SeqDiagram, and (decisively)
// DiagramMessage.entryId, which no dialect's syntax has any way to express. Without it there is no
// click-an-arrow-to-jump-to-the-log-line, which is the whole reason this feature renders in-app
// rather than shelling out to PlantUML.
//
// So the header carries the built model too. The alternative — re-running buildSequenceDiagram from
// the spec — needs the original log attached, and a note reopened from the Case Library ("notes
// only") has none; carrying the model is what lets such a note still display its diagram, exactly
// as AnnBlock.LogRef.sourceEntries lets a reopened note still show its log rows (see Model.kt).
// Cost is ~13 KB of JSON for a 120-message diagram, invisible in every rendered view and trivial
// beside the 400 KB JPEGs Annotations already inlines per screenshot.
//
// Keys here are deliberately terse (one or two chars) because messages are the one unbounded list.

private fun modelToMap(original: SeqDiagram): Map<String, Any?> {
    val d = stripTransientCaller(original)
    return mapOf(
        "participants" to d.participants.map(::participantToMap),
        "messages" to d.messages.map { m ->
            mapOf(
                "f" to m.fromIdx, "t" to m.toIdx, "l" to m.label, "e" to m.entryId,
                "ts" to m.ts, "v" to m.level.name, "k" to m.kind.name, "r" to m.repeatCount,
                "x" to m.evidence.name, "o" to m.edgeOrdinal,
                "i" to m.invocationId, "st" to m.traceStatus?.name, "ik" to m.invocationKind?.name,
                "p" to m.primary, "ids" to m.representedEntryIds.sorted(),
                "so" to m.sourceOperationId, "sl" to m.sourceLogSiteId,
                "origins" to m.originKeys.map(::originKeyToMap),
                "tl" to m.targetless, "mg" to m.manualGroupKey,
            )
        },
        "frames" to d.frames.map { f ->
            mapOf("l" to f.label, "c" to f.colorArgb, "a" to f.firstMsg, "b" to f.lastMsg, "d" to f.depth)
        },
        "notes" to d.notes.map { n ->
            mapOf("p" to n.participantIdx, "a" to n.afterMsg, "t" to n.text, "e" to n.isError)
        },
        "activations" to d.activationSpans.map { a ->
            mapOf(
                "p" to a.participantIdx, "s" to a.startMessage, "e" to a.endMessage, "v" to a.evidence.name,
                "i" to a.invocationId, "st" to a.status?.name, "ik" to a.invocationKind?.name,
            )
        },
        "truncated" to d.truncated,
        "scanned" to d.scannedEntries,
        "coverage" to mapOf(
            "scanned" to d.coverage.scannedEntries,
            "shown" to d.coverage.shownEntries,
            "grouped" to d.coverage.groupedEntries,
            "hidden" to d.coverage.hiddenEntries,
        ),
        "traceMode" to d.traceMode.name,
        "trace" to d.resolvedTrace?.let(::traceToMap),
    )
}

private fun stripTransientCaller(diagram: SeqDiagram): SeqDiagram {
    val transient = diagram.participants.mapIndexedNotNull { index, participant ->
        index.takeIf { participant.kind == ParticipantKind.ACTOR && participant.inferred && participant.label == "Caller" }
    }.toSet()
    if (transient.isEmpty()) return diagram
    val oldToNew = IntArray(diagram.participants.size) { -1 }
    val participants = diagram.participants.filterIndexed { index, _ -> index !in transient }
    var newIndex = 0
    diagram.participants.indices.filter { it !in transient }.forEach { oldIndex ->
        oldToNew[oldIndex] = newIndex++
    }

    fun remap(index: Int, other: Int): Int? {
        if (index !in transient) return oldToNew.getOrNull(index)?.takeIf { it >= 0 }
        return oldToNew.getOrNull(other)?.takeIf { it >= 0 }
    }
    val messages = diagram.messages.mapNotNull { message ->
        val from = remap(message.fromIdx, message.toIdx) ?: return@mapNotNull null
        val to = remap(message.toIdx, message.fromIdx) ?: return@mapNotNull null
        message.copy(fromIdx = from, toIdx = to)
    }
    val notes = diagram.notes.mapNotNull { note ->
        oldToNew.getOrNull(note.participantIdx)?.takeIf { it >= 0 }?.let { note.copy(participantIdx = it) }
    }
    val activations = diagram.activationSpans.mapNotNull { span ->
        oldToNew.getOrNull(span.participantIdx)?.takeIf { it >= 0 }?.let { span.copy(participantIdx = it) }
    }
    return diagram.copy(participants = participants, messages = messages, notes = notes, activationSpans = activations)
}

/** Rebuilds the model recorded by [modelToMap]. [spec] is threaded back in rather than stored
 *  twice — it is already the header's top-level payload. Returns null when the record is absent or
 *  carries no participants, so a caller falls back to "text-only diagram note" rather than
 *  rendering an empty picture.
 *
 *  Decode-and-validate uses one return per invalid untrusted sub-record to avoid partial models.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition")
private fun modelFromMap(map: Map<String, Any?>, spec: SeqDiagramSpec): SeqDiagram? {
    val participantMaps = strictMapList(map, "participants", MAX_CODEC_PARTICIPANTS) ?: return null
    val participants = participantMaps.map(::participantFromMap)
    val safeParticipants = participants.filterNotNull()
    if (participants.any { it == null } || safeParticipants.isEmpty()) return null
    if (!validParticipants(safeParticipants)) return null
    val messageMaps = strictMapList(map, "messages", MAX_DIAGRAM_MESSAGES) ?: return null
    val messages = messageMaps.map { m ->
        val from = m.int("f") ?: return@map null
        val to = m.int("t") ?: return@map null
        val originMaps = strictMapListOrEmpty(m, "origins", MAX_CODEC_MESSAGE_OVERRIDES) ?: return@map null
        val origins = originMaps.map(::originKeyFromMap)
        if (origins.any { it == null }) return@map null
        DiagramMessage(
            fromIdx = from, toIdx = to,
            label = m.str("l") ?: "",
            entryId = m.int("e") ?: 0,
            ts = m.str("ts") ?: "",
            level = enumFromName<LogLevel>(m.str("v")) ?: LogLevel.I,
            kind = enumFromName<MessageKind>(m.str("k")) ?: MessageKind.CALL,
            repeatCount = m.int("r") ?: 1,
            evidence = enumFromName<MessageEvidence>(m.str("x")) ?: MessageEvidence.LOG,
            edgeOrdinal = m.int("o") ?: 0,
            invocationId = m.str("i"),
            traceStatus = enumFromName<TraceCallStatus>(m.str("st")),
            invocationKind = enumFromName<TraceInvocationKind>(m.str("ik")),
            primary = m.bool("p") ?: true,
            representedEntryIds = (m["ids"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toSet()
                ?.takeIf { it.isNotEmpty() } ?: setOf(m.int("e") ?: return@map null),
            sourceOperationId = m.str("so"),
            sourceLogSiteId = m.str("sl"),
            originKeys = origins.filterNotNull().toSet(),
            targetless = m.bool("tl") ?: false,
            manualGroupKey = m.str("mg"),
        )
    }
    if (messages.any { it == null }) return null
    val safeMessages = messages.filterNotNull()
    if (safeMessages.any { !validMessage(it, safeParticipants.size) }) return null
    val frameMaps = strictMapListOrEmpty(map, "frames", MAX_DIAGRAM_MESSAGES) ?: return null
    val frames = frameMaps.map { f ->
        val a = f.int("a") ?: return@map null
        val b = f.int("b") ?: return@map null
        DiagramFrame(f.str("l") ?: "", f.int("c"), a, b, f.int("d") ?: 0)
    }
    if (frames.any { it == null }) return null
    val safeFrames = frames.filterNotNull()
    if (safeFrames.any { !validFrame(it, safeMessages.size) }) return null
    val noteMaps = strictMapListOrEmpty(map, "notes", MAX_DIAGRAM_MESSAGES) ?: return null
    val notes = noteMaps.map { n ->
        val p = n.int("p") ?: return@map null
        val a = n.int("a") ?: return@map null
        DiagramNoteMark(p, a, n.str("t") ?: "", n.bool("e") ?: false)
    }
    if (notes.any { it == null }) return null
    val safeNotes = notes.filterNotNull()
    if (safeNotes.any { !validNote(it, safeParticipants.size, safeMessages.size) }) return null
    val activationMaps = strictMapListOrEmpty(map, "activations", MAX_DIAGRAM_MESSAGES) ?: return null
    val activations = activationMaps.map { a ->
        val p = a.int("p") ?: return@map null
        val s = a.int("s") ?: return@map null
        val e = a.int("e") ?: return@map null
        DiagramActivationSpan(
            p, s, e, enumFromName<MessageEvidence>(a.str("v")) ?: MessageEvidence.LOG,
            invocationId = a.str("i"),
            status = enumFromName<TraceCallStatus>(a.str("st")),
            invocationKind = enumFromName<TraceInvocationKind>(a.str("ik")),
        )
    }
    if (activations.any { it == null }) return null
    val safeActivations = activations.filterNotNull()
    if (safeActivations.any { !validActivation(it, safeParticipants.size, safeMessages.size) }) return null
    val scannedEntries = map.int("scanned") ?: 0
    if (scannedEntries < 0) return null
    val coverageMap = subMap(map, "coverage")
    if (coverageMap != null && listOf("scanned", "shown", "grouped", "hidden").any { (coverageMap.int(it) ?: 0) < 0 }) return null
    val trace = if (map["trace"] != null) {
        subMap(map, "trace")?.let(::traceFromMap) ?: return null
    } else {
        null
    }
    return SeqDiagram(
        spec = spec,
        participants = safeParticipants,
        messages = safeMessages,
        frames = safeFrames,
        notes = safeNotes,
        activationSpans = safeActivations,
        truncated = map.bool("truncated") ?: false,
        scannedEntries = scannedEntries,
        coverage = coverageMap?.let {
            DiagramCoverage(
                scannedEntries = it.int("scanned") ?: scannedEntries,
                shownEntries = it.int("shown") ?: 0,
                groupedEntries = it.int("grouped") ?: 0,
                hiddenEntries = it.int("hidden") ?: 0,
            )
        } ?: DiagramCoverage(scannedEntries = scannedEntries),
        resolvedTrace = trace,
        traceMode = enumFromName<SourceTraceMode>(map.str("traceMode")) ?: SourceTraceMode.DISABLED,
    )
}

private fun specToMap(spec: SeqDiagramSpec): Map<String, Any?> = mapOf(
    "dialect" to spec.dialect.name,
    "title" to spec.title,
    "participants" to spec.participants.map(::participantToMap),
    "range" to rangeToMap(spec.range),
    "options" to optionsToMap(spec.options),
    "sourceFile" to spec.sourceFile,
    "components" to spec.components.map(::componentToMap),
    "actors" to spec.actors.map(::actorToMap),
    "unmappedTagPolicy" to spec.unmappedTagPolicy.name,
    "lifelineOrder" to spec.lifelineOrder,
    "manualDocument" to manualDocumentToMap(spec.manualDocument),
)

private fun componentToMap(c: DiagramComponent): Map<String, Any?> = mapOf(
    "id" to c.id, "displayName" to c.displayName, "tagIds" to c.tagIds.toList(), "enabled" to c.enabled,
    "sourceOwnerTypes" to c.sourceOwnerTypes.toList(),
)

private fun actorToMap(a: DiagramActor): Map<String, Any?> = mapOf(
    "id" to a.id, "label" to a.label,
)

private fun parameterToMap(parameter: DiagramParameter): Map<String, Any?> = mapOf(
    "name" to parameter.name,
    "value" to parameter.value,
)

private fun manualDocumentToMap(document: ManualDiagramDocument): Map<String, Any?> = mapOf(
    "interactions" to document.interactions.map { interaction ->
        mapOf(
            "id" to interaction.id, "sourceEntryIds" to interaction.sourceEntryIds.sorted(),
            "fromParticipantId" to interaction.fromParticipantId, "toParticipantId" to interaction.toParticipantId,
            "operation" to interaction.operation, "parameters" to interaction.parameters.map(::parameterToMap),
            "result" to interaction.result, "label" to interaction.label, "kind" to interaction.kind.name,
            "enabled" to interaction.enabled, "order" to interaction.order,
            "groupKey" to interaction.groupKey, "sourceMethodId" to interaction.sourceMethodId,
            "sourceLogSiteId" to interaction.sourceLogSiteId, "sourceOwnerType" to interaction.sourceOwnerType,
            "visibility" to interaction.visibility.name,
            "renderAnchorTs" to interaction.renderAnchorTs,
            "renderAnchorLevel" to interaction.renderAnchorLevel?.name,
            "authoring" to interaction.authoring.name,
        )
    },
    "groups" to document.groups.map { group ->
        mapOf("id" to group.id, "label" to group.label, "interactionIds" to group.interactionIds, "enabled" to group.enabled)
    },
    "notes" to document.notes.map { note ->
        mapOf("id" to note.id, "participantId" to note.participantId, "afterInteractionId" to note.afterInteractionId,
            "text" to note.text, "isError" to note.isError, "enabled" to note.enabled)
    },
    "activations" to document.activations.map { activation ->
        mapOf("id" to activation.id, "participantId" to activation.participantId,
            "startInteractionId" to activation.startInteractionId, "endInteractionId" to activation.endInteractionId,
            "enabled" to activation.enabled)
    },
)

private fun originKeyToMap(key: MessageOriginKey): Map<String, Any?> = mapOf(
    "entryId" to key.entryId,
    "ruleId" to key.ruleId,
    "sourceOperationId" to key.sourceOperationId,
    "sourceLogSiteId" to key.sourceLogSiteId,
    "invocationId" to key.invocationId,
    "manualInteractionId" to key.manualInteractionId,
    "generatedOrdinal" to key.generatedOrdinal,
)

private fun originKeyFromMap(map: Map<String, Any?>): MessageOriginKey? {
    val entryId = map.int("entryId") ?: return null
    return MessageOriginKey(
        entryId = entryId,
        ruleId = map.str("ruleId"),
        sourceOperationId = map.str("sourceOperationId"),
        sourceLogSiteId = map.str("sourceLogSiteId"),
        invocationId = map.str("invocationId"),
        manualInteractionId = map.str("manualInteractionId"),
        generatedOrdinal = map.int("generatedOrdinal") ?: 0,
    ).takeIf(::validOriginKey)
}

private fun traceToMap(trace: DiagramResolvedTrace): Map<String, Any?> = mapOf(
    "events" to trace.events.map { event ->
        mapOf(
            "entryId" to event.entryId, "sourceLogSiteId" to event.sourceLogSiteId,
            "methodId" to event.methodId, "ownerType" to event.ownerType, "methodName" to event.methodName,
            "sourceFile" to event.sourceFile, "sourceLine" to event.sourceLine, "laneId" to event.laneId,
            "pid" to event.pid, "tid" to event.tid, "confidence" to event.confidence,
            "evidence" to event.evidence.map { it.name }, "stale" to event.stale,
        )
    },
    "calls" to trace.calls.map { call ->
        mapOf(
            "invocationId" to call.invocationId, "callerOwnerType" to call.callerOwnerType,
            "calleeOwnerType" to call.calleeOwnerType, "callerMethodId" to call.callerMethodId,
            "calleeMethodId" to call.calleeMethodId, "callSiteId" to call.callSiteId,
            "callEntryId" to call.callEntryId, "returnEntryId" to call.returnEntryId,
            "status" to call.status.name, "invocationKind" to call.invocationKind.name,
            "callLabel" to call.callLabel, "returnLabel" to call.returnLabel, "confidence" to call.confidence,
            "evidence" to call.evidence.map { it.name }, "laneId" to call.laneId,
            "sourceFile" to call.sourceFile, "sourceLine" to call.sourceLine, "receiverRole" to call.receiverRole,
            "parentInvocationId" to call.parentInvocationId,
        )
    },
    "operations" to trace.operations.map { operation ->
        mapOf(
            "id" to operation.id, "kind" to operation.kind.name, "entryId" to operation.entryId,
            "invocationId" to operation.invocationId, "sourceOperationId" to operation.sourceOperationId,
            "sourceLogSiteId" to operation.sourceLogSiteId, "methodId" to operation.methodId,
            "ownerType" to operation.ownerType, "sourceFile" to operation.sourceFile, "sourceLine" to operation.sourceLine,
        )
    },
    "diagnostics" to mapOf(
        "droppedByReason" to trace.diagnostics.droppedByReason.mapKeys { it.key.name },
        "ambiguousEntryIds" to trace.diagnostics.ambiguousEntryIds,
        "staleEntryIds" to trace.diagnostics.staleEntryIds,
        "truncated" to trace.diagnostics.truncated,
        "entries" to trace.diagnostics.diagnostics.map { diagnostic ->
            mapOf("reason" to diagnostic.reason.name, "entryId" to diagnostic.entryId, "detail" to diagnostic.detail)
        },
    ),
)

/** Decodes a bounded JSON array field into a strict all-or-nothing list of T: null if the field is
 *  missing/malformed/oversized, or if any element fails to decode. Shared by traceFromMap and
 *  manualDocumentFromMap so neither needs its own pair of early returns per list field, keeping
 *  both under detekt's per-function return-count threshold without changing the strict contract. */
private fun <T> strictDecodedList(map: Map<String, Any?>, key: String, max: Int, decode: (Map<String, Any?>) -> T?): List<T>? {
    val maps = strictMapListOrEmpty(map, key, max) ?: return null
    val decoded = maps.map(decode)
    return if (decoded.any { it == null }) null else decoded.filterNotNull()
}

private fun traceFromMap(map: Map<String, Any?>): DiagramResolvedTrace? {
    val events = strictDecodedList(map, "events", MAX_CODEC_TRACE_EVENTS, ::traceEventFromMap) ?: return null
    val calls = strictDecodedList(map, "calls", MAX_CODEC_TRACE_CALLS, ::traceCallFromMap) ?: return null
    val operations = strictDecodedList(map, "operations", MAX_CODEC_TRACE_OPERATIONS, ::traceOperationFromMap) ?: return null
    val diagnostics = subMap(map, "diagnostics")?.let(::traceDiagnosticsFromMap) ?: return null
    return DiagramResolvedTrace(events, calls, operations, diagnostics)
}

private fun traceEventFromMap(map: Map<String, Any?>): DiagramTraceEvent? {
    val entryId = map.int("entryId") ?: return null
    val evidence = enumList<DiagramTraceEvidence>(map, "evidence") ?: return null
    return DiagramTraceEvent(
        entryId, map.str("sourceLogSiteId"), map.str("methodId"), map.str("ownerType"), map.str("methodName"),
        map.str("sourceFile"), map.int("sourceLine"), map.str("laneId") ?: "unknown", map.int("pid") ?: 0,
        map.int("tid") ?: 0, (map["confidence"] as? Number)?.toDouble() ?: 0.0, evidence.toSet(), map.bool("stale") ?: false,
    ).takeIf { it.entryId >= 0 && it.sourceLine?.let { line -> line >= 0 } != false && validTraceStrings(it) }
}

private fun traceCallFromMap(map: Map<String, Any?>): DiagramTraceCall? {
    // Decoded eagerly into locals (rather than each field's own `?: return null` inline in the
    // constructor call) so this stays under detekt's return-count threshold with one combined
    // null-check below; every field is a cheap side-effect-free map lookup, so evaluating them all
    // up front changes nothing observable versus the old short-circuiting order.
    val invocationId = map.str("invocationId")
    val caller = map.str("callerOwnerType")
    val callee = map.str("calleeOwnerType")
    val callEntryId = map.int("callEntryId")
    val label = map.str("callLabel")
    val evidence = enumList<DiagramTraceEvidence>(map, "evidence")
    val status = enumFromName<TraceCallStatus>(map.str("status"))
    val invocationKind = enumFromName<TraceInvocationKind>(map.str("invocationKind"))
    val confidence = (map["confidence"] as? Number)?.toDouble()
    // A single existence check over every required field, rather than nine individual `?: return
    // null` guards (return-count) or one long `||` chain (condition complexity) — same uniform
    // "all present or bail" shape as validOriginKey/validMessageOverride's own listOf(...).all(...)
    // checks elsewhere in this file. Each !! below is proven safe by this check.
    if (listOf<Any?>(invocationId, caller, callee, callEntryId, label, evidence, status, invocationKind, confidence).any { it == null }) {
        return null
    }
    return DiagramTraceCall(
        invocationId!!, caller!!, callee!!, map.str("callerMethodId"), map.str("calleeMethodId"), map.str("callSiteId"),
        callEntryId!!, map.int("returnEntryId"), status!!, invocationKind!!, label!!, map.str("returnLabel"),
        confidence!!, evidence!!.toSet(), map.str("laneId") ?: "unknown",
        map.str("sourceFile"), map.int("sourceLine"), map.str("receiverRole"), map.str("parentInvocationId"),
    ).takeIf { it.callEntryId >= 0 && (it.returnEntryId == null || it.returnEntryId >= 0) && validTraceStrings(it) }
}

private fun traceOperationFromMap(map: Map<String, Any?>): DiagramTraceOperation? {
    val id = map.str("id") ?: return null
    val kind = enumFromName<TraceOperationKind>(map.str("kind")) ?: return null
    val entryId = map.int("entryId") ?: return null
    return DiagramTraceOperation(
        id, kind, entryId, map.str("invocationId"), map.str("sourceOperationId"), map.str("sourceLogSiteId"),
        map.str("methodId"), map.str("ownerType"), map.str("sourceFile"), map.int("sourceLine"),
    ).takeIf { it.entryId >= 0 && it.sourceLine?.let { line -> line >= 0 } != false && validTraceStrings(it) }
}

private fun droppedByReasonFromMap(raw: Map<String, Any?>): Map<TraceDiagnosticReason, Int>? {
    val dropped = linkedMapOf<TraceDiagnosticReason, Int>()
    raw.forEach { (name, count) ->
        val reason = enumFromName<TraceDiagnosticReason>(name) ?: return null
        val value = (count as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
        dropped[reason] = value
    }
    return dropped
}

private fun traceDiagnosticsFromMap(map: Map<String, Any?>): DiagramTraceDiagnostics? {
    @Suppress("UNCHECKED_CAST")
    val rawDropped = map["droppedByReason"] as? Map<String, Any?> ?: return null
    val dropped = droppedByReasonFromMap(rawDropped) ?: return null
    val ambiguous = intList(map, "ambiguousEntryIds", MAX_CODEC_TRACE_DIAGNOSTICS)
    val stale = intList(map, "staleEntryIds", MAX_CODEC_TRACE_DIAGNOSTICS)
    val entryMaps = strictMapListOrEmpty(map, "entries", MAX_CODEC_TRACE_DIAGNOSTICS)
    if (ambiguous == null || stale == null || entryMaps == null) return null
    val entries = entryMaps.map { item ->
        val reason = enumFromName<TraceDiagnosticReason>(item.str("reason")) ?: return@map null
        DiagramTraceDiagnostic(reason, item.int("entryId"), item.str("detail"))
    }
    if (entries.any { it == null }) return null
    return DiagramTraceDiagnostics(dropped, ambiguous, stale, entries.filterNotNull(), map.bool("truncated") ?: false)
}

private inline fun <reified E : Enum<E>> enumList(map: Map<String, Any?>, key: String): List<E>? {
    val raw = map[key] as? List<*> ?: return null
    return raw.map { value -> enumFromName<E>(value as? String) ?: return null }
}

private fun intList(map: Map<String, Any?>, key: String, max: Int): List<Int>? {
    val raw = map[key] as? List<*> ?: return null
    if (raw.size > max) return null
    return raw.map { (it as? Number)?.toInt() ?: return null }
}

private fun validTraceStrings(event: DiagramTraceEvent): Boolean = listOf(
    event.sourceLogSiteId, event.methodId, event.ownerType, event.methodName, event.sourceFile, event.laneId,
).all(::validString)

private fun validTraceStrings(call: DiagramTraceCall): Boolean = listOf(
    call.invocationId, call.callerOwnerType, call.calleeOwnerType, call.callerMethodId, call.calleeMethodId,
    call.callSiteId, call.callLabel, call.returnLabel, call.laneId, call.sourceFile, call.receiverRole, call.parentInvocationId,
).all(::validString)

private fun validTraceStrings(operation: DiagramTraceOperation): Boolean = listOf(
    operation.id, operation.invocationId, operation.sourceOperationId, operation.sourceLogSiteId, operation.methodId,
    operation.ownerType, operation.sourceFile,
).all(::validString)

/** A present list must be an actual list of objects; silently dropping malformed elements would
 * make an untrusted note look valid while changing its semantic meaning. */
@Suppress("UNCHECKED_CAST")
private fun strictMapList(map: Map<String, Any?>, key: String, max: Int): List<Map<String, Any?>>? {
    val raw = map[key] as? List<*> ?: return null
    if (raw.size > max) return null
    return raw.map { it as? Map<String, Any?> ?: return null }
}

private fun strictMapListOrEmpty(map: Map<String, Any?>, key: String, max: Int): List<Map<String, Any?>>? =
    if (map.containsKey(key)) strictMapList(map, key, max) else emptyList()

private fun stringListOrEmpty(map: Map<String, Any?>, key: String, max: Int): List<String>? {
    if (!map.containsKey(key)) return emptyList()
    val raw = map[key] as? List<*> ?: return null
    if (raw.size > max) return null
    return raw.map { it as? String ?: return null }
}

private fun validString(value: String?): Boolean = value == null || value.length <= MAX_CODEC_STRING_CHARS

private fun validId(value: String): Boolean = value.isNotBlank() && validString(value)

private fun validParticipants(participants: List<DiagramParticipant>): Boolean =
    participants.size <= MAX_CODEC_PARTICIPANTS &&
        participants.all {
            validId(it.id) && validString(it.label) && validString(it.tag) && validString(it.alias) &&
                validString(it.sourceOwnerType) && validString(it.receiverRole)
        } &&
        participants.map { it.id }.toSet().size == participants.size

private fun validMessage(message: DiagramMessage, participantCount: Int): Boolean =
    message.fromIdx in 0 until participantCount &&
        message.toIdx in 0 until participantCount &&
        message.entryId >= 0 &&
        message.edgeOrdinal >= 0 &&
        message.repeatCount in 1..MAX_DIAGRAM_MESSAGES &&
        validString(message.label) && validString(message.ts) && validString(message.invocationId) &&
        validString(message.sourceOperationId) && validString(message.sourceLogSiteId) &&
        message.originKeys.size <= MAX_CODEC_MESSAGE_OVERRIDES && message.originKeys.all(::validOriginKey)

private fun validOriginKey(key: MessageOriginKey): Boolean =
    key.entryId >= 0 && key.generatedOrdinal >= 0 && listOf(
        key.ruleId, key.sourceOperationId, key.sourceLogSiteId, key.invocationId, key.manualInteractionId,
    ).all(::validString)

private fun validMessageOverride(override: DiagramMessageOverride): Boolean =
    validOriginKey(override.origin) && listOf(
        override.fromParticipantId, override.toParticipantId, override.label,
    ).all(::validString) && (override.parameters?.size ?: 0) <= MAX_CODEC_PARAMETERS &&
        override.parameters.orEmpty().all { validString(it.name) && validString(it.value) }

private fun validManualInteraction(interaction: ManualDiagramInteraction): Boolean =
    validId(interaction.id) && interaction.sourceEntryIds.all { it >= 0 } &&
        validId(interaction.fromParticipantId) && (interaction.toParticipantId == null || validId(interaction.toParticipantId)) &&
        listOf(
            interaction.operation, interaction.result, interaction.label, interaction.groupKey,
            interaction.sourceMethodId, interaction.sourceLogSiteId, interaction.sourceOwnerType,
            interaction.renderAnchorTs,
        ).all(::validString) &&
        interaction.parameters.size <= MAX_CODEC_PARAMETERS && interaction.parameters.all { validString(it.name) && validString(it.value) }

// Extracted from validManualDocument's own inline conditions so each stays under detekt's
// per-condition complexity threshold; behavior is unchanged (De Morgan's on the old any{!valid}
// checks below).
private fun manualDocumentWithinLimits(document: ManualDiagramDocument): Boolean =
    document.interactions.size <= MAX_CODEC_MANUAL_INTERACTIONS &&
        document.groups.size <= MAX_CODEC_MANUAL_GROUPS &&
        document.notes.size <= MAX_CODEC_MANUAL_NOTES &&
        document.activations.size <= MAX_CODEC_MANUAL_ACTIVATIONS &&
        document.interactions.all(::validManualInteraction)

private fun validManualGroup(group: ManualDiagramGroup, validInteractions: Set<String>): Boolean =
    validId(group.id) && validString(group.label) &&
        group.interactionIds.isNotEmpty() && group.interactionIds.all { it in validInteractions }

private fun validManualNote(note: ManualDiagramNote, validInteractions: Set<String>): Boolean =
    validId(note.id) && validId(note.participantId) &&
        note.afterInteractionId in validInteractions && validString(note.text)

private fun validManualDocument(document: ManualDiagramDocument): Boolean {
    if (!manualDocumentWithinLimits(document)) return false
    val interactions = document.interactions.map { it.id }
    if (interactions.toSet().size != interactions.size) return false
    val validInteractions = interactions.toSet()
    val groups = document.groups.map { it.id }
    if (groups.toSet().size != groups.size || !document.groups.all { validManualGroup(it, validInteractions) }) return false
    val notes = document.notes.map { it.id }
    if (notes.toSet().size != notes.size || !document.notes.all { validManualNote(it, validInteractions) }) return false
    val activations = document.activations.map { it.id }
    return activations.toSet().size == activations.size && document.activations.all {
        validId(it.id) && validId(it.participantId) && it.startInteractionId in validInteractions && it.endInteractionId in validInteractions
    }
}

private fun validFrame(frame: DiagramFrame, messageCount: Int): Boolean =
    frame.firstMsg in 0 until messageCount &&
        frame.lastMsg in 0 until messageCount &&
        frame.firstMsg <= frame.lastMsg &&
        frame.depth in 0..MAX_CODEC_PARTICIPANTS && validString(frame.label)

private fun validNote(note: DiagramNoteMark, participantCount: Int, messageCount: Int): Boolean =
    note.participantIdx in 0 until participantCount &&
        note.afterMsg in 0 until messageCount && validString(note.text)

private fun validActivation(span: DiagramActivationSpan, participantCount: Int, messageCount: Int): Boolean =
    span.participantIdx in 0 until participantCount &&
        span.startMessage in 0 until messageCount &&
        span.endMessage in 0 until messageCount && span.startMessage <= span.endMessage

private fun validComponents(components: List<DiagramComponent>, ids: List<String>): Boolean =
    ids.toSet().size == ids.size && components.all { component ->
        validId(component.id) && validString(component.displayName) &&
            component.tagIds.isNotEmpty() && component.tagIds.all(::validId) &&
            component.sourceOwnerTypes.size <= MAX_CODEC_TAG_IDS && component.sourceOwnerTypes.all(::validId)
    }

private fun validActors(actors: List<DiagramActor>, ids: List<String>, componentIds: List<String>): Boolean =
    ids.toSet().size == ids.size && actors.all { actor ->
        validId(actor.id) && validString(actor.label) &&
            validString(actor.mirrorComponentId) &&
            (actor.mirrorComponentId == null || actor.mirrorComponentId in componentIds) &&
            actor.mirrorComponentIds.size <= MAX_CODEC_COMPONENTS && actor.mirrorComponentIds.all { it in componentIds }
    }

private fun validCallOverrides(overrides: List<DiagramCallOverride>): Boolean =
    overrides.size <= MAX_CODEC_OVERRIDES && overrides.all {
        it.entryId >= 0 && it.edgeOrdinal >= 0 && validId(it.fromParticipantId) && validId(it.toParticipantId) &&
            // A rule may create an actor lazily during generation. Keep a valid correction for it
            // rather than rejecting the whole note; the builder safely ignores it if that actor is
            // not recreated by the current mode/spec.
            it.fromParticipantId.length <= MAX_CODEC_STRING_CHARS && it.toParticipantId.length <= MAX_CODEC_STRING_CHARS
    }

private fun validSourceSiteOverrides(overrides: List<DiagramSourceSiteOverride>): Boolean =
    overrides.size <= MAX_CODEC_SOURCE_OVERRIDES && overrides.all {
        it.entryId >= 0 && it.edgeOrdinal >= 0 && validId(it.sourceLogSiteId) &&
            it.sourceLogSiteId.length <= MAX_CODEC_STRING_CHARS
    }

private fun validRules(rules: List<DiagramMessageRule>): Boolean =
    rules.map { it.id }.toSet().size == rules.size && rules.all { rule ->
        validId(rule.id) && validString(rule.pattern) &&
            validString(rule.fromTemplate) && validString(rule.toTemplate) &&
            validString(rule.labelTemplate) &&
            validRuleEndpoint(rule.fromEndpoint) && validRuleEndpoint(rule.toEndpoint)
    }

private fun validRuleEndpoint(endpoint: DiagramRuleEndpoint?): Boolean = when (endpoint) {
    null, DiagramRuleEndpoint.CurrentEntry -> true
    is DiagramRuleEndpoint.ExistingParticipant -> validId(endpoint.participantId)
    is DiagramRuleEndpoint.CapturedValue ->
        validId(endpoint.captureName) && endpoint.bindings.size <= MAX_CODEC_PARAMETERS &&
            endpoint.bindings.map { it.capturedValue }.toSet().size == endpoint.bindings.size &&
            endpoint.bindings.all { validString(it.capturedValue) && validId(it.participantId) }
    is DiagramRuleEndpoint.ExplicitActor -> validId(endpoint.id) && validString(endpoint.label)
}

private fun ruleEndpointsReferenceDeclaredParticipants(
    endpoint: DiagramRuleEndpoint?,
    declaredParticipantIds: Set<String>,
): Boolean = when (endpoint) {
    null, DiagramRuleEndpoint.CurrentEntry, is DiagramRuleEndpoint.ExplicitActor -> true
    is DiagramRuleEndpoint.ExistingParticipant -> endpoint.participantId in declaredParticipantIds
    is DiagramRuleEndpoint.CapturedValue -> endpoint.bindings.all { it.participantId in declaredParticipantIds }
}

private fun manualEndpointsReferenceDeclaredParticipants(
    document: ManualDiagramDocument,
    declaredParticipantIds: Set<String>,
): Boolean = document.interactions.all {
    it.fromParticipantId in declaredParticipantIds && (it.toParticipantId == null || it.toParticipantId in declaredParticipantIds)
} && document.notes.all { it.participantId in declaredParticipantIds } &&
    document.activations.all { it.participantId in declaredParticipantIds }

// Validation is intentionally explicit so each persistence constraint remains locally visible.
@Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition")
private fun validSpec(spec: SeqDiagramSpec): Boolean {
    if (!validString(spec.title) || !validString(spec.sourceFile)) return false
    if (!validParticipants(spec.participants)) return false
    val collectionSizesAreValid = spec.components.size <= MAX_CODEC_COMPONENTS &&
        spec.actors.size <= MAX_CODEC_ACTORS && spec.rules.size <= MAX_CODEC_RULES
    if (!collectionSizesAreValid) return false
    val componentIds = spec.components.map { it.id }
    if (!validComponents(spec.components, componentIds)) return false
    val allTags = spec.components.flatMap { it.tagIds }
    if (allTags.size > MAX_CODEC_TAG_IDS || allTags.toSet().size != allTags.size) return false
    val actorIds = spec.actors.map { it.id }
    if (!validActors(spec.actors, actorIds, componentIds)) return false
    if (!validRules(spec.rules)) return false
    val declaredParticipantIds = (spec.participants.map { it.id } + componentIds + actorIds).toSet()
    if (!spec.rules.all {
            ruleEndpointsReferenceDeclaredParticipants(it.fromEndpoint, declaredParticipantIds) &&
                ruleEndpointsReferenceDeclaredParticipants(it.toEndpoint, declaredParticipantIds)
        }) return false
    if (!validCallOverrides(spec.callOverrides)) return false
    if (!validSourceSiteOverrides(spec.sourceSiteOverrides)) return false
    if (spec.lifelineOrder.size > MAX_CODEC_PARTICIPANTS || spec.lifelineOrder.toSet().size != spec.lifelineOrder.size ||
        !spec.lifelineOrder.all(::validId)) return false
    if (spec.messageOverrides.size > MAX_CODEC_MESSAGE_OVERRIDES || !spec.messageOverrides.all(::validMessageOverride)) return false
    if (!validManualDocument(spec.manualDocument)) return false
    if (!manualEndpointsReferenceDeclaredParticipants(spec.manualDocument, declaredParticipantIds)) return false
    return spec.options.maxMessages in 1..MAX_DIAGRAM_MESSAGES &&
        spec.options.labelMaxChars in 1..MAX_CODEC_STRING_CHARS &&
        spec.options.labelMaxLines in 1..MAX_LABEL_LINES &&
        spec.options.participantLabelMaxChars in 1..MAX_CODEC_STRING_CHARS &&
        spec.options.participantLabelMaxLines in 1..MAX_LABEL_LINES &&
        spec.sourceEnrichment.directCallDepth == 1
}

private fun validAttachment(attachment: DiagramAttachmentMetadata): Boolean =
    validString(attachment.diagramId) && validString(attachment.caption)

private fun participantToMap(p: DiagramParticipant): Map<String, Any?> = mapOf(
    "id" to p.id,
    "label" to p.label,
    "kind" to p.kind.name,
    "tag" to p.tag,
    "isEntryPoint" to p.isEntryPoint,
    "isExitPoint" to p.isExitPoint,
    "alias" to p.alias,
    "representation" to p.representation.name,
    "sourceOwnerType" to p.sourceOwnerType,
    "receiverRole" to p.receiverRole,
    "inferred" to p.inferred,
)

private fun optionsToMap(o: DiagramOptions): Map<String, Any?> = mapOf(
    "collapseRepeats" to o.collapseRepeats,
    "maxMessages" to o.maxMessages,
    "labelMaxChars" to o.labelMaxChars,
    "labelSource" to o.labelSource.name,
    "showTimestamps" to o.showTimestamps,
    "showElapsed" to o.showElapsed,
    "seqGroupFrames" to o.seqGroupFrames,
    "notesForErrors" to o.notesForErrors,
    "activationPolicy" to o.activationPolicy.name,
    // Appended last — see DiagramOptions' own "appended fields" note. A v1/v2/v3 note written
    // before these existed simply lacks these keys; optionsFromMap uses DiagramOptions()'s own
    // defaults below, so it decodes cleanly either way.
    "labelMaxLines" to o.labelMaxLines,
    "threadHandoffArrows" to o.threadHandoffArrows,
    "showSelfMessages" to o.showSelfMessages,
    "showSourceInferred" to o.showSourceInferred,
    "includeRowsHiddenByFilter" to o.includeRowsHiddenByFilter,
    "participantLabelMaxChars" to o.participantLabelMaxChars,
    "participantLabelMaxLines" to o.participantLabelMaxLines,
)

private fun rangeToMap(r: DiagramRange): Map<String, Any?> = when (r) {
    is DiagramRange.VisibleView -> mapOf("kind" to "visible")
    is DiagramRange.Ids -> mapOf("kind" to "ids", "from" to r.from, "to" to r.to, "selectedIds" to r.selectedIds.sorted())
    is DiagramRange.Time -> mapOf("kind" to "time", "fromTs" to r.fromTs, "toTs" to r.toTs)
    is DiagramRange.SeqGroupRef -> mapOf("kind" to "seqGroup", "gid" to r.gid)
}

// Strict optional-list decoding needs distinct rejection points to preserve legacy absence rules.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun specFromMap(map: Map<String, Any?>): SeqDiagramSpec? {
    val d = SeqDiagramSpec()
    val participantMaps = strictMapListOrEmpty(map, "participants", MAX_CODEC_PARTICIPANTS) ?: return null
    val participants = participantMaps.map(::participantFromMap)
    if (participants.any { it == null }) return null
    val safeParticipants = participants.filterNotNull()
    val componentMaps = strictMapListOrEmpty(map, "components", MAX_CODEC_COMPONENTS) ?: return null
    val explicitComponents = if (map.containsKey("components")) componentMaps.map(::componentFromMap) else null
    if (explicitComponents?.any { it == null } == true) return null
    val components = explicitComponents ?: migrateLegacyComponents(safeParticipants)
    val actorMaps = strictMapListOrEmpty(map, "actors", MAX_CODEC_ACTORS) ?: return null
    val explicitActors = if (map.containsKey("actors")) actorMaps.map(::actorFromMap) else null
    if (explicitActors?.any { it == null } == true) return null
    val actors = explicitActors?.filterNotNull() ?: safeParticipants.filter { it.kind == ParticipantKind.ACTOR }
        .map { DiagramActor(it.id, it.displayName) }
    val ruleMaps = strictMapListOrEmpty(map, "rules", MAX_CODEC_RULES) ?: return null
    val rules = ruleMaps.map(::ruleFromMap)
    if (rules.any { it == null }) return null
    val overrideMaps = strictMapListOrEmpty(map, "callOverrides", MAX_CODEC_OVERRIDES) ?: return null
    val callOverrides = overrideMaps.map(::callOverrideFromMap)
    if (callOverrides.any { it == null }) return null
    val sourceOverrideMaps = strictMapListOrEmpty(map, "sourceSiteOverrides", MAX_CODEC_SOURCE_OVERRIDES) ?: return null
    val sourceSiteOverrides = sourceOverrideMaps.map(::sourceSiteOverrideFromMap)
    if (sourceSiteOverrides.any { it == null }) return null
    val lifelineOrder = stringListOrEmpty(map, "lifelineOrder", MAX_CODEC_PARTICIPANTS) ?: return null
    val messageOverrideMaps = strictMapListOrEmpty(map, "messageOverrides", MAX_CODEC_MESSAGE_OVERRIDES) ?: return null
    val messageOverrides = messageOverrideMaps.map(::messageOverrideFromMap)
    if (messageOverrides.any { it == null }) return null
    val manualDocument = if (map.containsKey("manualDocument")) {
        subMap(map, "manualDocument")?.let(::manualDocumentFromMap) ?: return null
    } else {
        d.manualDocument
    }
    val spec = SeqDiagramSpec(
        dialect = enumFromName<DiagramDialect>(map.str("dialect")) ?: d.dialect,
        title = map.str("title") ?: d.title,
        participants = safeParticipants,
        range = subMap(map, "range")?.let(::rangeFromMap) ?: d.range,
        mode = arrowModeFromName(map.str("mode")) ?: d.mode,
        rules = rules.filterNotNull(),
        options = subMap(map, "options")?.let(::optionsFromMap) ?: d.options,
        sourceFile = map.str("sourceFile"),
        components = components.filterNotNull(),
        actors = actors,
        unmappedTagPolicy = enumFromName<UnmappedTagPolicy>(map.str("unmappedTagPolicy"))
            ?: if (safeParticipants.any { it.representation == DiagramParticipantRepresentation.OTHER }) {
                UnmappedTagPolicy.GROUP_AS_OTHER
            } else {
                d.unmappedTagPolicy
            },
        sourceEnrichment = subMap(map, "sourceEnrichment")?.let(::sourceEnrichmentFromMap) ?: d.sourceEnrichment,
        callOverrides = callOverrides.filterNotNull(),
        sourceSiteOverrides = sourceSiteOverrides.filterNotNull(),
        // New writes are always an editable manual document and intentionally omit the retired
        // authoringMode field. Keep inferred decoding only for source-only legacy notes.
        authoringMode = if (manualDocument.interactions.isNotEmpty()) DiagramAuthoringMode.MANUAL
        else enumFromName<DiagramAuthoringMode>(map.str("authoringMode")) ?: d.authoringMode,
        lifelineOrder = lifelineOrder,
        messageOverrides = messageOverrides.filterNotNull(),
        manualDocument = manualDocument,
    )
    return spec.takeIf(::validSpec)
}

private fun componentFromMap(m: Map<String, Any?>): DiagramComponent? {
    val id = m.str("id") ?: return null

    @Suppress("UNCHECKED_CAST")
    val tags = (m["tagIds"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
    val owners = (m["sourceOwnerTypes"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
    return DiagramComponent(id, m.str("displayName") ?: id, tags, m.bool("enabled") ?: true, owners)
}

private fun actorFromMap(m: Map<String, Any?>): DiagramActor? {
    val id = m.str("id") ?: return null
    val mirrors = (m["mirrorComponentIds"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
    return DiagramActor(
        id, m.str("label") ?: id, m.str("mirrorComponentId"),
        enumFromName<MirrorDirection>(m.str("mirrorDirection")) ?: MirrorDirection.BOTH, mirrors,
    )
}

private fun callOverrideFromMap(m: Map<String, Any?>): DiagramCallOverride? {
    val entryId = m.int("entryId") ?: return null
    val ordinal = m.int("edgeOrdinal") ?: return null
    val from = m.str("fromParticipantId") ?: return null
    val to = m.str("toParticipantId") ?: return null
    return DiagramCallOverride(entryId, ordinal, from, to)
}

private fun sourceSiteOverrideFromMap(m: Map<String, Any?>): DiagramSourceSiteOverride? {
    val entryId = m.int("entryId") ?: return null
    val sourceLogSiteId = m.str("sourceLogSiteId") ?: return null
    val edgeOrdinal = m.int("edgeOrdinal") ?: return null
    return DiagramSourceSiteOverride(entryId, sourceLogSiteId, edgeOrdinal)
}

private fun messageOverrideFromMap(map: Map<String, Any?>): DiagramMessageOverride? {
    val origin = subMap(map, "origin")?.let(::originKeyFromMap) ?: return null
    val parameterMaps = if (map["parameters"] != null) strictMapList(map, "parameters", MAX_CODEC_PARAMETERS) else null
    val parameters = parameterMaps?.map(::parameterFromMap)
    if (parameters?.any { it == null } == true) return null
    return DiagramMessageOverride(
        origin = origin, enabled = map.bool("enabled") ?: true,
        fromParticipantId = map.str("fromParticipantId"), toParticipantId = map.str("toParticipantId"),
        label = map.str("label"), kind = enumFromName<MessageKind>(map.str("kind")),
        parameters = parameters?.filterNotNull(),
    ).takeIf(::validMessageOverride)
}

private fun parameterFromMap(map: Map<String, Any?>): DiagramParameter? =
    DiagramParameter(map.str("name") ?: "", map.str("value") ?: "").takeIf { validString(it.name) && validString(it.value) }

private fun manualDocumentFromMap(map: Map<String, Any?>): ManualDiagramDocument? {
    val interactions = strictDecodedList(map, "interactions", MAX_CODEC_MANUAL_INTERACTIONS, ::manualInteractionFromMap) ?: return null
    val groups = strictDecodedList(map, "groups", MAX_CODEC_MANUAL_GROUPS, ::manualGroupFromMap) ?: return null
    val notes = strictDecodedList(map, "notes", MAX_CODEC_MANUAL_NOTES, ::manualNoteFromMap) ?: return null
    val activations = strictDecodedList(map, "activations", MAX_CODEC_MANUAL_ACTIVATIONS, ::manualActivationFromMap) ?: return null
    return ManualDiagramDocument(interactions, groups, notes, activations).takeIf(::validManualDocument)
}

private fun manualInteractionFromMap(map: Map<String, Any?>): ManualDiagramInteraction? {
    val id = map.str("id") ?: return null
    val entryIds = intList(map, "sourceEntryIds", MAX_DIAGRAM_MESSAGES) ?: return null
    val from = map.str("fromParticipantId") ?: return null
    val to = map.str("toParticipantId")
    val parameters = strictMapListOrEmpty(map, "parameters", MAX_CODEC_PARAMETERS)?.map(::parameterFromMap) ?: return null
    if (parameters.any { it == null }) return null
    return ManualDiagramInteraction(
        id, entryIds.toSet(), from, to, map.str("operation") ?: "", parameters.filterNotNull(), map.str("result"),
        map.str("label"), enumFromName<MessageKind>(map.str("kind")) ?: MessageKind.CALL,
        map.bool("enabled") ?: true, (map["order"] as? Number)?.toLong() ?: 0L,
        map.str("groupKey"), map.str("sourceMethodId"), map.str("sourceLogSiteId"), map.str("sourceOwnerType"),
        enumFromName<ManualOperationVisibility>(map.str("visibility")) ?: ManualOperationVisibility.UNSPECIFIED,
        map.str("renderAnchorTs"), enumFromName<LogLevel>(map.str("renderAnchorLevel")),
        enumFromName<ManualInteractionAuthoring>(map.str("authoring")) ?: ManualInteractionAuthoring.AUTO,
    ).takeIf(::validManualInteraction)
}

private fun manualGroupFromMap(map: Map<String, Any?>): ManualDiagramGroup? {
    val id = map.str("id") ?: return null
    val label = map.str("label") ?: return null
    val interactionIds = stringListOrEmpty(map, "interactionIds", MAX_CODEC_MANUAL_INTERACTIONS) ?: return null
    return ManualDiagramGroup(id, label, interactionIds, map.bool("enabled") ?: true)
}

private fun manualNoteFromMap(map: Map<String, Any?>): ManualDiagramNote? {
    val id = map.str("id") ?: return null
    val participantId = map.str("participantId") ?: return null
    val afterInteractionId = map.str("afterInteractionId") ?: return null
    val text = map.str("text") ?: return null
    return ManualDiagramNote(id, participantId, afterInteractionId, text, map.bool("isError") ?: false, map.bool("enabled") ?: true)
}

private fun manualActivationFromMap(map: Map<String, Any?>): ManualDiagramActivation? {
    val id = map.str("id") ?: return null
    val participantId = map.str("participantId") ?: return null
    val start = map.str("startInteractionId") ?: return null
    val end = map.str("endInteractionId") ?: return null
    return ManualDiagramActivation(id, participantId, start, end, map.bool("enabled") ?: true)
}

private fun sourceEnrichmentFromMap(m: Map<String, Any?>): DiagramSourceEnrichment = DiagramSourceEnrichment(
    enabled = m.bool("enabled") ?: DiagramSourceEnrichment().enabled,
    directCallDepth = 1,
    addReturnArrows = m.bool("addReturnArrows") ?: true,
)

private fun migrateLegacyComponents(participants: List<DiagramParticipant>): List<DiagramComponent> = participants
    .filter { it.kind == ParticipantKind.TAG && it.representation == DiagramParticipantRepresentation.SHOW && it.tag != null }
    .map { DiagramComponent(it.id, it.displayName, setOf(it.tag!!)) }

@Suppress("UNCHECKED_CAST")
private fun subMap(map: Map<String, Any?>, key: String): Map<String, Any?>? = map[key] as? Map<String, Any?>

private fun participantFromMap(m: Map<String, Any?>): DiagramParticipant? {
    val id = m.str("id") ?: return null
    return DiagramParticipant(
        id = id,
        label = m.str("label") ?: id,
        kind = enumFromName<ParticipantKind>(m.str("kind")) ?: ParticipantKind.TAG,
        tag = m.str("tag"),
        isEntryPoint = m.bool("isEntryPoint") ?: false,
        isExitPoint = m.bool("isExitPoint") ?: false,
        alias = m.str("alias"),
        representation = enumFromName<DiagramParticipantRepresentation>(m.str("representation"))
            ?: DiagramParticipantRepresentation.SHOW,
        sourceOwnerType = m.str("sourceOwnerType"),
        receiverRole = m.str("receiverRole"),
        inferred = m.bool("inferred") ?: false,
    )
}

private fun ruleFromMap(m: Map<String, Any?>): DiagramMessageRule? {
    val id = m.str("id") ?: return null
    val pattern = m.str("pattern") ?: return null
    val fromEndpoint = if (m["fromEndpoint"] != null) {
        subMap(m, "fromEndpoint")?.let(::ruleEndpointFromMap) ?: return null
    } else {
        null
    }
    val toEndpoint = if (m["toEndpoint"] != null) {
        subMap(m, "toEndpoint")?.let(::ruleEndpointFromMap) ?: return null
    } else {
        null
    }
    return DiagramMessageRule(
        id = id,
        pattern = pattern,
        enabled = m.bool("enabled") ?: true,
        fromTemplate = m.str("fromTemplate") ?: "",
        toTemplate = m.str("toTemplate") ?: "",
        labelTemplate = m.str("labelTemplate") ?: "",
        fromEndpoint = fromEndpoint,
        toEndpoint = toEndpoint,
    )
}

private fun ruleEndpointFromMap(map: Map<String, Any?>): DiagramRuleEndpoint? = when (map.str("kind")) {
    "existing" -> map.str("participantId")?.let(DiagramRuleEndpoint::ExistingParticipant)
    "currentEntry" -> DiagramRuleEndpoint.CurrentEntry
    "actor" -> {
        val id = map.str("id") ?: return null
        val label = map.str("label") ?: return null
        DiagramRuleEndpoint.ExplicitActor(id, label)
    }
    "captured" -> {
        val captureName = map.str("captureName") ?: return null
        val rawBindings = strictMapListOrEmpty(map, "bindings", MAX_CODEC_PARAMETERS) ?: return null
        val bindings = mutableListOf<DiagramRuleCaptureBinding>()
        for (binding in rawBindings) {
            val capturedValue = binding.str("capturedValue") ?: return null
            val participantId = binding.str("participantId") ?: return null
            bindings += DiagramRuleCaptureBinding(capturedValue, participantId)
        }
        DiagramRuleEndpoint.CapturedValue(captureName, bindings)
    }
    else -> null
}

private fun optionsFromMap(m: Map<String, Any?>): DiagramOptions {
    val d = DiagramOptions()
    return DiagramOptions(
        collapseRepeats = m.bool("collapseRepeats") ?: d.collapseRepeats,
        maxMessages = m.int("maxMessages") ?: d.maxMessages,
        labelMaxChars = m.int("labelMaxChars") ?: d.labelMaxChars,
        labelSource = enumFromName<LabelSource>(m.str("labelSource")) ?: d.labelSource,
        showTimestamps = m.bool("showTimestamps") ?: d.showTimestamps,
        showElapsed = m.bool("showElapsed") ?: d.showElapsed,
        seqGroupFrames = m.bool("seqGroupFrames") ?: d.seqGroupFrames,
        notesForErrors = m.bool("notesForErrors") ?: d.notesForErrors,
        activationPolicy = enumFromName<ActivationPolicy>(m.str("activationPolicy")) ?: d.activationPolicy,
        labelMaxLines = m.int("labelMaxLines") ?: d.labelMaxLines,
        threadHandoffArrows = m.bool("threadHandoffArrows") ?: d.threadHandoffArrows,
        showSelfMessages = m.bool("showSelfMessages") ?: d.showSelfMessages,
        showSourceInferred = m.bool("showSourceInferred") ?: d.showSourceInferred,
        includeRowsHiddenByFilter = m.bool("includeRowsHiddenByFilter") ?: d.includeRowsHiddenByFilter,
        participantLabelMaxChars = m.int("participantLabelMaxChars") ?: d.participantLabelMaxChars,
        participantLabelMaxLines = m.int("participantLabelMaxLines") ?: d.participantLabelMaxLines,
    )
}

private fun rangeFromMap(m: Map<String, Any?>): DiagramRange? = when (m.str("kind")) {
    "visible" -> DiagramRange.VisibleView
    "ids" -> {
        val from = m.int("from")
        val to = m.int("to")
        val rawSelected = m["selectedIds"] as? List<*>
        val selected = rawSelected?.mapNotNull { (it as? Number)?.toInt() }?.toSet().orEmpty()
        if (from != null && to != null && (rawSelected == null || selected.size == rawSelected.size)) {
            DiagramRange.Ids(from, to, selected)
        } else {
            null
        }
    }
    "time" -> {
        val fromTs = m.str("fromTs")
        val toTs = m.str("toTs")
        if (fromTs != null && toTs != null) DiagramRange.Time(fromTs, toTs) else null
    }
    "seqGroup" -> m.str("gid")?.let { DiagramRange.SeqGroupRef(it) }
    else -> null
}
