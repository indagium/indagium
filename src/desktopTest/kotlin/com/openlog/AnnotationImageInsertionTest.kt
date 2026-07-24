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
}
