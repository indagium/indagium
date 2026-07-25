package com.openlog

import com.openlog.model.LogTab
import com.openlog.model.VideoAnchor
import com.openlog.model.VideoAttachment
import com.openlog.model.VideoSource
import com.openlog.ui.b64
import com.openlog.ui.tabShellFromToken
import com.openlog.ui.tabToken
import com.openlog.ui.tokenFields
import com.openlog.ui.unb64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers VideoAttachment's autosave round-trip (LogTab.tabToken/String.tabShellFromToken,
 * AutosaveCodec.kt) after appending `attachedVideo` as the tab token's field index 11 — mirrors
 * archiveCandidate's own field-index-9 precedent (see AnnotationsTokenTest for the same discipline
 * on the Annotations token). Appended LAST so a legacy (11-field, pre-video) tab token still
 * parses with attachedVideo defaulting to null.
 *
 * tabShellFromToken() requires the tab's sourcePath to point at a real, existing file (it drops
 * any shell whose backing file is gone) — every fixture below backs sourcePath with a real temp
 * file for exactly that reason (see AutosaveGoldenV1Test's own doc comment on the same
 * constraint).
 */
class VideoAttachmentTokenTest {
    private val tempDir = createTempDirectory("openlog-video-token-test").toFile()

    private fun tabFixture(attachedVideo: VideoAttachment?): LogTab {
        val logFile = tempDir.resolve("app-${System.nanoTime()}.log").apply { writeText("10:00:00.000 I/Tag: hello\n") }
        return LogTab(
            id = "t1",
            filename = logFile.name,
            logData = emptyList(),
            rmap = emptyMap(),
            sourcePath = logFile.absolutePath,
            attachedVideo = attachedVideo,
        )
    }

    @Test
    fun roundTripsAttachedVideoWithAnchor() {
        val original = tabFixture(
            VideoAttachment(
                path = "/videos/repro.mp4",
                sourceLabel = "bugreport.zip/repro.mp4",
                durationMs = 125_000,
                anchor = VideoAnchor(videoMs = 5_400, logId = 42),
            ),
        )
        val restored = original.tabToken().tabShellFromToken()
        assertEquals(original.attachedVideo, restored?.tab?.attachedVideo)
    }

    @Test
    fun roundTripsNoAttachedVideoAsNull() {
        val original = tabFixture(attachedVideo = null)
        val restored = original.tabToken().tabShellFromToken()
        assertNull(restored?.tab?.attachedVideo)
    }

    @Test
    fun roundTripsAttachedVideoWithNoAnchorYet() {
        val original = tabFixture(VideoAttachment(path = "/videos/repro.mp4", sourceLabel = "/videos/repro.mp4"))
        val restored = original.tabToken().tabShellFromToken()
        assertEquals(original.attachedVideo, restored?.tab?.attachedVideo)
        assertNull(restored?.tab?.attachedVideo?.anchor)
    }

    @Test
    fun roundTripsArchiveVideoAsDurableArchiveReference() {
        val original = tabFixture(
            VideoAttachment(
                source = VideoSource.ArchiveEntry(
                    archivePath = "/reports/bugreport.zip",
                    entryPath = "FS/data/repro/screen.mp4",
                    displayName = "screen.mp4",
                ),
                sourceLabel = "bugreport.zip/screen.mp4",
                durationMs = 90_000,
            ),
        )

        val restored = original.tabToken().tabShellFromToken()

        assertEquals(original.attachedVideo, restored?.tab?.attachedVideo)
        assertEquals(
            VideoSource.ArchiveEntry("/reports/bugreport.zip", "FS/data/repro/screen.mp4", "screen.mp4"),
            restored?.tab?.attachedVideo?.source,
        )
    }

    @Test
    fun legacyElevenFieldTokenWithoutAttachedVideoStillParses() {
        // Simulate a tab token written before attachedVideo existed: everything through
        // showTimeDelta (field index 10), nothing appended after it.
        val fullToken = tabFixture(
            VideoAttachment(path = "/videos/repro.mp4", sourceLabel = "/videos/repro.mp4"),
        ).tabToken()
        val legacyToken = fullToken.split("|").take(11).joinToString("|")

        val restored = legacyToken.tabShellFromToken()
        assertNull(restored?.tab?.attachedVideo, "a legacy 11-field tab token must still parse, with attachedVideo defaulting to null")
    }

    @Test
    fun tabTokenCarriesAttachedVideoAsFieldIndexEleven() {
        val original = tabFixture(VideoAttachment(path = "/videos/repro.mp4", sourceLabel = "/videos/repro.mp4"))
        val fields = original.tabToken().tokenFields()
        // 13 fields (0..12) since noteTargetName (LogTab's auto-export-overwrite pin) was appended
        // as a 13th trailing field after attachedVideo — attachedVideo itself stays at index 11.
        assertEquals(13, fields.size, "tab token must carry exactly 13 fields (0..12) once attachedVideo is set")
    }

    @Test
    fun roundTripsRotationDegrees() {
        val original = tabFixture(
            VideoAttachment(
                path = "/videos/repro.mp4",
                sourceLabel = "/videos/repro.mp4",
                anchor = null,
            ).copy(rotationDegrees = 270),
        )
        val restored = original.tabToken().tabShellFromToken()
        assertEquals(270, restored?.tab?.attachedVideo?.rotationDegrees)
        assertEquals(original.attachedVideo, restored?.tab?.attachedVideo)
    }

    @Test
    fun legacyAttachedVideoTokenWithoutRotationDefaultsToZero() {
        // Simulate an attachedVideo token written before rotationDegrees existed: only the first
        // 8 fields (through displayName), nothing appended after it. attachedVideo is itself a
        // b64-blob within field index 11 of the tab token, so it must be unwrapped, truncated and
        // re-wrapped rather than truncated at the outer level (see the sibling legacy-11-field test,
        // which truncates the OUTER token because dropping a whole trailing field is safe there).
        val fullTabToken = tabFixture(
            VideoAttachment(path = "/videos/repro.mp4", sourceLabel = "/videos/repro.mp4").copy(rotationDegrees = 180),
        ).tabToken()
        val rawFields = fullTabToken.split("|").toMutableList()
        val legacyAttachedVideoRaw = rawFields[11].unb64().split("|").take(8).joinToString("|")
        rawFields[11] = legacyAttachedVideoRaw.b64()
        val legacyTabToken = rawFields.joinToString("|")

        val restored = legacyTabToken.tabShellFromToken()
        assertEquals(0, restored?.tab?.attachedVideo?.rotationDegrees, "a legacy 8-field attachment token must default rotationDegrees to 0")
    }
}
