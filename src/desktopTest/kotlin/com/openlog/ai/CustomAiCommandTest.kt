package com.openlog.ai

import com.openlog.ui.AppState
import com.openlog.ui.resolveSlashCommand
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomAiCommandTest {
    private val commands = listOf(
        CustomAiCommand("timeline", "Build a timeline around the selected line."),
        CustomAiCommand("Root-Cause", "Investigate the selected line for a root cause."),
    )

    @Test
    fun resolveSlashCommandMatchesExactNameCaseInsensitive() {
        assertEquals("Build a timeline around the selected line.", resolveSlashCommand("/timeline", commands))
        assertEquals("Investigate the selected line for a root cause.", resolveSlashCommand("/root-cause", commands))
    }

    @Test
    fun resolveSlashCommandAppendsTrailingText() {
        assertEquals(
            "Build a timeline around the selected line.\n\nfocus on the network stack",
            resolveSlashCommand("/timeline   focus on the network stack", commands),
        )
    }

    @Test
    fun resolveSlashCommandWithNoTrailingTextReturnsTemplateVerbatim() {
        assertEquals("Build a timeline around the selected line.", resolveSlashCommand("  /timeline  ", commands))
    }

    @Test
    fun resolveSlashCommandWithNoMatchReturnsInputUnchanged() {
        assertEquals("/doesnotexist please help", resolveSlashCommand("/doesnotexist please help", commands))
    }

    @Test
    fun resolveSlashCommandIgnoresPlainTextWithoutLeadingSlash() {
        assertEquals("just a normal question", resolveSlashCommand("just a normal question", commands))
    }

    @Test
    fun resolveSlashCommandSupportsPredefinedActions() {
        assertEquals(
            "${AiQuickAction.TIMELINE.prompt}\n\nfocus on the network stack",
            resolveSlashCommand("/timeline focus on the network stack", emptyList()),
        )
    }

    @Test
    fun saveCustomAiCommandWritesFileAndReloadPicksItUpAcrossFreshAppState() {
        val dir = createTempDirectory("openlog-custom-commands-save").toFile()
        val cacheFile = File(dir, "state.cache")
        val commandsDir = File(dir, "custom-ai-commands")
        val state = AppState(autosaveFile = cacheFile, customCommandsDir = commandsDir)

        val error = state.saveCustomAiCommand("timeline", "Build a timeline.")

        assertNull(error)
        assertTrue(File(commandsDir, "timeline.md").exists())
        assertEquals(listOf(CustomAiCommand("timeline", "Build a timeline.")), state.customAiCommands)

        val reopened = AppState(autosaveFile = cacheFile, customCommandsDir = commandsDir)
        assertEquals(listOf(CustomAiCommand("timeline", "Build a timeline.")), reopened.customAiCommands)
    }

    @Test
    fun saveCustomAiCommandRenamesByDeletingThePreviousFile() {
        val dir = createTempDirectory("openlog-custom-commands-rename").toFile()
        val commandsDir = File(dir, "custom-ai-commands")
        val state = AppState(autosaveFile = File(dir, "state.cache"), customCommandsDir = commandsDir)
        state.saveCustomAiCommand("old-name", "Some template.")

        state.saveCustomAiCommand("new-name", "Some template.", previousName = "old-name")

        assertTrue(!File(commandsDir, "old-name.md").exists())
        assertTrue(File(commandsDir, "new-name.md").exists())
        assertEquals(listOf(CustomAiCommand("new-name", "Some template.")), state.customAiCommands)
    }

    @Test
    fun deleteCustomAiCommandRemovesFileAndReloadsList() {
        val dir = createTempDirectory("openlog-custom-commands-delete").toFile()
        val commandsDir = File(dir, "custom-ai-commands")
        val state = AppState(autosaveFile = File(dir, "state.cache"), customCommandsDir = commandsDir)
        state.saveCustomAiCommand("timeline", "Build a timeline.")

        state.deleteCustomAiCommand("timeline")

        assertTrue(!File(commandsDir, "timeline.md").exists())
        assertEquals(emptyList(), state.customAiCommands)
    }

    @Test
    fun saveCustomAiCommandRejectsInvalidNameWithoutWritingAFile() {
        val dir = createTempDirectory("openlog-custom-commands-invalid").toFile()
        val commandsDir = File(dir, "custom-ai-commands")
        val state = AppState(autosaveFile = File(dir, "state.cache"), customCommandsDir = commandsDir)

        val blankError = state.saveCustomAiCommand("", "Some template.")
        val slashError = state.saveCustomAiCommand("has/slash", "Some template.")

        assertTrue(blankError != null)
        assertTrue(slashError != null)
        assertTrue(commandsDir.listFiles().isNullOrEmpty())
        assertEquals(emptyList(), state.customAiCommands)
    }
}
