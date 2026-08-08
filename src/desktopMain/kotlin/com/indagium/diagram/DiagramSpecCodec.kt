package com.indagium.diagram

import com.indagium.debug.Json
import com.indagium.debug.bool
import com.indagium.debug.int
import com.indagium.debug.mapList
import com.indagium.debug.str
import com.indagium.model.LogLevel

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
private const val SUPPORTED_SPEC_VERSION = "v1"

private fun fenceLanguage(dialect: DiagramDialect): String = when (dialect) {
    DiagramDialect.MERMAID -> "mermaid"
    DiagramDialect.PLANTUML -> "plantuml"
}

/** [model], when supplied, is recorded in the header so the note can render its own picture (and
 *  keep its clickable log-line links) later without the original log being attached — see
 *  modelToMap's doc for why the fenced text alone is not enough. */
fun encodeDiagramNote(spec: SeqDiagramSpec, source: String, model: SeqDiagram? = null): String {
    val json = Json.encode(specToMap(spec) + mapOf("model" to model?.let(::modelToMap)))
    val lang = fenceLanguage(spec.dialect)
    return buildString {
        append(MARKER_HEAD).append(SUPPORTED_SPEC_VERSION).append(' ').append(json).append(MARKER_TAIL).append('\n')
        append("```").append(lang).append('\n')
        append(source.trimEnd('\n')).append('\n')
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
    // Index into the ORIGINAL text one past the header comment's closing "-->" (and its trailing
    // newline, if any) — stripDiagramSpecHeader's whole implementation is `text.substring(this)`.
    val headerEndIndex: Int,
    // The fenced block's full extent in the ORIGINAL text, opening ``` through closing ``` line
    // inclusive.
    val fenceRange: IntRange,
)

/** Parses a diagram note produced by [encodeDiagramNote]. Returns null — never throws — for
 *  anything not a complete, version-[SUPPORTED_SPEC_VERSION] header immediately followed by a
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
    if (version != SUPPORTED_SPEC_VERSION) return null
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

    return ParsedDiagram(
        spec = spec,
        source = source,
        dialect = spec.dialect,
        model = subMap(map, "model")?.let { modelFromMap(it, spec) },
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
    return SeqDiagram(
        spec = spec,
        participants = participants,
        messages = messages,
        frames = frames,
        notes = notes,
        truncated = map.bool("truncated") ?: false,
        scannedEntries = map.int("scanned") ?: 0,
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
