package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.indagium.model.ThemePreset
import com.indagium.ui.AnnotationMarkdownText
import com.indagium.ui.themeColors
import org.junit.Rule
import org.junit.Test

/**
 * A tab switch disposes the notes panel, so every visit composes note text from scratch. The
 * markdown must already be laid out on that very first frame; an async parse renders zero-height
 * text first and the following note cards then glide down when the real height arrives.
 */
@OptIn(ExperimentalTestApi::class)
class AnnotationMarkdownFirstFrameUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun noteTextHasRealHeightOnFirstCompositionFrame() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AnnotationMarkdownText("Device attached", themeColors(ThemePreset.DRACULA))
        }

        rule.onNodeWithText("Device attached").assertHeightIsAtLeast(1.dp)
    }
}
