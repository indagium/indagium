package com.indagium.ui

import com.indagium.diagram.ParsedDiagram
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.parseDiagramNote
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.util.UUID

/** A stable identity for the log a diagram was made from.  The path is retained even when the
 * file later moves; [contentFingerprint] is the authoritative content check for regeneration. */
data class DiagramSourceIdentity(
    val sourcePath: String,
    val contentFingerprint: String,
) {
    val key: String get() = "$sourcePath\u0000$contentFingerprint"
}

/**
 * The full codec artifact is intentionally the library's payload, rather than a second, lossy
 * serialization of SeqDiagramSpec/SeqDiagram.  DiagramSpecCodec owns that format and may append
 * metadata in later versions; a library written by this layer preserves those bytes verbatim.
 */
data class DiagramLibrarySnapshot(val encodedDiagramNote: String) {
    fun parsed(): ParsedDiagram? = parseDiagramNote(encodedDiagramNote)

    companion object {
        fun create(spec: SeqDiagramSpec, source: String, model: SeqDiagram?): DiagramLibrarySnapshot =
            DiagramLibrarySnapshot(encodeDiagramNote(spec, source, model))

        fun fromDiagramNote(text: String): DiagramLibrarySnapshot? =
            text.takeIf { parseDiagramNote(it) != null }?.let(::DiagramLibrarySnapshot)
    }
}

enum class DiagramAttachmentKind { SNAPSHOT, LINK }

data class DiagramLibraryAttachment(
    val tabId: String,
    val blockId: String,
    val kind: DiagramAttachmentKind,
    val attachedAt: Long,
)

/** Persistent record for one working diagram.  [snapshot] is self-contained and therefore still
 * readable when no matching log is currently open. */
data class DiagramLibraryItem(
    val id: String,
    val title: String,
    val description: String,
    val source: DiagramSourceIdentity,
    val snapshot: DiagramLibrarySnapshot,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long = 0L,
    val attachments: List<DiagramLibraryAttachment> = emptyList(),
) {
    val parsed: ParsedDiagram? get() = snapshot.parsed()
    val isAttached: Boolean get() = attachments.isNotEmpty()
}

enum class DiagramLibraryStatus { DRAFT, ATTACHED }

/** Lightweight global-list/search result.  The diagram source/model stays on [DiagramLibraryItem]
 * and is fetched only after the user opens a result. */
data class DiagramLibrarySummary(
    val id: String,
    val title: String,
    val description: String,
    val source: DiagramSourceIdentity,
    val status: DiagramLibraryStatus,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val attachmentCount: Int,
    val participantLabels: List<String>,
    val rangeSummary: String,
)

private const val DIAGRAM_LIBRARY_MAGIC = "diagram-library-v1"
private const val DIAGRAM_LIBRARY_VERSION = 1
private const val ITEM_REQUIRED_FIELDS = 8
private const val ATTACHMENT_FIELDS = 5

private fun String.token(): String = if (isEmpty()) "~" else b64()
private fun String.value(): String = if (this == "~") "" else unb64()

private fun rangeSummary(spec: SeqDiagramSpec): String = when (val range = spec.range) {
    is com.indagium.diagram.DiagramRange.VisibleView -> "Visible view"
    is com.indagium.diagram.DiagramRange.Ids -> "IDs ${minOf(range.from, range.to)}–${maxOf(range.from, range.to)}"
    is com.indagium.diagram.DiagramRange.Time -> "${range.fromTs}–${range.toTs}"
    is com.indagium.diagram.DiagramRange.SeqGroupRef -> "Sequence ${range.gid}"
}

private fun DiagramLibraryItem.toSummary(): DiagramLibrarySummary {
    val parsed = parsed
    return DiagramLibrarySummary(
        id = id,
        title = title,
        description = description,
        source = source,
        status = if (isAttached) DiagramLibraryStatus.ATTACHED else DiagramLibraryStatus.DRAFT,
        updatedAt = updatedAt,
        lastOpenedAt = lastOpenedAt,
        attachmentCount = attachments.size,
        participantLabels = parsed?.model?.participants?.map { it.label }
            ?: parsed?.spec?.participants?.map { it.label }.orEmpty(),
        rangeSummary = parsed?.let { rangeSummary(it.spec) } ?: "Unavailable diagram data",
    )
}

/**
 * Small, corruption-tolerant app-data store for saved sequence diagrams.  It deliberately uses a
 * line format: a damaged item or attachment is ignored in isolation, while unaffected records
 * remain accessible.  Writes replace the complete file atomically.
 */
class DiagramLibraryStore(private val file: File = File(DesktopStorage.appDataDir(), DIAGRAM_LIBRARY_MAGIC)) {
    private val lock = Any()
    private var items: MutableMap<String, DiagramLibraryItem> = loadRecords().toMutableMap()

    fun all(): List<DiagramLibraryItem> = synchronized(lock) { items.values.sortedByDescending { it.updatedAt } }

    fun get(id: String): DiagramLibraryItem? = synchronized(lock) { items[id] }

    fun forSource(source: DiagramSourceIdentity): List<DiagramLibraryItem> = synchronized(lock) {
        items.values.filter { it.source == source }.sortedByDescending { it.updatedAt }
    }

    /** Search is intentionally global; optional source scoping is a UI filter, not a separate
     * library.  It covers title/description, provenance, aliases/tags and rule text in the codec
     * source, without requiring the source log to still exist. */
    fun search(query: String = "", source: DiagramSourceIdentity? = null): List<DiagramLibrarySummary> = synchronized(lock) {
        val needle = query.trim().lowercase()
        items.values.asSequence()
            .filter { source == null || it.source == source }
            .map { it to it.toSummary() }
            .filter { (item, summary) ->
                needle.isBlank() || listOf(
                    summary.title, summary.description, summary.source.sourcePath,
                    summary.source.contentFingerprint, summary.participantLabels.joinToString(" "),
                    item.snapshot.encodedDiagramNote,
                ).any { it.lowercase().contains(needle) }
            }
            .map { it.second }
            .sortedWith(compareByDescending<DiagramLibrarySummary> { it.lastOpenedAt }.thenByDescending { it.updatedAt })
            .toList()
    }

    fun recent(limit: Int = 20): List<DiagramLibrarySummary> = synchronized(lock) {
        items.values.map { it.toSummary() }
            .filter { it.lastOpenedAt > 0L }
            .sortedByDescending { it.lastOpenedAt }
            .take(limit.coerceAtLeast(0))
    }

    fun save(item: DiagramLibraryItem): DiagramLibraryItem = synchronized(lock) {
        require(item.id.isNotBlank()) { "Diagram library id must not be blank" }
        require(item.snapshot.parsed() != null) { "A diagram library snapshot must be a valid DiagramSpecCodec note" }
        items[item.id] = item
        persist()
        item
    }

    fun create(
        title: String,
        description: String,
        source: DiagramSourceIdentity,
        snapshot: DiagramLibrarySnapshot,
        now: Long = System.currentTimeMillis(),
    ): DiagramLibraryItem = save(
        DiagramLibraryItem(UUID.randomUUID().toString(), title, description, source, snapshot, now, now),
    )

    fun update(id: String, transform: (DiagramLibraryItem) -> DiagramLibraryItem): DiagramLibraryItem? = synchronized(lock) {
        val old = items[id] ?: return@synchronized null
        val updated = transform(old).copy(id = id)
        require(updated.snapshot.parsed() != null) { "A diagram library snapshot must be a valid DiagramSpecCodec note" }
        items[id] = updated
        persist()
        updated
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (items.remove(id) == null) return@synchronized false
        persist()
        true
    }

    fun markOpened(id: String, now: Long = System.currentTimeMillis()): DiagramLibraryItem? =
        update(id) { it.copy(lastOpenedAt = now) }

    fun addAttachment(id: String, attachment: DiagramLibraryAttachment): DiagramLibraryItem? = update(id) { item ->
        // Confirm may be retried after an overwrite prompt; preserve one reference per note block.
        item.copy(
            updatedAt = System.currentTimeMillis(),
            attachments = item.attachments.filterNot { it.tabId == attachment.tabId && it.blockId == attachment.blockId } + attachment,
        )
    }

    fun removeAttachment(id: String, tabId: String, blockId: String): DiagramLibraryItem? = update(id) { item ->
        item.copy(
            updatedAt = System.currentTimeMillis(),
            attachments = item.attachments.filterNot { it.tabId == tabId && it.blockId == blockId },
        )
    }

    private fun persist() {
        writeFileAtomically(file) { writer ->
            writer.appendLine(DIAGRAM_LIBRARY_MAGIC)
            writer.appendLine("version\t$DIAGRAM_LIBRARY_VERSION")
            items.values.sortedBy { it.id }.forEach { item ->
                writer.appendLine(
                    listOf(
                        "item", item.id.token(), item.title.token(), item.description.token(),
                        item.source.sourcePath.token(), item.source.contentFingerprint.token(),
                        item.snapshot.encodedDiagramNote.token(), item.createdAt, item.updatedAt, item.lastOpenedAt,
                    ).joinToString("\t"),
                )
                item.attachments.forEach { attachment ->
                    writer.appendLine(
                        listOf(
                            "attachment", item.id.token(), attachment.tabId.token(), attachment.blockId.token(),
                            attachment.kind.name, attachment.attachedAt,
                        ).joinToString("\t"),
                    )
                }
            }
        }
    }

    private fun loadRecords(): Map<String, DiagramLibraryItem> {
        if (!file.isFile) return emptyMap()
        val lines = runCatching { file.readLines() }.getOrNull().orEmpty()
        if (lines.firstOrNull() != DIAGRAM_LIBRARY_MAGIC) return emptyMap()
        val version = lines.drop(1).firstOrNull { it.startsWith("version\t") }?.substringAfter('\t')?.toIntOrNull()
        if (version != DIAGRAM_LIBRARY_VERSION) return emptyMap()

        val loaded = linkedMapOf<String, DiagramLibraryItem>()
        val pendingAttachments = mutableMapOf<String, MutableList<DiagramLibraryAttachment>>()
        lines.drop(1).forEach { line -> runCatching {
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "item" -> parseItem(fields)?.let { loaded[it.id] = it }
                "attachment" -> parseAttachment(fields)?.let { (id, attachment) ->
                    pendingAttachments.getOrPut(id, ::mutableListOf) += attachment
                }
            }
        } }
        return loaded.mapValues { (id, item) -> item.copy(attachments = pendingAttachments[id].orEmpty()) }
    }

    private fun parseItem(fields: List<String>): DiagramLibraryItem? {
        // type + eight required fields; lastOpenedAt was appended and safely defaults for early
        // v1 records that predate the Recent surface.
        if (fields.size < ITEM_REQUIRED_FIELDS + 1) return null
        val snapshot = DiagramLibrarySnapshot(fields[6].value())
        if (snapshot.parsed() == null) return null
        return DiagramLibraryItem(
            id = fields[1].value(), title = fields[2].value(), description = fields[3].value(),
            source = DiagramSourceIdentity(fields[4].value(), fields[5].value()), snapshot = snapshot,
            createdAt = fields[7].toLong(), updatedAt = fields[8].toLong(),
            lastOpenedAt = fields.getOrNull(9)?.toLongOrNull() ?: 0L,
        ).takeIf { it.id.isNotBlank() }
    }

    private fun parseAttachment(fields: List<String>): Pair<String, DiagramLibraryAttachment>? {
        if (fields.size < ATTACHMENT_FIELDS + 1) return null
        val kind = enumValues<DiagramAttachmentKind>().firstOrNull { it.name == fields[4] } ?: return null
        return fields[1].value() to DiagramLibraryAttachment(
            tabId = fields[2].value(), blockId = fields[3].value(), kind = kind, attachedAt = fields[5].toLong(),
        )
    }
}
