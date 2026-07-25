package com.openlog

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.ui.AppState
import com.openlog.ui.mkTab
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnnotationImageInsertionTest {
    private fun pngBytes(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    @Test
    fun imageEvidenceInsertsAfterAnchorAndAppendsForStaleAnchor() {
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("t1", "test.log", emptyList()).copy(
                annotations = Annotations(
                    blocks = listOf(AnnBlock.Note("first", "first"), AnnBlock.Note("last", "last")),
                ),
            ),
        )

        val afterFirst = state.addImageBlock("t1", pngBytes(), "pasted from clipboard", "first")!!
        val appended = state.addImageBlock("t1", pngBytes(), "dropped image.png", "removed-anchor")!!

        assertEquals(listOf("first", afterFirst, "last", appended), state.tab("t1")!!.annotations.blocks.map { it.id })
    }

    @Test
    fun theFirstImageStampsTheAnalysisAndLaterImagesLeaveThatStampAlone() {
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(mkTab("t1", "test.log", emptyList()))

        state.addImageBlock("t1", pngBytes(), "pasted from clipboard", null)
        val stamped = state.tab("t1")!!.annotations.frameStamp
        assertNotNull(stamped)
        // A stamp that moved on the second image would rename the first image's exported file and
        // break any `!frame-<stamp>-01.jpg!` anchor already pasted into a ticket.
        state.addImageBlock("t1", pngBytes(), "pasted from clipboard", null)
        assertEquals(stamped, state.tab("t1")!!.annotations.frameStamp)
    }

    @Test
    fun anAnalysisThatAlreadyHadImagesBeforeStampingExistedIsNeverStampedLater() {
        // Legacy note: images already present, frameStamp still null. Stamping it now would rename
        // every frame it has already exported (frame-01.jpg -> frame-<stamp>-01.jpg), orphaning the
        // files in its _frames folder and invalidating anchors already shared in Jira. It must keep
        // the unstamped naming permanently.
        val state = AppState()
        state.updateSettings { it.copy(autoExportNotes = false) }
        state.tabs = listOf(
            mkTab("t1", "test.log", emptyList()).copy(
                annotations = Annotations(
                    blocks = listOf(
                        AnnBlock.Image(id = "legacy", caption = "", provenance = "", format = "jpeg", bytes = byteArrayOf(1)),
                    ),
                ),
            ),
        )

        state.addImageBlock("t1", pngBytes(), "pasted from clipboard", null)

        assertNull(state.tab("t1")!!.annotations.frameStamp)
    }
}
