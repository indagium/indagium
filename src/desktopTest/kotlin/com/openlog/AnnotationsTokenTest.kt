package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.VideoFrameReference
import com.openlog.model.VideoSource
import com.openlog.ui.annotationsFromToken
import com.openlog.ui.annotationsToken
import com.openlog.ui.tokenFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the backward-compatibility-sensitive `.ann` sidecar / autosave token format
 * (Annotations.annotationsToken / String.annotationsFromToken, AutosaveCodec.kt) after
 * appending `appVersion` (field index 5), `decisiveTags` (field index 6) for the "similar
 * past issues" retrieval feature (com.openlog.cases), and `frameStamp` (field index 7) for the
 * unique-exported-frame-filenames fix (utils/annotationImageFileName).
 *
 * All three new fields are APPENDED after the original 5 fields (prefix, suffix, blocks,
 * issueDescription, sourcePath) — never inserted/reordered — so:
 *  - a legacy 5-field token (written before this change) must still parse with no error, with
 *    appVersion/decisiveTags defaulting to empty and frameStamp defaulting to null;
 *  - a new 8-field token must round-trip losslessly, including through the no-sourcePath
 *    autosave (tabToken) path.
 */
class AnnotationsTokenTest {
    @Test
    fun roundTripsAppVersionAndDecisiveTagsWithSourcePath() {
        val original = Annotations(
            blocks = listOf(AnnBlock.Note("n1", "root cause: race condition")),
            prefix = "prefix text",
            suffix = "suffix text",
            issueDescription = "App crashes on cold start",
            appVersion = "1.5.2",
            decisiveTags = listOf("ActivityManager", "CrashHandler"),
        )
        val token = original.annotationsToken("/path/to/source.log")
        val restored = token.annotationsFromToken()

        assertEquals(original, restored)
    }

    @Test
    fun roundTripsThroughTheNoSourcePathAutosavePath() {
        // Mirrors tabToken(): annotationsToken() is called with NO sourcePath argument there —
        // field 4 (sourcePath) must come back empty while fields 5/6 still round-trip.
        val original = Annotations(
            issueDescription = "ANR in main thread",
            appVersion = "2.0.0-beta",
            decisiveTags = listOf("ANR"),
        )
        val token = original.annotationsToken()
        val restored = token.annotationsFromToken()

        assertEquals(original, restored)
    }

    @Test
    fun roundTripsImageBlockBytesExactlyThroughBase64() {
        // Deliberately includes 0x00 and 0xFF bytes (not valid UTF-8 on their own) — this is the
        // scenario .b64()/.unb64() (String<->UTF-8) would corrupt; annBlockToken/FromToken must
        // route raw bytes through java.util.Base64 directly instead.
        val rawBytes = byteArrayOf(0x00, 0x01, 0x7F.toByte(), 0x80.toByte(), 0xFF.toByte(), 0x42, 0x00)
        val original = Annotations(
            blocks = listOf(
                AnnBlock.Image(
                    id = "i1",
                    caption = "Crash moment",
                    provenance = "from bugreport.zip/screen.mp4",
                    format = "jpeg",
                    bytes = rawBytes,
                ),
            ),
        )
        val token = original.annotationsToken()
        val restored = token.annotationsFromToken()

        requireNotNull(restored)
        val restoredImage = restored.blocks.single() as AnnBlock.Image
        assertTrue(rawBytes.contentEquals(restoredImage.bytes), "image bytes must survive the round trip exactly")
        assertEquals("Crash moment", restoredImage.caption)
        assertEquals("from bugreport.zip/screen.mp4", restoredImage.provenance)
        assertEquals("jpeg", restoredImage.format)
        // Also exercises AnnBlock.Image's overridden (content-based) equals — see Model.kt's doc
        // comment on why the default array-field equals would fail this even though decode is
        // byte-for-byte correct.
        assertEquals(original, restored)
    }

    @Test
    fun roundTripsVideoFrameIdentityLabelAndExactPosition() {
        val original = Annotations(
            blocks = listOf(
                AnnBlock.Image(
                    id = "frame-1",
                    caption = "Crash dialog appears",
                    provenance = "From bugreport.zip/screen.mp4",
                    format = "jpeg",
                    bytes = byteArrayOf(0x01, 0x7F, 0x00),
                    videoFrame = VideoFrameReference(
                        source = VideoSource.ArchiveEntry(
                            archivePath = "/reports/bugreport.zip",
                            entryPath = "FS/data/screen.mp4",
                            displayName = "screen.mp4",
                        ),
                        sourceLabel = "bugreport.zip/screen.mp4",
                        positionMs = 12_345L,
                    ),
                ),
            ),
        )

        val restored = requireNotNull(original.annotationsToken().annotationsFromToken())
        val image = restored.blocks.single() as AnnBlock.Image

        assertEquals(original, restored)
        assertEquals("From bugreport.zip/screen.mp4 @ 00:12.345", image.videoFrame?.provenanceLabel)
        assertEquals(12_345L, image.videoFrame?.positionMs)
    }

    @Test
    fun parsesLegacyFiveFieldTokenWithEmptyNewFieldsAndNoCrash() {
        val current = Annotations(
            blocks = listOf(AnnBlock.Note("n1", "legacy note")),
            prefix = "p",
            suffix = "s",
            issueDescription = "legacy issue",
            appVersion = "should-not-appear",
            decisiveTags = listOf("should-not-appear-either"),
            frameStamp = "should-not-appear-either",
        )
        val fullToken = current.annotationsToken("/legacy/source.log")
        // Simulate a pre-existing .ann sidecar written before appVersion/decisiveTags/frameStamp
        // existed: exactly the first 5 "|"-separated fields, nothing appended.
        val legacyToken = fullToken.split("|").take(5).joinToString("|")

        val restored = legacyToken.annotationsFromToken()
        assertTrue(restored != null, "a legacy 5-field token must still parse")
        requireNotNull(restored)

        assertEquals("p", restored.prefix)
        assertEquals("s", restored.suffix)
        assertEquals("legacy issue", restored.issueDescription)
        assertEquals(1, restored.blocks.size)
        assertEquals("", restored.appVersion)
        assertEquals(emptyList(), restored.decisiveTags)
        assertEquals(null, restored.frameStamp, "a legacy note must keep its original unstamped frame names")
    }

    @Test
    fun oldReaderShapeReadingOnlyFieldsZeroToFourIsUnaffected() {
        // Confirms readSourceFingerprint's read path (AppState.kt: tokenFields().getOrNull(4))
        // still sees sourcePath at index 4 unaffected by the newly appended trailing fields.
        val token = Annotations(issueDescription = "desc", appVersion = "9.9.9", decisiveTags = listOf("X"))
            .annotationsToken("/some/source/path.log")
        val fields = token.tokenFields()
        assertEquals(8, fields.size, "new token must carry exactly 8 fields (0..7)")
        assertEquals("/some/source/path.log", fields.getOrNull(4))
        assertEquals("9.9.9", fields.getOrNull(5))
        assertEquals("X", fields.getOrNull(6))
        assertEquals("", fields.getOrNull(7), "no frameStamp was set, so field 7 is empty (not absent)")
    }

    @Test
    fun roundTripsFrameStampWithSourcePath() {
        val original = Annotations(
            blocks = listOf(AnnBlock.Note("n1", "root cause: race condition")),
            issueDescription = "App crashes on cold start",
            frameStamp = "20260725-143012",
        )
        val restored = original.annotationsToken("/path/to/source.log").annotationsFromToken()

        assertEquals(original, restored)
        assertEquals("20260725-143012", restored?.frameStamp)
    }

    @Test
    fun frameStampDefaultsToNullWhenNeverSet() {
        // An analysis that never gained an image block never gains a frameStamp either — its
        // token must round-trip with frameStamp still null, not an empty string masquerading as
        // "no stamp yet".
        val original = Annotations(blocks = listOf(AnnBlock.Note("n1", "no images here")))
        val restored = original.annotationsToken().annotationsFromToken()

        assertEquals(original, restored)
        assertEquals(null, restored?.frameStamp)
    }
}
