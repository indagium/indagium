package com.openlog

import com.openlog.ui.jsonEscape
import com.openlog.ui.mcpConfigSnippet
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// user.dir is only a reliable stand-in for the project root during an unpackaged dev run
// (./gradlew desktopRun sets the JVM's working directory there). The control server can also be
// turned on from Settings in a normal installed .dmg/.deb/.msi — not a dev-only path — and there
// user.dir is whatever the OS handed the launched app (often unrelated to any project checkout),
// so blindly resolving against it once produced a broken-looking-real path
// ("/mcp-server/src/index.ts") that silently failed to spawn when pasted into LM Studio.
class McpConfigSnippetTest {
    // jpackage.app-path is the same packaged-vs-dev-run signal Main.kt already uses; clear it
    // after each test so a leaked value can't affect any other test sharing this JVM.
    @AfterTest
    fun clearPackagedFlag() {
        System.clearProperty("jpackage.app-path")
    }

    @Test
    fun devRunEmbedsAbsoluteServerPathUnderProjectRoot() {
        System.clearProperty("jpackage.app-path")
        val snippet = mcpConfigSnippet(8991)
        val expected = java.io.File(System.getProperty("user.dir"), "mcp-server/src/index.ts").absolutePath
        assertTrue(snippet.contains(expected.jsonEscape()), "expected snippet to contain absolute path $expected:\n$snippet")
        assertTrue(snippet.contains("\"http://127.0.0.1:8991\""))
    }

    @Test
    fun packagedRunEmitsObviousPlaceholderInsteadOfAWrongRealLookingPath() {
        System.setProperty("jpackage.app-path", "/Applications/openLog.app")
        val snippet = mcpConfigSnippet(8991)
        // Must not be user.dir-derived (that's what produced the silently-broken real-looking
        // path in the first place) and must be obviously a placeholder, not a real filesystem path.
        val userDirPath = java.io.File(System.getProperty("user.dir"), "mcp-server/src/index.ts").absolutePath
        assertFalse(snippet.contains(userDirPath.jsonEscape()))
        assertTrue(snippet.contains("/path/to/openLog2/mcp-server/src/index.ts"))
    }

    @Test
    fun jsonEscapeDoublesBackslashesForWindowsPaths() {
        assertTrue("C:\\\\Users\\\\me\\\\mcp-server" == "C:\\Users\\me\\mcp-server".jsonEscape())
    }
}
