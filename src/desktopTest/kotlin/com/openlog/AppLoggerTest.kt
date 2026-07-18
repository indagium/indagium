package com.openlog

import com.openlog.debug.AppLogger
import com.openlog.utils.parseLogcat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoggerTest {
    @AfterTest
    fun tearDown() {
        AppLogger.configure(enabled = false, path = null)
    }

    @Test
    fun disabledLoggerNeverCreatesAFile() {
        val dir = createTempDirectory("openlog-applogger-disabled").toFile()
        val logFile = File(dir, "debug.log")
        AppLogger.configure(enabled = false, path = logFile.absolutePath)

        AppLogger.info("test", "should not be written")

        assertFalse(logFile.exists())
    }

    @Test
    fun enabledLoggerWritesUtf8ThreadtimeRowsThatTheParserCanOpen() {
        val dir = createTempDirectory("openlog-applogger-enabled").toFile()
        val logFile = File(dir, "debug.log")
        AppLogger.configure(enabled = true, path = logFile.absolutePath)

        AppLogger.debug("open", "Opened foo.log (3 entries)")
        AppLogger.info("open", "Loaded 3 entries")
        AppLogger.warn("open", "A recoverable condition occurred")
        AppLogger.error("autosave", "Failed to write", IllegalStateException("disk full"))

        val content = logFile.readText()
        val entries = parseLogcat(logFile)
        assertTrue(entries.size >= 6) // message rows plus one header for every stack-trace line
        assertTrue(entries.all { it.tag.startsWith("openLog.") })
        assertTrue(entries.any { it.level.name == "D" && it.tag == "openLog.open" })
        assertTrue(entries.any { it.level.name == "W" })
        assertTrue(entries.any { it.level.name == "E" && it.msg.contains("IllegalStateException: disk full") })
        val threadtime = Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+ [DIWE] openLog\\.[^:]+: .+")
        assertTrue(content.lines().filter { it.isNotBlank() }.all { it.matches(threadtime) })
    }

    @Test
    fun disablingClosesTheFileSoLaterLogCallsAreNoOps() {
        val dir = createTempDirectory("openlog-applogger-toggle").toFile()
        val logFile = File(dir, "debug.log")
        AppLogger.configure(enabled = true, path = logFile.absolutePath)
        AppLogger.info("app", "first line")

        AppLogger.configure(enabled = false, path = null)
        AppLogger.info("app", "second line")

        val content = logFile.readText()
        assertTrue(content.contains("first line"))
        assertFalse(content.contains("second line"))
    }

    @Test
    fun reconfiguringToANewPathSwitchesTheOpenFile() {
        val dir = createTempDirectory("openlog-applogger-switch").toFile()
        val firstFile = File(dir, "first.log")
        val secondFile = File(dir, "second.log")
        AppLogger.configure(enabled = true, path = firstFile.absolutePath)
        AppLogger.info("app", "to first file")

        AppLogger.configure(enabled = true, path = secondFile.absolutePath)
        AppLogger.info("app", "to second file")

        assertTrue(firstFile.readText().contains("to first file"))
        assertFalse(firstFile.readText().contains("to second file"))
        assertTrue(secondFile.readText().contains("to second file"))
    }

    @Test
    fun configureCreatesMissingParentDirectories() {
        val dir = createTempDirectory("openlog-applogger-mkdirs").toFile()
        val logFile = File(dir, "nested/sub/debug.log")
        AppLogger.configure(enabled = true, path = logFile.absolutePath)

        AppLogger.info("app", "line")

        assertEquals(true, logFile.exists())
    }

    @Test
    fun messagesAreSingleLineAndRedactPathsAndSecrets() {
        val dir = createTempDirectory("openlog-applogger-privacy").toFile()
        val logFile = File(dir, "debug.log")
        AppLogger.configure(enabled = true, path = logFile.absolutePath)

        AppLogger.error(
            "ai",
            "request /private/user.log\nAuthorization: BearerSecret token=abc123 C:\\Users\\me\\secret.txt",
        )

        val text = logFile.readText()
        assertFalse(text.contains("/private/user.log"))
        assertFalse(text.contains("C:\\Users\\me\\secret.txt"))
        assertFalse(text.contains("BearerSecret"))
        assertFalse(text.contains("abc123"))
        assertTrue(text.contains("[PATH]"))
        assertTrue(text.contains("token=[REDACTED]"))
        assertEquals(1, text.lines().count { it.isNotBlank() })
    }
}
