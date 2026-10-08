package com.indagium

import com.indagium.testing.authoring.rewritePrompt
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import kotlin.test.Test
import kotlin.test.assertContains

class RecordingRewritePromptIntentTest {
    @Test
    fun guidanceGeneralizesTitlesAndMergesControlRevealGestures() {
        val prompt = rewritePrompt(
            TestSuite("suite", "Video suite", targetPackage = "com.google.android.youtube"),
            TestCase("case", "Play a selected video", description = "Open a video and pause it."),
            note = "",
            inputs = emptyList(),
        )
        val normalizedPrompt = prompt.replace(Regex("\\s+"), " ")

        assertContains(normalizedPrompt, "use an exact video/title only when the goal, preconditions, or the person's note")
        assertContains(normalizedPrompt, "Otherwise describe a generic selection/playback action")
        assertContains(normalizedPrompt, "merge both inputs into one \"Pause playback\" step")
        assertContains(normalizedPrompt, "unless revealing them is itself the goal")
        assertContains(normalizedPrompt, "preserve that dismissal as its own conditional step")
        assertContains(normalizedPrompt, "never merge an optional ad dismissal with a required action such as Pause")
    }
}
