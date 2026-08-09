package com.indagium.diagram

import com.indagium.debug.Json
import com.indagium.debug.bool
import com.indagium.debug.int
import com.indagium.debug.mapList
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
private const val CURRENT_SPEC_VERSION = "v2"

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
    val normalizedSnapshot = snapshot ?: DiagramNoteSnapshot(normalizedSource, sourceHash, model)
    val json = Json.encode(
        specToMap(spec) + mapOf(
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
 *  function) and must degrade to "not a diagram note", never crash a Notes-panel render. */
fun parseDiagramNote(text: String): ParsedDiagram? {
    val trimmed = text.trimStart()
    val leadingWs = text.length - trimmed.length
    if (!trimmed.startsWith(MARKER_HEAD)) return null
    val afterHead = trimmed.substring(MARKER_HEAD.length)
    val spaceIdx = afterHead.indexOf(' ')
    if (spaceIdx <= 0) return null
    val version = afterHead.substring(0, spaceIdx)
    if (version != LEGACY_SPEC_VERSION && version != CURRENT_SPEC_VERSION) return null
    val rest = afterHead.substring(spaceIdx + 1)
    val tailIdx = rest.indexOf(MARKER_TAIL)
    if (tailIdx < 0) return null
    val jsonText = rest.substring(0, tailIdx)
    @Suppress("UNCHECKED_CAST")
    val map = runCatching { Json.decode(jsonText) }.getOrNull() as? Map<String, Any?> ?: return null
    val spec = specFromMap(map)

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

    val declaredHash = map.str("sourceHash")
    val snapshot = if (version == CURRENT_SPEC_VERSION) subMap(map, "snapshot")?.let { snapshotFromMap(it, spec) } else null
    // v1 did not carry a source fingerprint and remains trusted for backward compatibility. v2
    // only exposes its model when the header's hash and snapshot both describe the visible fence.
    val hashMatches = if (version == CURRENT_SPEC_VERSION) declaredHash != null && declaredHash == diagramSourceHash(source) else null
    val snapshotMatchesHeader = snapshot?.let { it.sourceHash == declaredHash && it.source == source } == true
    val model = when {
        version == LEGACY_SPEC_VERSION -> subMap(map, "model")?.let { modelFromMap(it, spec) }
        hashMatches == true && snapshotMatchesHeader -> snapshot.model
        else -> null
    }
    val warning = if (version == CURRENT_SPEC_VERSION && model == null && snapshot?.model != null) {
        "Diagram source has changed since this snapshot was generated; showing source only."
    } else {
        null
    }
    return ParsedDiagram(
        spec = spec,
        source = source,
        dialect = spec.dialect,
        model = model,
        sourceHashMatches = hashMatches,
        sourceHash = declaredHash,
        attachment = subMap(map, "attachment")?.let(::attachmentFromMap),
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

private fun modelToMap(d: SeqDiagram): Map<String, Any?> = mapOf(
    "participants" to d.participants.map(::participantToMap),
    "messages" to d.messages.map { m ->
        mapOf(
            "f" to m.fromIdx, "t" to m.toIdx, "l" to m.label, "e" to m.entryId,
            "ts" to m.ts, "v" to m.level.name, "k" to m.kind.name, "r" to m.repeatCount,
        )
    },
    "frames" to d.frames.map { f ->
        mapOf("l" to f.label, "c" to f.colorArgb, "a" to f.firstMsg, "b" to f.lastMsg, "d" to f.depth)
    },
    "notes" to d.notes.map { n ->
        mapOf("p" to n.participantIdx, "a" to n.afterMsg, "t" to n.text, "e" to n.isError)
    },
    "truncated" to d.truncated,
    "scanned" to d.scannedEntries,
    "coverage" to mapOf(
        "scanned" to d.coverage.scannedEntries,
        "shown" to d.coverage.shownEntries,
        "grouped" to d.coverage.groupedEntries,
        "hidden" to d.coverage.hiddenEntries,
    ),
)

/** Rebuilds the model recorded by [modelToMap]. [spec] is threaded back in rather than stored
 *  twice — it is already the header's top-level payload. Returns null when the record is absent or
 *  carries no participants, so a caller falls back to "text-only diagram note" rather than
 *  rendering an empty picture. */
private fun modelFromMap(map: Map<String, Any?>, spec: SeqDiagramSpec): SeqDiagram? {
    val participants = map.mapList("participants")?.mapNotNull(::participantFromMap).orEmpty()
    if (participants.isEmpty()) return null
    val messages = map.mapList("messages")?.mapNotNull { m ->
        val from = m.int("f") ?: return@mapNotNull null
        val to = m.int("t") ?: return@mapNotNull null
        DiagramMessage(
            fromIdx = from, toIdx = to,
            label = m.str("l") ?: "",
            entryId = m.int("e") ?: 0,
            ts = m.str("ts") ?: "",
            level = enumFromName<LogLevel>(m.str("v")) ?: LogLevel.I,
            kind = enumFromName<MessageKind>(m.str("k")) ?: MessageKind.CALL,
            repeatCount = m.int("r") ?: 1,
        )
    }.orEmpty()
    val frames = map.mapList("frames")?.mapNotNull { f ->
        val a = f.int("a") ?: return@mapNotNull null
        val b = f.int("b") ?: return@mapNotNull null
        DiagramFrame(f.str("l") ?: "", f.int("c"), a, b, f.int("d") ?: 0)
    }.orEmpty()
    val notes = map.mapList("notes")?.mapNotNull { n ->
        val p = n.int("p") ?: return@mapNotNull null
        val a = n.int("a") ?: return@mapNotNull null
        DiagramNoteMark(p, a, n.str("t") ?: "", n.bool("e") ?: false)
    }.orEmpty()
    val scannedEntries = map.int("scanned") ?: 0
    val coverageMap = subMap(map, "coverage")
    return SeqDiagram(
        spec = spec,
        participants = participants,
        messages = messages,
        frames = frames,
        notes = notes,
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
    )
}

private fun specToMap(spec: SeqDiagramSpec): Map<String, Any?> = mapOf(
    "dialect" to spec.dialect.name,
    "title" to spec.title,
    "participants" to spec.participants.map(::participantToMap),
    "range" to rangeToMap(spec.range),
    "mode" to spec.mode.name,
    "rules" to spec.rules.map(::ruleToMap),
    "options" to optionsToMap(spec.options),
    "sourceFile" to spec.sourceFile,
)

private fun participantToMap(p: DiagramParticipant): Map<String, Any?> = mapOf(
    "id" to p.id,
    "label" to p.label,
    "kind" to p.kind.name,
    "tag" to p.tag,
    "isEntryPoint" to p.isEntryPoint,
    "isExitPoint" to p.isExitPoint,
    "alias" to p.alias,
    "representation" to p.representation.name,
)

private fun ruleToMap(r: DiagramMessageRule): Map<String, Any?> = mapOf(
    "id" to r.id,
    "pattern" to r.pattern,
    "enabled" to r.enabled,
    "fromTemplate" to r.fromTemplate,
    "toTemplate" to r.toTemplate,
    "labelTemplate" to r.labelTemplate,
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
)

private fun rangeToMap(r: DiagramRange): Map<String, Any?> = when (r) {
    is DiagramRange.VisibleView -> mapOf("kind" to "visible")
    is DiagramRange.Ids -> mapOf("kind" to "ids", "from" to r.from, "to" to r.to)
    is DiagramRange.Time -> mapOf("kind" to "time", "fromTs" to r.fromTs, "toTs" to r.toTs)
    is DiagramRange.SeqGroupRef -> mapOf("kind" to "seqGroup", "gid" to r.gid)
}

private fun specFromMap(map: Map<String, Any?>): SeqDiagramSpec {
    val d = SeqDiagramSpec()
    return SeqDiagramSpec(
        dialect = enumFromName<DiagramDialect>(map.str("dialect")) ?: d.dialect,
        title = map.str("title") ?: d.title,
        participants = map.mapList("participants")?.mapNotNull(::participantFromMap) ?: d.participants,
        range = subMap(map, "range")?.let(::rangeFromMap) ?: d.range,
        mode = enumFromName<ArrowMode>(map.str("mode")) ?: d.mode,
        rules = map.mapList("rules")?.mapNotNull(::ruleFromMap) ?: d.rules,
        options = subMap(map, "options")?.let(::optionsFromMap) ?: d.options,
        sourceFile = map.str("sourceFile"),
    )
}

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
    )
}

private fun ruleFromMap(m: Map<String, Any?>): DiagramMessageRule? {
    val id = m.str("id") ?: return null
    val pattern = m.str("pattern") ?: return null
    return DiagramMessageRule(
        id = id,
        pattern = pattern,
        enabled = m.bool("enabled") ?: true,
        fromTemplate = m.str("fromTemplate") ?: "",
        toTemplate = m.str("toTemplate") ?: "",
        labelTemplate = m.str("labelTemplate") ?: "",
    )
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
    )
}

private fun rangeFromMap(m: Map<String, Any?>): DiagramRange? = when (m.str("kind")) {
    "visible" -> DiagramRange.VisibleView
    "ids" -> {
        val from = m.int("from")
        val to = m.int("to")
        if (from != null && to != null) DiagramRange.Ids(from, to) else null
    }
    "time" -> {
        val fromTs = m.str("fromTs")
        val toTs = m.str("toTs")
        if (fromTs != null && toTs != null) DiagramRange.Time(fromTs, toTs) else null
    }
    "seqGroup" -> m.str("gid")?.let { DiagramRange.SeqGroupRef(it) }
    else -> null
}
