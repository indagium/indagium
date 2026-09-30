package com.indagium

import com.indagium.model.LogTab
import com.indagium.ui.persistedSnapshot
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import com.indagium.ui.tokenFields
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * LogTab.captureSessionId (Phase 2a of "capture as a streaming LogTab") is deliberately
 * session-only — it must never appear in tabToken()'s field list and must always restore to null,
 * same house convention as tailing/search/tidMap (see Model.kt's own doc on the field). This is
 * the negative case: proving AutosaveCodec ignores the field entirely, not merely that it decodes
 * to something reasonable.
 *
 * tabShellFromToken() requires sourcePath to point at a real, existing file (drops any shell whose
 * backing file is gone) — see AutosaveGoldenV1Test's own doc on the same constraint.
 */
class CaptureSessionIdTokenTest {
    private val tempDir = createTempDirectory("openlog-capture-session-token-test").toFile()

    private fun tabFixture(captureSessionId: String?): LogTab {
        val logFile = tempDir.resolve("app-${System.nanoTime()}.log").apply { writeText("10:00:00.000 I/Tag: hello\n") }
        return LogTab(
            id = "t1",
            filename = logFile.name,
            logData = emptyList(),
            rmap = emptyMap(),
            sourcePath = logFile.absolutePath,
            captureSessionId = captureSessionId,
        )
    }

    @Test
    fun captureSessionIdIsNotPersisted() {
        val withSession = tabFixture("session-123")
        val withoutSession = withSession.copy(captureSessionId = null)

        assertEquals(
            withoutSession.tabToken().tokenFields().size,
            withSession.tabToken().tokenFields().size,
            "captureSessionId must not add a field to the tab token",
        )

        val restored = withSession.tabToken().tabShellFromToken()
        assertNull(restored?.tab?.captureSessionId, "captureSessionId is session-only and must never survive a restore")
    }

    @Test
    fun persistedSnapshotIgnoresCaptureSessionId() {
        val a = tabFixture("session-a")
        val b = a.copy(captureSessionId = "session-b")

        assertEquals(a.persistedSnapshot(), b.persistedSnapshot(), "two tabs differing only in captureSessionId must produce an equal persistedSnapshot")
    }
}
