package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.source.SourceIndexStore
import com.openlog.ui.AppState
import com.openlog.ui.editorCommandArguments
import com.openlog.ui.mkTab
import com.openlog.ui.resolveExecutable
import com.openlog.ui.splitEditorCommand
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Task 2: AppState's persistence/wiring around the headless resolver engine (source/*). Every
// AppState here is given its own temp autosaveFile/sourceIndexFile so these tests never touch the
// real ~/.openlog2-equivalent location (see the constructor's own doc comments on those seams).
class SourceIndexAppStateTest {
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun newState(dir: File, sourceIndexFile: File = File(dir, "source-index")) =
        AppState(autosaveFile = File(dir, "state.cache"), sourceIndexFile = sourceIndexFile)

    @Test
    fun settingsRoundTripPreservesSourceFolders() {
        val dir = createTempDirectory("openlog-src-settings").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(autosaveFile = cacheFile, sourceIndexFile = File(dir, "source-index"))
        state.updateSettings { it.copy(sourceFolders = listOf("/a", "/b")) }
        state.autosaveNow()

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true, sourceIndexFile = File(dir, "source-index"))

        assertEquals(listOf("/a", "/b"), restored.settings.sourceFolders)
    }

    @Test
    fun settingsRoundTripPreservesEditorCommand() {
        val dir = createTempDirectory("openlog-src-editor-cmd").toFile()
        val cacheFile = File(dir, "state.cache")
        val state = AppState(autosaveFile = cacheFile, sourceIndexFile = File(dir, "source-index"))
        state.updateSettings { it.copy(editorCommand = "idea --line {line} {file}") }
        state.autosaveNow()

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true, sourceIndexFile = File(dir, "source-index"))

        assertEquals("idea --line {line} {file}", restored.settings.editorCommand)
    }

    @Test
    fun oldAutosaveTokenWithoutEditorCommandFieldRestoresToBlank() {
        val dir = createTempDirectory("openlog-src-editor-cmd-legacy").toFile()
        val cacheFile = File(dir, "state.cache")
        // Hand-encoded settings token in the pre-editorCommand shape (24 pipe fields, sourceFolders
        // present but no trailing editorCommand field) — exercises settingsFromToken's tolerant
        // index math the same way a real cache file written by an older build would.
        val legacyFields = listOf(
            "LIGHT", "12", "true", "", "5", "5", "8", "true", "true", "JIRA_JAVA", "false", "From",
            "5", "480", "true", "false", "8991", "false", "java", "j*ava", "false", "KEYWORD_REGEX", "false",
            listOf("/a", "/b").joinToString(",") { it.legacyB64() }.legacyB64(),
        )
        val legacyToken = legacyFields.joinToString("|") { it.legacyFieldToken() }
        cacheFile.writeText("openLog2-cache-v1\nsettings\t${legacyToken.legacyB64()}\n")

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true, sourceIndexFile = File(dir, "source-index"))

        assertEquals(listOf("/a", "/b"), restored.settings.sourceFolders)
        assertEquals("", restored.settings.editorCommand)
    }

    @Test
    fun oldAutosaveTokenWithoutSourceFoldersFieldRestoresToEmptyList() {
        val dir = createTempDirectory("openlog-src-settings-legacy").toFile()
        val cacheFile = File(dir, "state.cache")
        // Hand-encoded settings token in the pre-Task-2 shape (23 pipe fields, no trailing
        // sourceFolders field) — exercises settingsFromToken's tolerant index math the same way a
        // real cache file written by an older build of the app would.
        val legacyFields = listOf(
            "LIGHT", "12", "true", "", "5", "5", "8", "true", "true", "JIRA_JAVA", "false", "From",
            "5", "480", "true", "false", "8991", "false", "java", "j*ava", "false", "KEYWORD_REGEX", "false",
        )
        val legacyToken = legacyFields.joinToString("|") { it.legacyFieldToken() }
        cacheFile.writeText("openLog2-cache-v1\nsettings\t${legacyToken.legacyB64()}\n")

        val restored = AppState(autosaveFile = cacheFile, restoreOnCreate = true, sourceIndexFile = File(dir, "source-index"))

        assertEquals(emptyList(), restored.settings.sourceFolders)
        // Sanity check the hand-rolled legacy token was decoded faithfully, not just defaulted
        // wholesale because parsing bailed out early.
        assertEquals(8991, restored.settings.mcpControlPort)
    }

    @Test
    fun resolveForLineMapsTabEntryTagAndMessageToSourceMatch() {
        val dir = createTempDirectory("openlog-src-resolve").toFile()
        val srcDir = File(dir, "src").apply { mkdirs() }
        File(srcDir, "Foo.kt").writeText(
            """
            package demo

            class Foo {
                fun bar() {
                    Log.d("TagX", "Hello world")
                }
            }
            """.trimIndent(),
        )

        val state = newState(dir)
        state.updateSettings { it.copy(sourceFolders = listOf(srcDir.absolutePath)) }
        state.reindexSources()
        waitUntil { state.sourceIndex != null }

        val tab = mkTab("log", "test.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.D, "TagX", "Hello world")))
        state.tabs = listOf(tab)

        val matches = state.resolveForLine("log", 1)

        assertEquals(1, matches.size)
        assertEquals("bar", matches.single().site.methodName)
        assertEquals(false, matches.single().stale)
    }

    @Test
    fun resolveForLineIsEmptyForMissingTabOrLine() {
        val dir = createTempDirectory("openlog-src-resolve-missing").toFile()
        val state = newState(dir)

        assertEquals(emptyList(), state.resolveForLine("no-such-tab", 1))
    }

    @Test
    fun readMethodSourceReturnsExactMethodLines() {
        val dir = createTempDirectory("openlog-src-read-method").toFile()
        val srcDir = File(dir, "src").apply { mkdirs() }
        File(srcDir, "Foo.kt").writeText(
            """
            package demo

            class Foo {
                fun bar() {
                    Log.d("TagX", "Hello world")
                }
            }
            """.trimIndent(),
        )

        val state = newState(dir)
        state.updateSettings { it.copy(sourceFolders = listOf(srcDir.absolutePath)) }
        state.reindexSources()
        waitUntil { state.sourceIndex != null }

        val site = state.sourceIndex!!.sites.single()
        val source = state.readMethodSource(site)

        assertEquals("    fun bar() {\n        Log.d(\"TagX\", \"Hello world\")\n    }", source)
    }

    @Test
    fun readMethodSourceReturnsNullForUnreadableFile() {
        val dir = createTempDirectory("openlog-src-read-method-missing").toFile()
        val state = newState(dir)
        val site = com.openlog.source.LogCallSite(
            filePath = File(dir, "does-not-exist.kt").absolutePath,
            tag = "TagX",
            methodName = "bar",
            methodStartLine = 1,
            methodEndLine = 2,
            callLine = 1,
            matcher = "^\\QHello\\E$",
            literalLen = 5,
        )

        assertNull(state.readMethodSource(site))
    }

    @Test
    fun reindexPersistsIndexSoANewAppStateRestoresItOnStartup() {
        val dir = createTempDirectory("openlog-src-persist").toFile()
        val srcDir = File(dir, "src").apply { mkdirs() }
        File(srcDir, "Foo.kt").writeText(
            """
            package demo

            class Foo {
                fun bar() {
                    Log.d("TagX", "Hello world")
                }
            }
            """.trimIndent(),
        )
        val indexFile = File(dir, "source-index")

        val first = newState(dir, indexFile)
        first.updateSettings { it.copy(sourceFolders = listOf(srcDir.absolutePath)) }
        first.reindexSources()
        waitUntil { first.sourceIndex != null }

        assertTrue(indexFile.exists())
        assertEquals(first.sourceIndex, SourceIndexStore.load(indexFile))

        val second = newState(dir, indexFile)
        waitUntil { second.sourceIndexStatus.siteCount == 1 }

        assertEquals(1, second.sourceIndexStatus.siteCount)
        assertEquals(0, second.sourceIndexStatus.changedFileCount)
    }

    @Test
    fun modifyingAnIndexedFileMarksItStaleAndCountsAsChanged() {
        val dir = createTempDirectory("openlog-src-stale").toFile()
        val srcDir = File(dir, "src").apply { mkdirs() }
        val srcFile = File(srcDir, "Foo.kt").apply {
            writeText(
                """
                package demo

                class Foo {
                    fun bar() {
                        Log.d("TagX", "Hello world")
                    }
                }
                """.trimIndent(),
            )
        }
        val indexFile = File(dir, "source-index")

        val state = newState(dir, indexFile)
        state.updateSettings { it.copy(sourceFolders = listOf(srcDir.absolutePath)) }
        state.reindexSources()
        waitUntil { state.sourceIndexStatus.siteCount == 1 }
        assertEquals(0, state.sourceIndexStatus.changedFileCount)

        // resolveLogSource checks the file's current on-disk state against the recorded FileMeta on
        // every call (not just at index-build time), so this already reflects the edit below without
        // needing a reindex.
        Thread.sleep(20)
        srcFile.setLastModified(System.currentTimeMillis() + 60_000)
        srcFile.writeText(
            """
            package demo

            class Foo {
                fun bar() {
                    Log.d("TagX", "Hello world, edited")
                }
            }
            """.trimIndent(),
        )

        val matches = state.resolveLogSource("TagX", "Hello world")
        assertEquals(1, matches.size)
        assertTrue(matches.single().stale)

        // changedFileCount is recomputed against live disk state whenever an index is (re)loaded —
        // a fresh AppState pointed at the same persisted index file should now see the edit too.
        val reopened = newState(dir, indexFile)
        waitUntil { reopened.sourceIndexStatus.changedFileCount > 0 }
        assertTrue(reopened.sourceIndexStatus.changedFileCount > 0)
    }

    @Test
    fun resolveExecutableFindsExecutableFileByNameInSearchDirs() {
        val dir = createTempDirectory("openlog-resolve-exec-found").toFile()
        val exe = File(dir, "myeditor").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

        assertEquals(exe, resolveExecutable("myeditor", listOf(dir)))
    }

    @Test
    fun resolveExecutableReturnsNullForNonExecutableFile() {
        val dir = createTempDirectory("openlog-resolve-exec-non-exec").toFile()
        File(dir, "myeditor").apply {
            writeText("#!/bin/sh\n")
            setExecutable(false)
        }

        assertNull(resolveExecutable("myeditor", listOf(dir)))
    }

    @Test
    fun resolveExecutableReturnsNullForMissingName() {
        val dir = createTempDirectory("openlog-resolve-exec-missing").toFile()

        assertNull(resolveExecutable("no-such-editor", listOf(dir)))
    }

    @Test
    fun resolveExecutableReturnsExecutableForAbsolutePath() {
        val dir = createTempDirectory("openlog-resolve-exec-abs").toFile()
        val exe = File(dir, "myeditor").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

        assertEquals(exe, resolveExecutable(exe.absolutePath, emptyList()))
    }

    @Test
    fun resolveExecutableReturnsNullForMissingAbsolutePath() {
        val dir = createTempDirectory("openlog-resolve-exec-abs-missing").toFile()
        val missing = File(dir, "does-not-exist")

        assertNull(resolveExecutable(missing.absolutePath, emptyList()))
    }

    @Test
    fun resolveExecutableReturnsNullForDirectoryPath() {
        // A directory is traversable (canExecute() == true) but is not itself launchable — the
        // isFile guard must reject it, otherwise splitEditorCommand's greedy prefix search would
        // wrongly accept a directory prefix of a space-containing executable path.
        val dir = createTempDirectory("openlog-resolve-exec-dir").toFile()
        val subDir = File(dir, "Application Support").apply { mkdirs() }

        assertNull(resolveExecutable(subDir.absolutePath, emptyList()))
    }

    @Test
    fun splitEditorCommandResolvesExecutablePathContainingASpace() {
        val dir = createTempDirectory("openlog-split-editor-space").toFile()
        val binDir = File(dir, "App Support/bin").apply { mkdirs() }
        val exe = File(binDir, "idea").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val template = "${exe.absolutePath} --line {line} {file}"

        val result = splitEditorCommand(template, emptyList())

        assertEquals(exe, result?.first)
        assertEquals(listOf("--line", "{line}", "{file}"), result?.second)
    }

    @Test
    fun toolboxStyleEditorCommandPassesTheExactCallLineAndFileAsSeparateArguments() {
        val dir = createTempDirectory("openlog-editor-args").toFile()
        val binDir = File(dir, "Application Support/JetBrains/Toolbox/scripts").apply { mkdirs() }
        val exe = File(binDir, "idea").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val source = File(dir, "Source File.kt").apply { writeText("class SourceFile\n") }

        val args = editorCommandArguments(
            "${exe.absolutePath} --line {line} {file}",
            source,
            line = 37,
            dirs = emptyList(),
        )

        assertEquals(listOf(exe.absolutePath, "--line", "37", source.absolutePath), args)
    }

    @Test
    fun invalidConfiguredEditorCommandDoesNotFallBackToOpeningWithoutALine() {
        val dir = createTempDirectory("openlog-editor-failure").toFile()
        val state = newState(dir)
        state.updateSettings { it.copy(editorCommand = "not-an-editor --line {line} {file}") }

        state.openInEditor(File(dir, "Source.kt").absolutePath, 37)
        waitUntil { state.openError != null }

        assertEquals("Could not open code location", state.openError?.title)
    }

    @Test
    fun splitEditorCommandResolvesBareCommandFoundInSearchDirs() {
        val dir = createTempDirectory("openlog-split-editor-bare").toFile()
        val exe = File(dir, "idea").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        val template = "idea --line {line} {file}"

        val result = splitEditorCommand(template, listOf(dir))

        assertEquals(exe, result?.first)
        assertEquals(listOf("--line", "{line}", "{file}"), result?.second)
    }

    @Test
    fun splitEditorCommandReturnsNullWhenNothingResolves() {
        val dir = createTempDirectory("openlog-split-editor-missing").toFile()

        assertNull(splitEditorCommand("no-such-editor --line {line} {file}", listOf(dir)))
    }
}

private fun String.legacyB64(): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.legacyFieldToken(): String = if (isEmpty()) "~" else legacyB64()
