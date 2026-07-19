package com.openlog.cases

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.ui.annotationsToken
import java.io.File

/** Writes a synthetic `<baseName>.md` + `<baseName>.ann` pair into [dir], shaped like a real
 *  saved analysis note (see AppState.autoExportAnnotations/saveAnalysis), for CaseIndexer/
 *  CaseSearch/CaseIndexStore tests. Returns the (md, ann) file pair. */
internal fun writeCaseNote(
    dir: File,
    baseName: String,
    title: String,
    issueDescription: String,
    tags: List<String> = emptyList(),
    decisiveTags: List<String> = emptyList(),
    appVersion: String = "",
    sourcePath: String? = null,
    extraMdText: String = "",
    writeMd: Boolean = true,
): Pair<File?, File> {
    val md = File(dir, "$baseName.md")
    if (writeMd) {
        md.writeText("# $title\n\n$extraMdText")
    }
    val logRefEntries = tags.mapIndexed { i, tag -> LogEntry(i, "10:00:00.000", LogLevel.I, tag, "evidence for $tag") }
    val annotations = Annotations(
        blocks = if (logRefEntries.isNotEmpty()) {
            listOf(AnnBlock.LogRef("r1", logRefEntries.map { it.id }, "evidence", sourceEntries = logRefEntries))
        } else {
            emptyList()
        },
        issueDescription = issueDescription,
        appVersion = appVersion,
        decisiveTags = decisiveTags,
    )
    val ann = File(dir, "$baseName.ann")
    ann.writeText(annotations.annotationsToken(sourcePath))
    return (md.takeIf { writeMd }) to ann
}
