package com.openlog.source

import com.openlog.model.SourceLogConfiguration
import java.security.MessageDigest

/** Bumped whenever the shape of [SourceIndex] (or the matcher-building rules that feed it)
 *  changes in a way that makes a previously-persisted index stale. Task 1 doesn't persist
 *  anything yet, but later tasks compare this against a saved value to decide whether a cached
 *  index must be rebuilt rather than merely refreshed. */
const val SOURCE_INDEX_VERSION = 8

/** One Android logging call site discovered by [SourceIndexer.build].
 *
 * [tag] is the resolved TAG string, or null when it couldn't be resolved (e.g. a Timber call
 * with no preceding `.tag(...)`, or a `Log.d(someVariable, ...)` call whose first arg isn't a
 * literal and isn't a locally-defined constant).
 *
 * [matcher] is a regex pattern string (not a compiled [Regex] — callers compile it once and
 * cache the result, see [LogSourceResolver]) built from the call's message template: literal
 * segments are `\Q...\E`-quoted and joined with `.*?` for the dynamic "holes" (interpolations,
 * concatenated variables, etc).
 */
data class LogCallSite(
    val filePath: String,
    val tag: String?,
    val methodName: String,
    val methodStartLine: Int,
    val methodEndLine: Int,
    val callLine: Int,
    val matcher: String,
    val literalLen: Int,
    // Direct Log/Timber sites remain valid when custom-wrapper settings change. Wrapper sites
    // must be hidden until their folder is reindexed with the current configuration.
    val configurationDependent: Boolean = false,
)

/** Snapshot of a source file's on-disk state at index-build time, used by later tasks to detect
 *  when a file has changed since it was scanned without re-reading its contents. */
data class FileMeta(val mtime: Long, val size: Long)

data class SourceIndex(
    val version: Int,
    val roots: List<String>,
    val sites: List<LogCallSite>,
    val fileMeta: Map<String, FileMeta>,
    val builtAt: Long,
    // Per-root last-reindex timestamp — reindexing is per source folder (AppState.reindexSources),
    // so unlike [builtAt] (stamped whenever any one root is merged in) this is what the Settings UI
    // shows as "indexed N ago" for a given folder.
    val rootBuiltAt: Map<String, Long> = emptyMap(),
    // Fingerprint of the source logging configuration used for each root. A mismatch means
    // configuration-dependent wrapper sites are not current until that root is reindexed.
    val rootConfigFingerprints: Map<String, String> = emptyMap(),
)

/** A candidate source site for a resolved log line. [stale] is always false in Task 1 — later
 *  tasks compute it by comparing the site's file against its recorded [FileMeta]. */
data class SourceMatch(
    val site: LogCallSite,
    val confidence: Double,
    val stale: Boolean = false,
)

data class SourceIndexStatus(
    val fileCount: Int = 0,
    val siteCount: Int = 0,
    val builtAt: Long = 0L,
    val changedFileCount: Int = 0,
    val configurationChanged: Boolean = false,
)

/** Host state for the source-code popup (Task 4): the resolved candidates for whichever log row
 *  triggered it, and which one is currently shown. [selected] is an index into [matches]. */
data class SourceCodeView(
    val matches: List<SourceMatch>,
    val selected: Int = 0,
)

/** Stable per-root fingerprint for the settings that affect source-call extraction. */
fun sourceConfigurationFingerprint(
    configurations: List<SourceLogConfiguration>,
    autoDiscoveryEnabled: Boolean = true,
): String {
    val canonical = buildString {
        append("autoDiscovery=").append(autoDiscoveryEnabled).append('\n')
        append(configurations.sortedBy { it.id }.joinToString("\n") { config ->
            buildString {
                append(config.id).append('|').append(config.name)
                config.wrapperRules.sortedWith(compareBy({ it.ownerType }, { it.methodName }, { it.tagArgumentIndex }, { it.messageArgumentIndex }))
                    .forEach { rule ->
                        append('|').append(rule.ownerType).append('|').append(rule.methodName)
                            .append('|').append(rule.tagArgumentIndex).append('|').append(rule.messageArgumentIndex)
                            .append('|').append(rule.throwableArgumentIndex ?: -1)
                    }
            }
        })
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "source-index-$SOURCE_INDEX_VERSION\n$canonical".toByteArray(Charsets.UTF_8),
    )
    return digest.joinToString("") { "%02x".format(it) }
}
