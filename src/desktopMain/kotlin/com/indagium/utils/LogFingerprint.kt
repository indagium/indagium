package com.indagium.utils

import com.indagium.model.LogEntry
import java.security.MessageDigest

// How many entries from each end of the log are hashed. Large enough that two different captures
// of the same bug (same device, same steps, re-run) almost always diverge somewhere in their
// first/last FINGERPRINT_SAMPLE_SIZE lines (boot noise, timestamps, pid/tid churn), small enough
// that this stays O(sample size) rather than O(file size) — see computeLogFingerprint's own doc.
private const val FINGERPRINT_SAMPLE_SIZE = 20

/**
 * Cheap, save-time content fingerprint for a parsed log (Change 2a of the relink-log feature) —
 * lets a later "Locate log…" tell a renamed/moved copy of the SAME capture apart from a genuinely
 * different capture of the same bug. The distinction matters because [com.indagium.model.AnnBlock.
 * LogRef.logIds] are positional per file (LogParser restarts ids at 1 for every file it parses):
 * attaching a note to an unrelated capture would silently make every log reference point at the
 * wrong row instead of failing loudly.
 *
 * Composition: the total entry count, plus a SHA-256 hash (truncated to 16 hex chars — this only
 * needs to be "confident enough to warn on," not cryptographically unique) of the timestamp, tag,
 * and message of the first and last [FINGERPRINT_SAMPLE_SIZE] entries, concatenated in order. Only
 * content is hashed, never the file's name or path, so a plain rename/move is still a match.
 *
 * Deliberately does not touch the filesystem: [entries] is the already-parsed log a tab is holding
 * in memory (LogTab.logData) — computing this from that, rather than re-opening/re-reading the
 * file, is what keeps it cheap on a multi-GB capture. For files at or under 2×
 * [FINGERPRINT_SAMPLE_SIZE] entries, the head and tail samples overlap; that only shrinks how much
 * distinct material gets hashed for tiny files, which are already unambiguous by entry count alone.
 */
fun computeLogFingerprint(entries: List<LogEntry>): String {
    if (entries.isEmpty()) return ""
    val sample = entries.take(FINGERPRINT_SAMPLE_SIZE) + entries.takeLast(FINGERPRINT_SAMPLE_SIZE)
    val digest = MessageDigest.getInstance("SHA-256")
    sample.forEach { entry ->
        digest.update(entry.ts.toByteArray(Charsets.UTF_8))
        digest.update(entry.tag.toByteArray(Charsets.UTF_8))
        digest.update(entry.msg.toByteArray(Charsets.UTF_8))
    }
    val hashHex = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    return "${entries.size}:$hashHex"
}
