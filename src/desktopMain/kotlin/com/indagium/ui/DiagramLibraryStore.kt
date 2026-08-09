package com.indagium.ui

import com.indagium.diagram.ParsedDiagram
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.parseDiagramNote
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.util.Base64
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
internal const val MAX_DIAGRAM_LIBRARY_FILE_BYTES = 64L * 1024L * 1024L
internal const val MAX_DIAGRAM_LIBRARY_LINE_CHARS = 6 * 1024 * 1024
internal const val MAX_DIAGRAM_LIBRARY_ITEMS = 128
internal const val MAX_DIAGRAM_LIBRARY_ATTACHMENTS = 4_096
internal const val MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM = 256
internal const val MAX_DIAGRAM_LIBRARY_ID_CHARS = 256
internal const val MAX_DIAGRAM_LIBRARY_TITLE_CHARS = 512
internal const val MAX_DIAGRAM_LIBRARY_DESCRIPTION_CHARS = 16 * 1024
internal const val MAX_DIAGRAM_LIBRARY_SOURCE_PATH_CHARS = 16 * 1024
internal const val MAX_DIAGRAM_LIBRARY_FINGERPRINT_CHARS = 1_024
internal const val MAX_DIAGRAM_LIBRARY_SNAPSHOT_CHARS = 3 * 1024 * 1024
private const val MAX_DIAGRAM_LIBRARY_LINES = 8_192
private const val MAX_RECORD_FIELDS = 11
private const val MAX_ID_TOKEN_BYTES = 1_024
private const val MAX_TITLE_TOKEN_BYTES = 2_048
private const val MAX_DESCRIPTION_TOKEN_BYTES = 64 * 1024
private const val MAX_SOURCE_PATH_TOKEN_BYTES = 64 * 1024
private const val MAX_FINGERPRINT_TOKEN_BYTES = 4 * 1024
private const val MAX_SNAPSHOT_TOKEN_BYTES = 4 * 1024 * 1024
private const val PERSISTED_RECORD_OVERHEAD_BYTES = 256L

private fun String.token(): String = if (isEmpty()) "~" else b64()

private fun maxBase64Chars(decodedBytes: Int): Int = ((decodedBytes + 2) / 3) * 4

private fun String.boundedValue(maxChars: Int, maxBytes: Int): String? {
    if (this == "~") return ""
    if (length > maxBase64Chars(maxBytes)) return null
    val bytes = runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull() ?: return null
    if (bytes.size > maxBytes) return null
    return String(bytes, Charsets.UTF_8).takeIf { it.length <= maxChars }
}

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
    fun search(query: String = "", source: DiagramSourceIdentity? = null): List<DiagramLibrarySummary> =
        synchronized(lock) {
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
        requireValidItem(item)
        val updated = LinkedHashMap(items).apply { put(item.id, item) }
        requireValidLibrary(updated.values)
        items = updated
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

    fun update(id: String, transform: (DiagramLibraryItem) -> DiagramLibraryItem): DiagramLibraryItem? =
        synchronized(lock) {
            val old = items[id] ?: return@synchronized null
            val updated = transform(old).copy(id = id)
            requireValidItem(updated)
            val updatedItems = LinkedHashMap(items).apply { put(id, updated) }
            requireValidLibrary(updatedItems.values)
            items = updatedItems
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

    private fun requireValidItem(item: DiagramLibraryItem) {
        require(item.id.isNotBlank() && item.id.length <= MAX_DIAGRAM_LIBRARY_ID_CHARS) {
            "Diagram library id must contain 1..$MAX_DIAGRAM_LIBRARY_ID_CHARS characters"
        }
        require(item.title.length <= MAX_DIAGRAM_LIBRARY_TITLE_CHARS) { "Diagram library title is too long" }
        require(item.description.length <= MAX_DIAGRAM_LIBRARY_DESCRIPTION_CHARS) { "Diagram library description is too long" }
        require(item.source.sourcePath.length <= MAX_DIAGRAM_LIBRARY_SOURCE_PATH_CHARS) { "Diagram source path is too long" }
        require(item.source.contentFingerprint.length <= MAX_DIAGRAM_LIBRARY_FINGERPRINT_CHARS) {
            "Diagram source fingerprint is too long"
        }
        require(item.snapshot.encodedDiagramNote.length <= MAX_DIAGRAM_LIBRARY_SNAPSHOT_CHARS) {
            "Diagram library snapshot is too large"
        }
        require(item.snapshot.encodedDiagramNote.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_TOKEN_BYTES) {
            "Diagram library snapshot uses too many encoded bytes"
        }
        require(item.snapshot.parsed() != null) { "A diagram library snapshot must be a valid DiagramSpecCodec note" }
        require(item.attachments.size <= MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM) {
            "A diagram library item has too many attachments"
        }
        item.attachments.forEach(::requireValidAttachment)
    }

    private fun requireValidAttachment(attachment: DiagramLibraryAttachment) {
        require(attachment.tabId.isNotBlank() && attachment.tabId.length <= MAX_DIAGRAM_LIBRARY_ID_CHARS) {
            "Diagram attachment tab id must be non-blank and bounded"
        }
        require(attachment.blockId.isNotBlank() && attachment.blockId.length <= MAX_DIAGRAM_LIBRARY_ID_CHARS) {
            "Diagram attachment block id must be non-blank and bounded"
        }
    }

    private fun requireValidLibrary(records: Collection<DiagramLibraryItem>) {
        require(records.size <= MAX_DIAGRAM_LIBRARY_ITEMS) { "Diagram library has too many items" }
        require(records.sumOf { it.attachments.size } <= MAX_DIAGRAM_LIBRARY_ATTACHMENTS) {
            "Diagram library has too many attachments"
        }
        require(estimatedPersistedBytes(records) <= MAX_DIAGRAM_LIBRARY_FILE_BYTES) { "Diagram library is too large" }
    }

    private fun estimatedPersistedBytes(records: Collection<DiagramLibraryItem>): Long {
        var bytes = PERSISTED_RECORD_OVERHEAD_BYTES
        records.forEach { item ->
            bytes += PERSISTED_RECORD_OVERHEAD_BYTES + listOf(
                item.id, item.title, item.description, item.source.sourcePath,
                item.source.contentFingerprint, item.snapshot.encodedDiagramNote,
            ).sumOf(::estimatedTokenBytes)
            bytes += item.attachments.sumOf { attachment ->
                PERSISTED_RECORD_OVERHEAD_BYTES + estimatedTokenBytes(item.id) +
                    estimatedTokenBytes(attachment.tabId) + estimatedTokenBytes(attachment.blockId)
            }
        }
        return bytes
    }

    private fun estimatedTokenBytes(value: String): Long {
        if (value.isEmpty()) return 1L
        val utf8Bytes = value.toByteArray(Charsets.UTF_8).size.toLong()
        return ((utf8Bytes + 2L) / 3L) * 4L
    }

    private fun loadRecords(): Map<String, DiagramLibraryItem> {
        if (!file.isFile) return emptyMap()
        if (file.length() !in 1..MAX_DIAGRAM_LIBRARY_FILE_BYTES) return emptyMap()

        val loaded = linkedMapOf<String, DiagramLibraryItem>()
        val pendingAttachments = mutableMapOf<String, MutableList<DiagramLibraryAttachment>>()
        var magicSeen = false
        var version: Int? = null
        var attachmentCount = 0
        runCatching {
            forEachBoundedLine { line ->
                if (!magicSeen) {
                    magicSeen = line == DIAGRAM_LIBRARY_MAGIC
                    return@forEachBoundedLine magicSeen
                }
                val fields = line.split('\t', limit = MAX_RECORD_FIELDS)
                when (fields.firstOrNull()) {
                    "version" -> version = fields.getOrNull(1)?.toIntOrNull()
                    "item" -> if (loaded.size < MAX_DIAGRAM_LIBRARY_ITEMS) {
                        runCatching { parseItem(fields) }.getOrNull()?.let { loaded[it.id] = it }
                    }

                    "attachment" -> parseAttachment(fields)?.let { (id, attachment) ->
                        val attachments = pendingAttachments.getOrPut(id, ::mutableListOf)
                        if (attachmentCount < MAX_DIAGRAM_LIBRARY_ATTACHMENTS &&
                            attachments.size < MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM
                        ) {
                            attachments += attachment
                            attachmentCount++
                        }
                    }
                }
                true
            }
        }
        if (!magicSeen || version != DIAGRAM_LIBRARY_VERSION) return emptyMap()
        return loaded.mapValues { (id, item) -> item.copy(attachments = pendingAttachments[id].orEmpty()) }
    }

    /** Streams the file without ever constructing an oversized line or an unbounded line list.
     * Returning false from [onLine] stops early (used when the magic header is invalid). */
    private fun forEachBoundedLine(onLine: (String) -> Boolean) {
        file.inputStream().buffered().reader(Charsets.UTF_8).use { reader ->
            val line = StringBuilder()
            var lineTooLong = false
            var lineCount = 0
            var inspectedChars = 0L
            while (lineCount < MAX_DIAGRAM_LIBRARY_LINES && inspectedChars < MAX_DIAGRAM_LIBRARY_FILE_BYTES) {
                val next = reader.read()
                if (next < 0) {
                    if ((line.isNotEmpty() || lineTooLong) && !lineTooLong) onLine(line.toString())
                    return
                }
                inspectedChars++
                when (val char = next.toChar()) {
                    '\n' -> {
                        lineCount++
                        if (!lineTooLong && !onLine(line.toString())) return
                        line.setLength(0)
                        lineTooLong = false
                    }

                    '\r' -> Unit
                    else -> if (line.length < MAX_DIAGRAM_LIBRARY_LINE_CHARS) line.append(char) else lineTooLong = true
                }
            }
        }
    }

    private fun parseItem(fields: List<String>): DiagramLibraryItem? {
        // type + eight required fields; lastOpenedAt was appended and safely defaults for early
        // v1 records that predate the Recent surface.
        if (fields.size < ITEM_REQUIRED_FIELDS + 1) return null
        val decoded = decodeItemFields(fields) ?: return null
        val snapshot = DiagramLibrarySnapshot(decoded.snapshot)
        if (snapshot.parsed() == null) return null
        val createdAt = fields[7].toLongOrNull()
        val updatedAt = fields[8].toLongOrNull()
        if (createdAt == null || updatedAt == null) return null
        return DiagramLibraryItem(
            id = decoded.id, title = decoded.title, description = decoded.description,
            source = DiagramSourceIdentity(decoded.sourcePath, decoded.fingerprint), snapshot = snapshot,
            createdAt = createdAt, updatedAt = updatedAt,
            lastOpenedAt = fields.getOrNull(9)?.toLongOrNull() ?: 0L,
        ).takeIf { it.id.isNotBlank() }
    }

    private data class DecodedItemFields(
        val id: String,
        val title: String,
        val description: String,
        val sourcePath: String,
        val fingerprint: String,
        val snapshot: String,
    )

    private fun decodeItemFields(fields: List<String>): DecodedItemFields? {
        val values = listOf(
            fields[1].boundedValue(MAX_DIAGRAM_LIBRARY_ID_CHARS, MAX_ID_TOKEN_BYTES),
            fields[2].boundedValue(MAX_DIAGRAM_LIBRARY_TITLE_CHARS, MAX_TITLE_TOKEN_BYTES),
            fields[3].boundedValue(MAX_DIAGRAM_LIBRARY_DESCRIPTION_CHARS, MAX_DESCRIPTION_TOKEN_BYTES),
            fields[4].boundedValue(MAX_DIAGRAM_LIBRARY_SOURCE_PATH_CHARS, MAX_SOURCE_PATH_TOKEN_BYTES),
            fields[5].boundedValue(MAX_DIAGRAM_LIBRARY_FINGERPRINT_CHARS, MAX_FINGERPRINT_TOKEN_BYTES),
            fields[6].boundedValue(MAX_DIAGRAM_LIBRARY_SNAPSHOT_CHARS, MAX_SNAPSHOT_TOKEN_BYTES),
        )
        if (values.any { it == null }) return null
        return DecodedItemFields(
            id = values[0].orEmpty(), title = values[1].orEmpty(), description = values[2].orEmpty(),
            sourcePath = values[3].orEmpty(), fingerprint = values[4].orEmpty(), snapshot = values[5].orEmpty(),
        )
    }

    private fun parseAttachment(fields: List<String>): Pair<String, DiagramLibraryAttachment>? {
        if (fields.size < ATTACHMENT_FIELDS + 1) return null
        val kind = enumValues<DiagramAttachmentKind>().firstOrNull { it.name == fields[4] } ?: return null
        val ids = fields.slice(1..3).map { it.boundedValue(MAX_DIAGRAM_LIBRARY_ID_CHARS, MAX_ID_TOKEN_BYTES) }
        val attachedAt = fields[5].toLongOrNull()
        if (ids.any { it.isNullOrBlank() } || attachedAt == null) return null
        return ids[0].orEmpty() to DiagramLibraryAttachment(ids[1].orEmpty(), ids[2].orEmpty(), kind, attachedAt)
    }
}
