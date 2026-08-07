package com.indagium.utils

import java.io.File

// Depth of the dropped folder itself counts as 0 — a file directly inside it is depth 1. Six
// levels comfortably covers an unpacked bug-report's typical "FS/data/misc/logd/..." shape
// without letting a pathological tree (or a symlink maze the cycle guard hasn't caught yet)
// wander arbitrarily deep.
const val MAX_FOLDER_SCAN_DEPTH = 6

// Mirrors MAX_ARCHIVE_ENTRIES_SCANNED (BugReportZip.kt) — caps how many filesystem entries (files
// AND directories) the walk will visit before giving up, so a folder with an enormous number of
// entries can't stall the scan even before any candidate is accepted.
const val MAX_FOLDER_ENTRIES_SCANNED = 20_000

// Independent from the entries-visited cap above: a folder could visit relatively few entries but
// still accept an unreasonable number of *candidates* (e.g. thousands of tiny .log files at the
// top level) — this bounds what the picker dialog would otherwise have to render.
const val MAX_FOLDER_CANDIDATES = 500

data class FolderScan(
    val logCandidates: List<ZipLogCandidate>,
    val videoCandidates: List<ZipLogCandidate>,
    val truncated: Boolean,
)

// One filesystem entry pending a visit, paired with its depth relative to the scan root (the
// root's direct children are depth 1) so the walk can enforce maxDepth without re-deriving it from
// the path.
private data class PendingDir(val dir: File, val depth: Int)

// Mutable scan state threaded through the BFS helpers below (walk/visitDirectory/visitFile).
// Pulling every counter/collection scanFolderForLogs would otherwise hold as local vars into one
// object is what lets the walk split into small, single-purpose functions instead of one large
// function — the original single-function shape tripped both detekt's cyclomatic-complexity cap
// and its "too many break/continue in one loop" rule.
private class ScanState(
    val root: File,
    val maxDepth: Int,
    val maxEntries: Int,
    val maxCandidates: Int,
    val includeHidden: Boolean,
) {
    val logs = mutableListOf<ZipLogCandidate>()
    val videos = mutableListOf<ZipLogCandidate>()
    val visitedDirs = HashSet<String>()
    val queue = ArrayDeque<PendingDir>()
    var entriesExamined = 0
    var candidatesAccepted = 0
    var truncated = false

    fun candidatesFull() = candidatesAccepted >= maxCandidates
}

/**
 * Scans [root] for log/ANR-text and video candidates, shaped exactly like [scanArchiveCandidates]
 * (BugReportZip.kt) does for an archive — reusing [candidateKind] and [isVideoEntryName] verbatim
 * so a folder of unpacked logs and an archive containing the same logs classify identically and
 * can never quietly drift apart from each other.
 *
 * Deliberately an iterative BFS over an explicit [ArrayDeque] work queue, NOT `File.walk()`/
 * `Files.walk()` — a bounded, cancellable, cycle-safe traversal needs to inspect and discard
 * children itself rather than delegate to a lazy sequence that doesn't know about maxDepth,
 * maxEntries, or the symlink-cycle guard below.
 *
 * Symlink cycles: a `HashSet` of every directory's `canonicalPath` visited so far. A symlink
 * pointing back at an ancestor (or at a sibling already queued) resolves to a canonicalPath
 * already in the set and is skipped rather than re-descended — the resolution itself is what
 * collapses "the same real directory reached two different ways" down to one entry, whether that
 * second path arrived via a symlink or an ordinary (non-cyclic) alias.
 *
 * Hidden dirs and hidden files are both skipped by default — `.git`, `.DS_Store`, and similar
 * clutter a user unpacking a bug report into a folder never intends to open, matching neither
 * listArchiveLogCandidates' behavior (an archive rarely bundles genuinely hidden entries the same
 * way a live filesystem does) nor needing to.
 *
 * Deliberately does NOT descend into archives found inside the folder — a `.zip`/`.tar.gz` sitting
 * next to unpacked logs is left as an ordinary (non-candidate, non-error) file. Recursing into it
 * here would mean silently duplicating scanArchiveCandidates' own nested-tar handling one layer
 * deeper than the picker UI (one flat list of candidates) can represent, for a case ("bug report
 * folder that itself contains an unopened archive") this app has no product need to support today.
 */
fun scanFolderForLogs(
    root: File,
    maxDepth: Int = MAX_FOLDER_SCAN_DEPTH,
    maxEntries: Int = MAX_FOLDER_ENTRIES_SCANNED,
    maxCandidates: Int = MAX_FOLDER_CANDIDATES,
    includeHidden: Boolean = false,
): FolderScan {
    if (!root.isDirectory) return FolderScan(emptyList(), emptyList(), truncated = false)
    val state = ScanState(root, maxDepth, maxEntries, maxCandidates, includeHidden)
    root.canonicalPathOrNull()?.let { state.visitedDirs += it }
    state.queue.addLast(PendingDir(root, depth = 0))

    walk(state)

    // Directory listing order is filesystem-dependent (and the BFS visits siblings before their
    // own children), so the accepted candidates aren't already in a stable order — sort by
    // entryPath the same way the picker dialog expects an archive's candidates to already be
    // ordered.
    return FolderScan(
        logCandidates = state.logs.sortedBy { it.entryPath },
        videoCandidates = state.videos.sortedBy { it.entryPath },
        truncated = state.truncated,
    )
}

// Drains the BFS queue until it's empty or a bound (maxEntries/maxCandidates) is hit. Returns
// early the moment either bound trips, rather than breaking a labeled outer loop — which is what
// let visitDirectory/visitFile below stay simple functions instead of needing to signal "stop the
// whole walk" back up through nested break/continue levels.
private fun walk(state: ScanState) {
    while (state.queue.isNotEmpty()) {
        val (dir, depth) = state.queue.removeFirst()
        val children = dir.listFiles() ?: continue
        // Sort children so sibling ordering (and therefore which entries get cut off once a cap
        // is hit) is deterministic across platforms rather than following raw, filesystem-
        // dependent directory listing order.
        for (child in children.sortedBy { it.name }) {
            if (state.entriesExamined >= state.maxEntries) {
                state.truncated = true
                return
            }
            if (!state.includeHidden && child.isHidden) continue
            state.entriesExamined++
            if (child.isDirectory) {
                visitDirectory(state, child, depth)
            } else if (!visitFile(state, child)) {
                return
            }
        }
    }
}

// Enqueues [child] for a later visit unless it's past the depth cap, or is a symlink cycle (or
// duplicate alias) back to an already-visited real directory — see scanFolderForLogs' doc comment
// on the canonicalPath HashSet for why that one check alone is enough to break a cycle.
private fun visitDirectory(state: ScanState, child: File, depth: Int) {
    if (depth + 1 > state.maxDepth) return
    val canonical = child.canonicalPathOrNull() ?: return
    if (!state.visitedDirs.add(canonical)) return
    state.queue.addLast(PendingDir(child, depth + 1))
}

// Classifies [child] as a video/log candidate (or rejects it) and records it into [state]. Returns
// false once maxCandidates has been hit, telling walk() to stop the entire BFS rather than just
// this file — the same "no more capacity" signal a labeled break out of the whole walk used to
// give.
private fun visitFile(state: ScanState, child: File): Boolean {
    if (!child.isFile || !child.canRead()) return true
    if (state.candidatesFull()) {
        state.truncated = true
        return false
    }
    val relativePath = state.root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/')
    if (isVideoEntryName(child.name)) {
        state.videos += ZipLogCandidate(relativePath, child.name, child.length(), ZipLogCandidateKind.VIDEO)
        state.candidatesAccepted++
        return true
    }
    // Each file gets its own stream here (unlike a sequential archive's one shared stream) —
    // `.use {}` is correct and required to release the file handle promptly rather than leaving
    // thousands of them open until GC gets around to it.
    val kind = candidateKind(relativePath) { child.inputStream().use(::isLikelyTextStream) }
    if (kind != null) {
        state.logs += ZipLogCandidate(relativePath, child.name, child.length(), kind)
        state.candidatesAccepted++
    }
    return true
}

private fun File.canonicalPathOrNull(): String? = runCatching { canonicalPath }.getOrNull()
