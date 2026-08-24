package com.indagium.ui

import com.indagium.diagram3.DiagramExportMode
import com.indagium.diagram3.MAX_SEQ3_HEADER_CHARS
import com.indagium.diagram3.ParsedSeq3
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.seq3NoteWithinBounds
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 * serialization of [Seq3Document]. [com.indagium.diagram3.Seq3Codec] owns that format and may
 * append metadata in later versions; a library written by this layer preserves those bytes
 * verbatim.
 */
data class DiagramLibrarySnapshot(val encodedDiagramNote: String) {
    // W3: memoized. DiagramLibraryItem.parsed (a get() below), DiagramLibraryStore.toSummary()
    // (called for EVERY item on EVERY library-list render) and requireValidItem/itemLevelRejection
    // (called on every save, sometimes twice per save — see rejectionFor's own doc) all used to
    // re-run parseSeq3Note's full header decode + body walk on every access; measured ~15ms at
    // 1,400 occurrences. A body-only `by lazy` property — never a primary-constructor property —
    // stays outside this data class's generated equals/hashCode/copy/toString, so two snapshots
    // with the same [encodedDiagramNote] remain equal regardless of which (if either) has already
    // parsed, and `copy()` never needs to know this field exists.
    private val cachedParsed: ParsedSeq3? by lazy { parseSeq3Note(encodedDiagramNote) }

    fun parsed(): ParsedSeq3? = cachedParsed

    companion object {
        fun create(
            document: Seq3Document,
            dialect: Seq3Dialect = Seq3Dialect.MERMAID,
            caption: String = "",
            exportMode: DiagramExportMode = DiagramExportMode.IMAGE,
        ): DiagramLibrarySnapshot = DiagramLibrarySnapshot(encodeSeq3Note(document, dialect, caption, exportMode))

        fun fromDiagramNote(text: String): DiagramLibrarySnapshot? =
            text.takeIf { parseSeq3Note(it) != null }?.let(::DiagramLibrarySnapshot)
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
    val parsed: ParsedSeq3? get() = snapshot.parsed()
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

internal fun rangeSummary(range: Seq3Range): String = when (range) {
    is Seq3Range.VisibleView -> "Visible view"
    is Seq3Range.Ids -> "IDs ${minOf(range.from, range.to)}–${maxOf(range.from, range.to)}"
    is Seq3Range.Time -> "${range.fromTs}–${range.toTs}"
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
        participantLabels = parsed?.document?.lifelines?.map { it.name }.orEmpty(),
        rangeSummary = parsed?.let { rangeSummary(it.document.range) } ?: "Unavailable diagram data",
    )
}

/**
 * W1c/W2: the non-throwing counterpart to [DiagramLibraryStore]'s own `require`-based validation.
 * [DiagramLibraryStore.save]/[DiagramLibraryStore.create]/[DiagramLibraryStore.update] keep
 * throwing [IllegalArgumentException] exactly as before ([Seq3LibraryStoreTest.
 * saveRejectsOversizedMetadataAndAttachmentCollections] pins that) — an escaping `require` is fine
 * for a caller that treats a rejected save as a programming error, but `Seq3Session` treats it as
 * an ordinary, user-facing "this didn't fit" outcome (an over-wide diagram, a full library), so it
 * calls [DiagramLibraryStore.trySave]/[DiagramLibraryStore.tryCreate]/[DiagramLibraryStore.
 * tryUpdate] instead, which return this type rather than throwing.
 */
sealed interface DiagramSaveRejection {
    /** The header's JSON span (or, when that scan itself fails, the whole encoded note's char
     *  count — see [DiagramLibraryStore.rejectionFor]) exceeded [limitChars]. */
    data class TooLarge(val encodedChars: Int, val limitChars: Int) : DiagramSaveRejection

    /** The library already holds [MAX_DIAGRAM_LIBRARY_ITEMS] items and this would be a NEW one —
     *  see [DiagramLibraryStore.rejectionFor]'s own doc on why updating an existing item never
     *  triggers this. */
    data object LibraryFull : DiagramSaveRejection

    /** Any other `requireValidItem`/`requireValidLibrary` check ([reason] is that check's own
     *  message) — metadata too long, too many attachments, or a snapshot that fails to parse for a
     *  reason other than its size. */
    data class Invalid(val reason: String) : DiagramSaveRejection
}

/** Thrown only internally by [DiagramLibraryStore.trySave] to carry a [DiagramSaveRejection]
 *  through a [Result.failure] — a caller should read [rejection] rather than this exception's
 *  message, which exists solely for a stray uncaught-exception log line to be readable. */
class DiagramSaveRejectedException(val rejection: DiagramSaveRejection) :
    IllegalStateException("Diagram save rejected: $rejection")

/**
 * Small, corruption-tolerant app-data store for saved sequence diagrams.  It deliberately uses a
 * line format: a damaged item or attachment is ignored in isolation, while unaffected records
 * remain accessible.  Writes replace the complete file atomically.
 */
class DiagramLibraryStore(private val file: File = File(DesktopStorage.appDataDir(), DIAGRAM_LIBRARY_MAGIC)) {
    private val lock = Any()
    private var items: MutableMap<String, DiagramLibraryItem> = loadRecords().toMutableMap()

    // W3: separate from [lock] on purpose, mirroring AutosaveScheduler's schedulingLock/writerLock
    // split (docs/SAAD.md §12.4/§12.5) — [lock] is held only long enough to compute and publish the
    // next in-memory [items] map; [writeLock] covers only the disk write itself, so a slow rewrite
    // of a large library never blocks an unrelated all()/get()/search() read for its duration. Fair
    // (matches AutosaveScheduler's own writerLock) so concurrent writers are served in arrival order
    // rather than risking starvation under contention.
    private val writeLock = ReentrantLock(true)

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

    fun save(item: DiagramLibraryItem): DiagramLibraryItem {
        // W3: only the in-memory publish needs [lock]; [persist] re-reads `items` itself once it
        // actually holds [writeLock] rather than being handed a value captured here — see that
        // function's own doc for why that distinction is the whole race-safety argument.
        synchronized(lock) {
            requireValidItem(item)
            val updated = LinkedHashMap(items).apply { put(item.id, item) }
            requireValidLibrary(updated.values)
            items = updated
        }
        persist()
        return item
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

    fun update(id: String, transform: (DiagramLibraryItem) -> DiagramLibraryItem): DiagramLibraryItem? {
        val updated = synchronized(lock) {
            val old = items[id] ?: return@synchronized null
            val updated = transform(old).copy(id = id)
            requireValidItem(updated)
            val updatedItems = LinkedHashMap(items).apply { put(id, updated) }
            requireValidLibrary(updatedItems.values)
            items = updatedItems
            updated
        } ?: return null
        persist()
        return updated
    }

    /**
     * Every check [requireValidItem]/[requireValidLibrary] make below, evaluated against what the
     * library WOULD contain after this save, but returning the first violation found instead of
     * throwing. [isNew] must reflect whether [item]'s id is already in the library — a caller adding
     * a genuinely new record (grows the count by one) is held to [MAX_DIAGRAM_LIBRARY_ITEMS]
     * differently than one replacing an existing record in place (count unchanged), which is why W2
     * ("editing an existing diagram still saves when the library is full") falls out of this
     * distinction rather than needing its own special case.
     */
    fun rejectionFor(item: DiagramLibraryItem, isNew: Boolean): DiagramSaveRejection? = synchronized(lock) {
        itemLevelRejection(item)?.let { return@synchronized it }
        // Library-wide checks: what the store would hold AFTER this save. isNew adds one record on
        // top of the current count; an update replaces one record in place (net zero), which is
        // exactly the "still saves at the cap" contract W2 asks for.
        val prospectiveCount = if (isNew) items.size + 1 else items.size
        if (prospectiveCount > MAX_DIAGRAM_LIBRARY_ITEMS) return@synchronized DiagramSaveRejection.LibraryFull
        val othersAttachments = items.values.filter { it.id != item.id }.sumOf { it.attachments.size }
        if (othersAttachments + item.attachments.size > MAX_DIAGRAM_LIBRARY_ATTACHMENTS) {
            return@synchronized DiagramSaveRejection.Invalid("Diagram library has too many attachments")
        }
        val prospectiveRecords = items.values.filter { it.id != item.id } + item
        if (estimatedPersistedBytes(prospectiveRecords) > MAX_DIAGRAM_LIBRARY_FILE_BYTES) {
            return@synchronized DiagramSaveRejection.Invalid("Diagram library is too large")
        }
        null
    }

    // Pulled out of rejectionFor purely to keep that function's own cyclomatic complexity under
    // detekt's limit (mirrors Seq3Codec.withinSeq3DocumentBounds's identical reason for existing as
    // its own function) — every check here is about [item] alone, none of them touch [items], so
    // this needs no lock of its own; the one caller already holds it.
    private fun itemLevelRejection(item: DiagramLibraryItem): DiagramSaveRejection? {
        // Snapshot bounds first: this is the actual W1 blocker (Seq3Codec's own MAX_SEQ3_HEADER_CHARS,
        // decode-only until now) — a document over that bound fails `item.snapshot.parsed() != null`
        // below regardless of every other check, so report the SIZE reason, not "invalid", when it's
        // the one that's actually true.
        val encoded = item.snapshot.encodedDiagramNote
        // Report whichever bound actually failed, not always the same constant — the codec's own
        // MAX_SEQ3_HEADER_CHARS (512 KB) is what the W1 probe actually hits; the store's own, much
        // larger MAX_DIAGRAM_LIBRARY_SNAPSHOT_CHARS (3 MB) is a second, independent ceiling. A popup
        // naming the wrong limit would tell the user the wrong number to aim under.
        if (!seq3NoteWithinBounds(encoded)) return DiagramSaveRejection.TooLarge(encoded.length, MAX_SEQ3_HEADER_CHARS)
        if (encoded.length > MAX_DIAGRAM_LIBRARY_SNAPSHOT_CHARS || encoded.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_TOKEN_BYTES) {
            return DiagramSaveRejection.TooLarge(encoded.length, MAX_DIAGRAM_LIBRARY_SNAPSHOT_CHARS)
        }
        if (item.snapshot.parsed() == null) {
            return DiagramSaveRejection.Invalid("A diagram library snapshot must be a valid diagram note")
        }
        return metadataRejection(item)
    }

    // A (violation?, message) list rather than a chain of individual `if`/`return` statements —
    // functionally identical (first true wins), but keeps this under detekt's ReturnCount limit
    // without hiding any of the individual bounds `requireValidItem` also enforces.
    private fun metadataRejection(item: DiagramLibraryItem): DiagramSaveRejection? {
        val badAttachment = item.attachments.any { attachment ->
            attachment.tabId.isBlank() || attachment.tabId.length > MAX_DIAGRAM_LIBRARY_ID_CHARS ||
                attachment.blockId.isBlank() || attachment.blockId.length > MAX_DIAGRAM_LIBRARY_ID_CHARS
        }
        val violations = listOf(
            (item.id.isBlank() || item.id.length > MAX_DIAGRAM_LIBRARY_ID_CHARS) to
                "Diagram library id must contain 1..$MAX_DIAGRAM_LIBRARY_ID_CHARS characters",
            (item.title.length > MAX_DIAGRAM_LIBRARY_TITLE_CHARS) to "Diagram library title is too long",
            (item.description.length > MAX_DIAGRAM_LIBRARY_DESCRIPTION_CHARS) to "Diagram library description is too long",
            (item.source.sourcePath.length > MAX_DIAGRAM_LIBRARY_SOURCE_PATH_CHARS) to "Diagram source path is too long",
            (item.source.contentFingerprint.length > MAX_DIAGRAM_LIBRARY_FINGERPRINT_CHARS) to "Diagram source fingerprint is too long",
            (item.attachments.size > MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM) to "A diagram library item has too many attachments",
            badAttachment to "Diagram attachment tab/block id must be non-blank and bounded",
        )
        return violations.firstOrNull { (violated, _) -> violated }?.let { (_, message) -> DiagramSaveRejection.Invalid(message) }
    }

    /** Non-throwing counterpart to [save] — see [DiagramSaveRejection]'s own doc for why this exists
     *  alongside (not instead of) the throwing API. */
    fun trySave(item: DiagramLibraryItem): Result<DiagramLibraryItem> {
        val isNew = synchronized(lock) { item.id !in items }
        val rejection = rejectionFor(item, isNew)
        if (rejection != null) return Result.failure(DiagramSaveRejectedException(rejection))
        return runCatching { save(item) }
    }

    /** Non-throwing counterpart to [create]. */
    fun tryCreate(
        title: String,
        description: String,
        source: DiagramSourceIdentity,
        snapshot: DiagramLibrarySnapshot,
        now: Long = System.currentTimeMillis(),
    ): Result<DiagramLibraryItem> = trySave(
        DiagramLibraryItem(UUID.randomUUID().toString(), title, description, source, snapshot, now, now),
    )

    /** Non-throwing counterpart to [update]. Returns null (matching [update]'s own contract) when
     *  [id] names no existing item — a rejection has nothing to report if there's no record to have
     *  rejected changing. */
    fun tryUpdate(id: String, transform: (DiagramLibraryItem) -> DiagramLibraryItem): Result<DiagramLibraryItem>? {
        val old = synchronized(lock) { items[id] } ?: return null
        return trySave(transform(old).copy(id = id))
    }

    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            if (id !in items) return@synchronized false
            items = LinkedHashMap(items).apply { remove(id) }
            true
        }
        if (!removed) return false
        persist()
        return true
    }

    fun markOpened(id: String, now: Long = System.currentTimeMillis()): DiagramLibraryItem? =
        update(id) { it.copy(lastOpenedAt = now) }

    /** W2: routed through the non-throwing [tryUpdate] rather than the throwing [update] this used
     *  to call — the same store-wide attachment caps (`MAX_DIAGRAM_LIBRARY_ATTACHMENTS`/
     *  `MAX_DIAGRAM_LIBRARY_ATTACHMENTS_PER_ITEM`) a fresh [save] can be rejected for apply here
     *  too, and this runs from a Compose event handler ([Seq3Session.attachLibraryItem]), where an
     *  escaping `IllegalArgumentException` crashes the window exactly like the original W1 blocker.
     *  Returns null exactly as before on either "no such id" or a rejected save — every existing
     *  caller already treats a null result as "the attachment wasn't recorded" via `?.`/`?:`. */
    fun addAttachment(id: String, attachment: DiagramLibraryAttachment): DiagramLibraryItem? =
        tryUpdate(id) { item ->
            // Confirm may be retried after an overwrite prompt; preserve one reference per note block.
            item.copy(
                updatedAt = System.currentTimeMillis(),
                attachments = item.attachments.filterNot { it.tabId == attachment.tabId && it.blockId == attachment.blockId } + attachment,
            )
        }?.getOrNull()

    /** Non-throwing counterpart of the old [removeAttachment] — see [addAttachment]'s doc; removing
     *  an attachment only shrinks the list, so this is nearly never actually rejected, but a
     *  snapshot that has drifted out of bounds since it was saved (e.g. a lowered
     *  `MAX_SEQ3_HEADER_CHARS` in a later build) is still possible and must not throw either. */
    fun removeAttachment(id: String, tabId: String, blockId: String): DiagramLibraryItem? =
        tryUpdate(id) { item ->
            item.copy(
                updatedAt = System.currentTimeMillis(),
                attachments = item.attachments.filterNot { it.tabId == tabId && it.blockId == blockId },
            )
        }?.getOrNull()

    /**
     * W3: the disk write — `writeFileAtomically` replaces the whole file — runs OUTSIDE [lock],
     * serialized instead by [writeLock] (mirrors `AutosaveScheduler`'s `schedulingLock`/`writerLock`
     * split; see that class's own header comment). save/update/delete each mutate `items` under
     * [lock] and THEN call this with no argument — deliberately: [items] is re-read fresh, under
     * [lock], only once this call actually holds [writeLock], not handed a value captured back at
     * mutation time. That distinction is what makes concurrent writers race free: whichever call's
     * disk write is the last of a batch to actually finish is, BY CONSTRUCTION, the last one to
     * start (a thread only reaches its own `persist()` call after its own mutation, and a "last to
     * finish" write can only be delayed behind writers whose own mutation-then-persist has therefore
     * already completed) — so its fresh read at write-time can never be older than any mutation
     * whose own write has already landed. A snapshot captured earlier (e.g. right after the CALLER's
     * own mutation, before contending for [writeLock]) would NOT have this property — a slow writer
     * could still be holding a stale pre-contention snapshot when it finally gets the lock, clobbering
     * a newer write that beat it there. The only possible outcome of the real race here is one
     * redundant identical rewrite, never a file that reverts to an older snapshot.
     */
    private fun persist() {
        writeLock.withLock {
            val snapshot = synchronized(lock) { items }
            writeFileAtomically(file) { writer ->
                writer.appendLine(DIAGRAM_LIBRARY_MAGIC)
                writer.appendLine("version\t$DIAGRAM_LIBRARY_VERSION")
                snapshot.values.sortedBy { it.id }.forEach { item ->
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
