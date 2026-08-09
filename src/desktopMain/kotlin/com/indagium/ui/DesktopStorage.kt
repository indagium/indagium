package com.indagium.ui

import com.indagium.generated.BuildInfo
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

// ── openLog2 -> Indagium one-time app-data migration ────────────────────────
//
// Stage 1 of the rename: the app-data directory literal moves from "openLog2" to "Indagium", so
// existing users' sessions/notes/indexes would otherwise be silently orphaned (still on disk,
// unreachable) the moment a renamed build launches. migrateAppDataDir() copies the entries worth
// keeping from the old directory into the new one, once, before AppState (and therefore autosave
// restore) is constructed. See Main.kt's call to DesktopStorage.migrateAppDataDirIfNeeded().

/** Outcome of a [DesktopStorage.migrateAppDataDir] run; see [DesktopStorage.lastMigrationOutcome]. */
internal sealed class MigrationOutcome {
    /** The marker file already exists in the new dir; nothing to do this launch. */
    internal object AlreadyDone : MigrationOutcome()

    /** No old-directory data existed to migrate; the marker was written so future launches skip. */
    internal object NoLegacyData : MigrationOutcome()

    /**
     * Migration completed and the marker was written. [bytes] counts only bytes that were actually
     * byte-copied (hardlinked files cost no disk and are not counted). [skipped] lists entry names
     * (e.g. "voice-models") whose copy was aborted mid-way because it alone would have pushed the
     * running byte-copy total past [MIGRATION_MAX_BYTES] — the rest of the migration still completed
     * and the marker was still written; nothing already copied is ever discarded because of this.
     */
    internal data class Migrated(val fileCount: Int, val bytes: Long, val skipped: List<String> = emptyList()) :
        MigrationOutcome()

    /** Migration did not complete; nothing was written to the new dir and no marker was left. */
    internal data class Failed(val reason: String) : MigrationOutcome()
}

private const val MIGRATION_LOG_PREFIX = "[Indagium migration]"
private const val MIGRATION_MARKER_NAME = ".migrated-from-openLog2"
private const val MIGRATION_STAGING_SUFFIX = ".migrating"
private const val MIGRATION_MAX_DEPTH = 16
internal const val MIGRATION_MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GiB cap on the whole copy

private enum class MigrationCopyMode {
    /** Plain byte copy. */
    COPY,

    /** Hardlink per file (same volume, near-free); falls back to a byte copy on any failure. */
    HARDLINK_OR_COPY,
}

private data class MigrationEntry(val name: String, val mode: MigrationCopyMode)

// archive-cache/, control-token, single-instance.{lock,port} and openlog-debug.log are
// deliberately absent — see the copy-policy table in the Stage 1 spec: derived/rebuildable,
// per-install-secret, process-local, or renamed-away respectively.
private val MIGRATION_ENTRIES = listOf(
    MigrationEntry("autosave.cache", MigrationCopyMode.COPY),
    MigrationEntry("notes", MigrationCopyMode.COPY),
    MigrationEntry("custom-ai-commands", MigrationCopyMode.COPY),
    MigrationEntry("filter-backups", MigrationCopyMode.COPY),
    MigrationEntry("source-index", MigrationCopyMode.COPY),
    MigrationEntry("case-index", MigrationCopyMode.COPY),
    MigrationEntry("voice-models", MigrationCopyMode.HARDLINK_OR_COPY),
)

private class MigrationByteCapExceeded : IOException("migration exceeded $MIGRATION_MAX_BYTES byte cap")

// [bytes] deliberately counts only bytes that were actually byte-copied — a hardlinked file costs
// no disk and no measurable time, so it must not count against a cap whose whole purpose is to stop
// a pathological *copy* from blocking startup. See checkCapFor/recordCopied below.
private class MigrationCounters {
    var fileCount: Int = 0
        private set
    var bytes: Long = 0L
        private set

    fun recordHardlinked() {
        fileCount++
    }

    /** Call before the actual byte copy; throws without recording anything if [size] would exceed the cap. */
    fun checkCapFor(size: Long) {
        if (bytes + size > MIGRATION_MAX_BYTES) throw MigrationByteCapExceeded()
    }

    fun recordCopied(size: Long) {
        bytes += size
        fileCount++
    }
}

/**
 * Hardlinks one file when [mode] allows it (near-free, same volume, does not count toward the byte
 * cap), falling back to a byte copy — checked against the cap first — on any hardlink failure or
 * when [mode] doesn't permit linking. Preserves attributes on the copy path.
 */
private fun copyOneFile(src: Path, dst: Path, mode: MigrationCopyMode, counters: MigrationCounters) {
    val hardlinked = mode == MigrationCopyMode.HARDLINK_OR_COPY &&
        runCatching { Files.createLink(dst, src) }.isSuccess
    if (hardlinked) {
        counters.recordHardlinked()
        return
    }
    val size = Files.size(src)
    counters.checkCapFor(size)
    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
    counters.recordCopied(size)
}

// Walks without FOLLOW_LINKS (the default for the 4-arg overload below) so a symlink is visited
// as a leaf via visitFile — never descended into — which is what keeps a symlink loop under
// notes/ from hanging startup. Symlinks themselves are skipped rather than copied: the target may
// point outside the old app-data dir entirely, and there is nothing in the copy policy that calls
// for preserving link structure.
private class MigrationCopyVisitor(
    private val srcRoot: Path,
    private val dstRoot: Path,
    private val mode: MigrationCopyMode,
    private val counters: MigrationCounters,
) : SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        Files.createDirectories(dstRoot.resolve(srcRoot.relativize(dir)))
        return FileVisitResult.CONTINUE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (attrs.isSymbolicLink) return FileVisitResult.CONTINUE
        copyOneFile(file, dstRoot.resolve(srcRoot.relativize(file)), mode, counters)
        return FileVisitResult.CONTINUE
    }
}

/**
 * Copies one [MigrationEntry] into staging. Returns false if the entry's copy was aborted midway
 * by the byte cap — in which case its partial staging subtree is deleted so nothing half-copied is
 * ever published, and the caller is expected to skip it and continue with the remaining entries.
 * Any other failure (permissions, disk full, etc.) propagates and fails the whole migration.
 */
private fun copyEntryIntoStaging(oldDir: File, staging: File, entry: MigrationEntry, counters: MigrationCounters): Boolean {
    val src = File(oldDir, entry.name)
    if (!src.exists()) return true
    val dst = File(staging, entry.name)
    return try {
        if (src.isDirectory) {
            Files.walkFileTree(
                src.toPath(),
                emptySet(),
                MIGRATION_MAX_DEPTH,
                MigrationCopyVisitor(src.toPath(), dst.toPath(), entry.mode, counters),
            )
        } else {
            copyOneFile(src.toPath(), dst.toPath(), entry.mode, counters)
        }
        true
    } catch (_: MigrationByteCapExceeded) {
        dst.deleteRecursively()
        false
    }
}

private fun copyRecursively(src: File, dst: File) {
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.forEach { child -> copyRecursively(child, File(dst, child.name)) }
    } else {
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
    }
}

/** Moves staging's entries into an already-existing newDir, skipping anything already there. */
private fun mergeStagingInto(staging: File, newDir: File) {
    staging.listFiles()?.forEach { child ->
        val target = File(newDir, child.name)
        if (!target.exists() && !child.renameTo(target)) copyRecursively(child, target)
    }
}

private fun placeStaging(staging: File, newDir: File) {
    if (newDir.exists()) {
        mergeStagingInto(staging, newDir)
    } else if (!staging.renameTo(newDir)) {
        copyRecursively(staging, newDir)
    }
}

private fun writeMigrationMarker(newDir: File, oldDir: File) {
    newDir.mkdirs()
    File(newDir, MIGRATION_MARKER_NAME).writeText(
        "source=${oldDir.absolutePath}\ntimestamp=${Instant.now()}\nappVersion=${BuildInfo.APP_VERSION}\n",
    )
}

private fun describeOutcome(outcome: MigrationOutcome, oldDir: File, newDir: File): String = when (outcome) {
    is MigrationOutcome.AlreadyDone -> "already migrated into ${newDir.absolutePath}; skipping"
    is MigrationOutcome.NoLegacyData -> "no legacy data at ${oldDir.absolutePath}; marker written"
    is MigrationOutcome.Migrated -> {
        val skippedNote = if (outcome.skipped.isEmpty()) {
            ""
        } else {
            " (skipped over the byte cap: ${outcome.skipped.joinToString()})"
        }
        "migrated ${outcome.fileCount} file(s), ${outcome.bytes} byte(s) from " +
            "${oldDir.absolutePath} to ${newDir.absolutePath}$skippedNote"
    }
    is MigrationOutcome.Failed -> "failed (${outcome.reason}); old data untouched, will retry next launch"
}

/**
 * Compact, path-free summary of a migration outcome for the follow-diagnostics export (see
 * [formatFollowDiagnostics] in AppState.kt), so a user's report is enough to tell whether their
 * data made it across. Distinct from [describeOutcome], which is the fuller stderr log line.
 */
internal fun migrationOutcomeSummary(outcome: MigrationOutcome?): String = when (outcome) {
    null -> "no migration recorded this session"
    is MigrationOutcome.AlreadyDone -> "already migrated (no-op this launch)"
    is MigrationOutcome.NoLegacyData -> "no legacy openLog2 data found"
    is MigrationOutcome.Migrated -> {
        val skippedNote = if (outcome.skipped.isEmpty()) "" else ", skipped over byte cap: ${outcome.skipped.joinToString()}"
        "migrated ${outcome.fileCount} file(s), ${outcome.bytes} byte(s)$skippedNote"
    }
    is MigrationOutcome.Failed -> "failed: ${outcome.reason} (will retry next launch)"
}

object DesktopStorage {
    private const val APP_DIR_NAME = "Indagium"
    private const val LEGACY_APP_DIR_NAME = "openLog2"

    // This is deliberately process-local and is only installed by Main.kt after it has observed
    // the ephemeral debug-control switch.  It is not a user setting and must never be restored
    // from autosave: its sole purpose is letting an automation run keep its token, autosave,
    // Recent list, caches, and single-instance files away from a developer's real app-data dir.
    @Volatile
    private var debugAppDataDirOverride: File? = null

    /** Outcome of the last [migrateAppDataDirIfNeeded] run this process, for later diagnostics. */
    @Volatile
    internal var lastMigrationOutcome: MigrationOutcome? = null
        private set

    private fun platformDataDir(dirName: String, osName: String, userHome: String, getenv: (String) -> String?): File {
        val os = osName.lowercase()
        return when {
            os.contains("mac") -> File(userHome, "Library/Application Support/$dirName")
            os.contains("win") -> File(
                getenv("APPDATA") ?: File(userHome, "AppData/Roaming").absolutePath,
                dirName,
            )
            else -> File(getenv("XDG_STATE_HOME") ?: File(userHome, ".local/state").absolutePath, dirName)
        }
    }

    /**
     * The process app-data directory. Production returns the platform default; a test-only,
     * explicitly configured debug override may replace it for the lifetime of this process.
     */
    fun appDataDir(): File = debugAppDataDirOverride ?: platformDataDir(
        APP_DIR_NAME,
        System.getProperty("os.name").orEmpty(),
        System.getProperty("user.home").orEmpty(),
        System::getenv,
    )

    /** Explicit platform calculation kept separate so storage tests never observe process state. */
    fun appDataDir(
        osName: String,
        userHome: String,
        getenv: (String) -> String?,
    ): File = platformDataDir(APP_DIR_NAME, osName, userHome, getenv)

    /**
     * Installs an isolated app-data directory for a debug-control process only.
     *
     * A caller must supply an absolute path and enable the ephemeral debug-control launch switch.
     * Existing directories must be empty so the launch cannot restore a previous autosave or Recent
     * list. Invalid requests fail closed rather than silently falling back to a user's normal
     * app-data directory.
     */
    internal fun installDebugAppDataDirOverride(
        requestedPath: String?,
        debugControlEnabled: Boolean,
    ): File? {
        val isolated = resolveDebugAppDataDirOverride(requestedPath, debugControlEnabled)
        debugAppDataDirOverride = isolated
        return isolated
    }

    /** Pure launch-policy resolver; kept separate from installation for focused, state-free tests. */
    internal fun resolveDebugAppDataDirOverride(
        requestedPath: String?,
        debugControlEnabled: Boolean,
    ): File? {
        if (requestedPath.isNullOrBlank()) {
            return null
        }
        if (!debugControlEnabled) {
            return null
        }

        val requested = File(requestedPath)
        require(requested.isAbsolute) {
            "INDAGIUM_DEBUG_APP_DATA_DIR must be an absolute path"
        }
        requireNoSymlinkInDebugAppDataPath(requested.toPath())
        val isolated = requested.canonicalFile
        require(!isolated.exists() || isolated.isDirectory) {
            "INDAGIUM_DEBUG_APP_DATA_DIR must name a directory, not a file"
        }
        require(isUnderApprovedDebugTempRoot(isolated)) {
            "INDAGIUM_DEBUG_APP_DATA_DIR must be inside the JVM temporary directory or a macOS /private temporary root"
        }
        require(!isolated.isDirectory || isolated.list()?.isEmpty() == true) {
            "INDAGIUM_DEBUG_APP_DATA_DIR must be empty to prevent restoring autosave or Recent state"
        }
        return isolated
    }

    internal fun hasDebugAppDataDirOverride(): Boolean = debugAppDataDirOverride != null

    /**
     * Reject a symlink anywhere in the spelling supplied by the launcher, including an ancestor
     * of a not-yet-created target. `canonicalFile` alone is insufficient: it would hide the link
     * after following it, letting an apparently temporary test root write somewhere unexpected.
     */
    private fun requireNoSymlinkInDebugAppDataPath(requested: Path) {
        var current = requireNotNull(requested.root) { "debug app-data path must be absolute" }
        requested.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                "INDAGIUM_DEBUG_APP_DATA_DIR must not contain symlinked path components"
            }
        }
    }

    /** Only a fresh directory beneath an OS/JVM temporary root is safe for automation isolation. */
    private fun isUnderApprovedDebugTempRoot(candidate: File): Boolean {
        val candidatePath = candidate.toPath()
        return approvedDebugTempRoots().any { root -> candidatePath != root && candidatePath.startsWith(root) }
    }

    private fun approvedDebugTempRoots(): List<Path> {
        val roots = mutableListOf(File(System.getProperty("java.io.tmpdir")).canonicalFile)
        if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
            roots += File("/private/tmp").canonicalFile
            roots += File("/private/var/folders").canonicalFile
        }
        return roots.map(File::toPath).distinct()
    }

    /** The pre-rename locations `appDataDir()` used to produce, for the one-time migration. */
    fun legacyAppDataDir(
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home").orEmpty(),
        getenv: (String) -> String? = System::getenv,
    ): File = platformDataDir(LEGACY_APP_DIR_NAME, osName, userHome, getenv)

    fun autosaveFile(): File = File(appDataDir(), "autosave.cache")

    fun archiveCacheDir(): File = File(appDataDir(), "archive-cache")

    fun notesDir(): File = File(appDataDir(), "notes")

    fun customCommandsDir(): File = File(appDataDir(), "custom-ai-commands")

    /** Locally installed, explicitly user-downloaded speech models. Never contains recordings. */
    fun voiceModelsDir(): File = File(appDataDir(), "voice-models")

    fun filterBackupsDir(): File = File(appDataDir(), "filter-backups")

    fun controlTokenFile(): File = File(appDataDir(), "control-token")

    fun sourceIndexFile(): File = File(appDataDir(), "source-index")

    fun caseIndexFile(): File = File(appDataDir(), "case-index")

    fun debugLogFile(): File = File(appDataDir(), "indagium-debug.log")

    fun legacyNotesDir(userHome: String = System.getProperty("user.home").orEmpty()): File =
        File(userHome, ".openlog2/notes")

    /**
     * Wires the real [appDataDir] / [legacyAppDataDir] and runs the one-time copy. Call first in
     * main(). A debug-control process with an isolated app-data override intentionally skips this:
     * migration would import the user's normal session into the test instance.
     */
    fun migrateAppDataDirIfNeeded() {
        if (hasDebugAppDataDirOverride()) {
            lastMigrationOutcome = null
            return
        }
        migrateAppDataDir(appDataDir(), legacyAppDataDir())
    }

    /**
     * Copies the entries worth keeping from [oldDir] into [newDir], once. Never writes, moves, or
     * deletes anything under [oldDir]. Takes both directories explicitly so it is unit-testable
     * with two temp dirs and no environment mocking; [migrateAppDataDirIfNeeded] wires the real ones.
     */
    internal fun migrateAppDataDir(newDir: File, oldDir: File): MigrationOutcome {
        val marker = File(newDir, MIGRATION_MARKER_NAME)
        if (marker.exists()) return finish(MigrationOutcome.AlreadyDone, oldDir, newDir)
        if (!oldDir.isDirectory) {
            writeMigrationMarker(newDir, oldDir)
            return finish(MigrationOutcome.NoLegacyData, oldDir, newDir)
        }

        val stagingParent = requireNotNull(newDir.parentFile) { "appDataDir must have a parent directory" }
        val staging = File(stagingParent, "${newDir.name}$MIGRATION_STAGING_SUFFIX")
        staging.deleteRecursively()
        val counters = MigrationCounters()
        val skipped = mutableListOf<String>()

        val copyFailure = runCatching {
            staging.mkdirs()
            MIGRATION_ENTRIES.forEach { entry ->
                if (!copyEntryIntoStaging(oldDir, staging, entry, counters)) skipped += entry.name
            }
        }.exceptionOrNull()
        if (copyFailure != null) {
            staging.deleteRecursively()
            return finish(MigrationOutcome.Failed(describeFailure(copyFailure)), oldDir, newDir)
        }

        val placeFailure = runCatching { placeStaging(staging, newDir) }.exceptionOrNull()
        staging.deleteRecursively()
        if (placeFailure != null) {
            return finish(MigrationOutcome.Failed(describeFailure(placeFailure)), oldDir, newDir)
        }

        writeMigrationMarker(newDir, oldDir)
        return finish(MigrationOutcome.Migrated(counters.fileCount, counters.bytes, skipped), oldDir, newDir)
    }

    private fun describeFailure(t: Throwable): String = t.message ?: t::class.simpleName ?: "unknown error"

    // AlreadyDone is the steady state for the rest of the app's life once migrated — logging it
    // would print on every single launch forever. The other three outcomes are each one-time
    // (or a rare, actionable failure) and stay logged.
    private fun finish(outcome: MigrationOutcome, oldDir: File, newDir: File): MigrationOutcome {
        lastMigrationOutcome = outcome
        if (outcome !is MigrationOutcome.AlreadyDone) {
            System.err.println("$MIGRATION_LOG_PREFIX ${describeOutcome(outcome, oldDir, newDir)}")
        }
        return outcome
    }
}
