package com.indagium.testing.store

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

// Where the AI test-suite data lives. Three user-configurable folders (Settings > General), by default subfolders of the
// Default save folder:
//   <save root>/test-suites   library.json, suites/<id>.json, assets/<suiteId>/        (TestLibraryStore, TestAssets)
//   <save root>/test-runs     <runId>/run.json, lanes/<laneId>/capture/ ...             (TestRunStore)
//   <save root>/test-issues   <issueId>/issue.json, attachments/                        (IssueStore)
// Earlier builds of this branch kept everything under <app data>/testing (library, suites, assets, issues/) and runs under
// <app data>/testing/runs when no save root was known. [migrateLegacyTestStorage] moves that data once.

/** Folder name under the Default save folder when "Test suites folder" is not set. */
const val DEFAULT_TEST_SUITES_FOLDER_NAME = "test-suites"

/** Folder name under the Default save folder when "Test runs folder" is not set. */
const val DEFAULT_TEST_RUNS_FOLDER_NAME = "test-runs"

/** Folder name under the Default save folder when "Issues folder" is not set. */
const val DEFAULT_TEST_ISSUES_FOLDER_NAME = "test-issues"

/** The runs folder inside the legacy `<app data>/testing` directory. */
const val LEGACY_TEST_RUNS_DIR_NAME = "runs"

/** Written into the legacy directory when a migration left data behind on purpose, so it is not retried into another folder. */
const val TEST_STORAGE_MIGRATED_MARKER = ".moved-to-save-folders"

/** What [migrateLegacyTestStorage] did: the moves that happened, what was left in place on purpose, and what failed. */
data class TestStorageMigrationReport(
    val moved: List<String> = emptyList(),
    val kept: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = moved.isEmpty() && kept.isEmpty() && failed.isEmpty()
}

/**
 * Moves the data of the old `<app data>/testing` layout ([legacyDir]) into the three resolved folders, once.
 * - library.json, suites/ and assets/ move to [suitesDir] only when it has no library.json yet.
 * - `issues/` moves to [issuesDir] only when that folder is empty or absent.
 * - `runs/` moves to [runsDir] only when that folder is empty or absent.
 * Nothing existing is ever overwritten (a directory that exists on both sides is merged entry by entry and a file that exists
 * on both sides stays where it is). A move that fails leaves its source untouched and is reported in [TestStorageMigrationReport.failed].
 * A destination that is the same folder as its source is skipped, so a layout that never changed does nothing.
 */
fun migrateLegacyTestStorage(legacyDir: File, suitesDir: File, issuesDir: File, runsDir: File): TestStorageMigrationReport {
    if (!legacyDir.isDirectory || File(legacyDir, TEST_STORAGE_MIGRATED_MARKER).exists()) return TestStorageMigrationReport()
    val moved = ArrayList<String>()
    val kept = ArrayList<String>()
    val failed = ArrayList<String>()
    val mover = TreeMover(moved, kept, failed)
    if (!sameFolder(legacyDir, suitesDir)) {
        if (File(suitesDir, TEST_LIBRARY_FILE_NAME).exists()) {
            val present = listOf(TEST_LIBRARY_FILE_NAME, TEST_SUITES_DIR_NAME, TEST_ASSETS_DIR_NAME).filter { File(legacyDir, it).exists() }
            if (present.isNotEmpty()) kept += "The test library in ${legacyDir.path} was left in place: ${suitesDir.path} already has a library."
        } else {
            listOf(TEST_LIBRARY_FILE_NAME, TEST_SUITES_DIR_NAME, TEST_ASSETS_DIR_NAME).forEach { name ->
                mover.move(File(legacyDir, name), File(suitesDir, name))
            }
        }
    }
    moveWhenEmpty(File(legacyDir, ISSUES_DIR_NAME), issuesDir, mover, kept)
    moveWhenEmpty(File(legacyDir, LEGACY_TEST_RUNS_DIR_NAME), runsDir, mover, kept)
    if (failed.isEmpty() && (moved.isNotEmpty() || kept.isNotEmpty())) settleLegacyDir(legacyDir)
    return TestStorageMigrationReport(moved, kept, failed)
}

private fun moveWhenEmpty(source: File, target: File, mover: TreeMover, kept: MutableList<String>) {
    if (!source.exists() || sameFolder(source, target)) return
    if (hasEntries(target)) {
        kept += "${source.path} was left in place: ${target.path} already has data."
    } else {
        mover.move(source, target)
    }
}

/** Removes the legacy folder when nothing is left in it, otherwise remembers that the leftovers were left on purpose. */
private fun settleLegacyDir(legacyDir: File) {
    val remaining = legacyDir.list().orEmpty()
    if (remaining.isEmpty()) {
        runCatching { Files.deleteIfExists(legacyDir.toPath()) }
    } else if (remaining.any { it != TEST_STORAGE_MIGRATED_MARKER }) {
        runCatching { File(legacyDir, TEST_STORAGE_MIGRATED_MARKER).writeText(System.currentTimeMillis().toString()) }
    }
}

private fun hasEntries(folder: File): Boolean = folder.exists() && (!folder.isDirectory || folder.list().orEmpty().isNotEmpty())

private fun sameFolder(a: File, b: File): Boolean = a.absoluteFile.toPath().normalize() == b.absoluteFile.toPath().normalize()

/** Moves files and folders without ever overwriting, falling back to copy-then-delete across file systems. */
private class TreeMover(private val moved: MutableList<String>, private val kept: MutableList<String>, private val failed: MutableList<String>) {
    fun move(source: File, target: File) {
        val sourcePath = source.toPath()
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) return
        try {
            if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                target.parentFile?.let { Files.createDirectories(it.toPath()) }
                relocate(sourcePath, target.toPath())
                moved += "${source.path} -> ${target.path}"
            } else if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                source.list().orEmpty().sorted().forEach { move(File(source, it), File(target, it)) }
                runCatching { Files.deleteIfExists(sourcePath) }
            } else {
                kept += "${source.path} was left in place: ${target.path} already exists."
            }
        } catch (failure: IOException) {
            failed += "${source.path}: ${failure.message ?: failure::class.simpleName}"
        }
    }

    private fun relocate(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            copyThenDelete(source, target)
        }
    }

    /** A copy that fails part way removes only what it created; the source is deleted only after the whole copy succeeded. */
    private fun copyThenDelete(source: Path, target: Path) {
        try {
            copyTree(source, target)
        } catch (failure: IOException) {
            runCatching { deleteTree(target) }
            throw failure
        }
        deleteTree(source)
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectory(target.resolve(source.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, target.resolve(source.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
