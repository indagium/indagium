package com.openlog.cases

import com.openlog.debug.AppLogger
import com.openlog.model.AnnBlock
import com.openlog.ui.annotationsFromToken
import com.openlog.ui.tokenFields
import java.io.File

/** Builds a [CaseIndex] by enumerating `.md`/`.ann` note pairs across a set of directories and
 *  parsing each into a [CaseRecord] — pure, no AppState dependency (mirrors
 *  [com.openlog.source.SourceIndexer]'s shape). Every base name having an `.ann` AND/OR a `.md`
 *  is indexed, so a note whose `.ann` was hand-copied into a notes folder (with or without a
 *  paired `.md`) is still discovered. */
object CaseIndexer {
    private val NOTE_EXTENSIONS = setOf("md", "ann")

    fun build(dirs: List<File>): CaseIndex {
        val records = mutableListOf<CaseRecord>()
        val fileMeta = mutableMapOf<String, CaseFileMeta>()
        dirs.distinctBy { it.absolutePath }.forEach { dir ->
            enumerateBaseNames(dir).forEach { baseName ->
                runCatching { buildRecord(dir, baseName) }
                    .onSuccess { built ->
                        if (built != null) {
                            records += built.first
                            fileMeta[built.first.backingPath] = built.second
                        }
                    }
                    .onFailure { e -> AppLogger.error("case-index", "Failed to index note $baseName in ${dir.absolutePath}", e) }
            }
        }
        return CaseIndex(
            version = CASE_INDEX_VERSION,
            records = records,
            fileMeta = fileMeta,
            builtAt = System.currentTimeMillis(),
        )
    }

    /** Base names (filename without extension) under [dir] that have a `.md` and/or `.ann`
     *  sibling — cheap, directory-listing only, no file content read. Used both by [build] and by
     *  CaseSearch's per-search auto-rescan to detect added/removed notes without re-parsing
     *  everything. */
    fun enumerateBaseNames(dir: File): List<String> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.extension.lowercase() in NOTE_EXTENSIONS }
            .map { it.nameWithoutExtension }
            .distinct()
            .toList()
    }

    /** The single file whose mtime/size drives staleness for (dir, baseName): `.ann` preferred,
     *  else `.md`, else null when neither exists (already removed since the last enumeration). */
    fun backingFileFor(dir: File, baseName: String): File? {
        val ann = File(dir, "$baseName.ann")
        if (ann.isFile) return ann
        val md = File(dir, "$baseName.md")
        return md.takeIf { it.isFile }
    }

    /** Full parse of one note into a [CaseRecord] + its [CaseFileMeta] — the "expensive" path,
     *  only run for notes [CaseSearch] has determined are new or changed since the last index. */
    fun buildRecord(dir: File, baseName: String): Pair<CaseRecord, CaseFileMeta>? {
        val mdFile = File(dir, "$baseName.md")
        val annFile = File(dir, "$baseName.ann")
        val mdExists = mdFile.isFile
        val annText = if (annFile.isFile) runCatching { annFile.readText() }.getOrNull() else null
        if (!mdExists && annText == null) return null

        val annotations = annText?.annotationsFromToken()
        // Field index 4 (sourcePath) is not part of the Annotations model itself — it's read
        // straight off the raw token, same as AppState.readSourceFingerprint does.
        val sourcePath = annText?.tokenFields()?.getOrNull(4)?.takeIf { it.isNotBlank() }

        val mdText = if (mdExists) runCatching { mdFile.readText() }.getOrNull() else null
        val title = extractTitle(mdText) ?: baseName

        val issueDescription = annotations?.issueDescription.orEmpty()
        val appVersion = annotations?.appVersion.orEmpty()
        val decisiveTags = annotations?.decisiveTags.orEmpty()

        val logRefEntries = annotations?.blocks.orEmpty()
            .filterIsInstance<AnnBlock.LogRef>()
            .flatMap { it.sourceEntries.orEmpty() }
        val logRefTags = logRefEntries.map { it.tag }.filter { it.isNotBlank() }
        val referencedMessages = logRefEntries.map { it.msg }

        val tags = (decisiveTags + logRefTags).map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val noteText = mdText ?: annotations?.let(::reconstructAnnotationsText).orEmpty()
        val tokens = tokenize(
            listOf(title, issueDescription, noteText, referencedMessages.joinToString(" ")).joinToString(" "),
        )

        val backing = if (annFile.isFile) annFile else mdFile
        val record = CaseRecord(
            id = mdFile.absolutePath,
            title = title,
            issueDescription = issueDescription,
            sourcePath = sourcePath,
            appVersion = appVersion,
            decisiveTags = decisiveTags,
            tags = tags,
            tokens = tokens,
            mdPath = mdFile.takeIf { mdExists }?.absolutePath,
            annPath = annFile.takeIf { it.isFile }?.absolutePath,
            backingPath = backing.absolutePath,
        )
        return record to CaseFileMeta(mtime = backing.lastModified(), size = backing.length())
    }

    /** Full readable text for a case (get_case): the `.md` verbatim when it exists, else a
     *  reconstruction from the `.ann`'s prefix/Note blocks/suffix (a hand-copied `.ann` with no
     *  paired `.md`). Returns null only when neither file is readable. */
    fun readCaseText(record: CaseRecord): String? {
        record.mdPath?.let { path -> runCatching { File(path).readText() }.getOrNull()?.let { return it } }
        val annPath = record.annPath ?: return null
        val annotations = runCatching { File(annPath).readText() }.getOrNull()?.annotationsFromToken() ?: return null
        return reconstructAnnotationsText(annotations)
    }

    private fun extractTitle(mdText: String?): String? = mdText
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("#") }
        ?.trimStart('#')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
