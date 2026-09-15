package com.indagium

import com.indagium.ui.forwardedDropFilesWhenNotesLocked
import com.indagium.ui.notesMutationAllowed
import com.indagium.ui.reconcileMarkdownDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationEditPolicyTest {
    @Test
    fun pristineDraftFollowsUpstreamChange() {
        val result = reconcileMarkdownDraft("old", "old", "latest")

        assertEquals("latest", result.baseline)
        assertEquals("latest", result.draft)
        assertFalse(result.conflict)
    }

    @Test
    fun dirtyDraftIsPreservedAndConflicted() {
        val result = reconcileMarkdownDraft("old", "my draft", "latest")

        assertEquals("old", result.baseline)
        assertEquals("my draft", result.draft)
        assertEquals("latest", result.latest)
        assertTrue(result.conflict)
    }

    @Test
    fun unchangedUpstreamDoesNotConflict() {
        val result = reconcileMarkdownDraft("same", "draft", "same")

        assertEquals("same", result.baseline)
        assertEquals("draft", result.draft)
        assertFalse(result.conflict)
    }

    @Test
    fun activeAiRunLocksNotesMutationsOnly() {
        assertFalse(notesMutationAllowed(notesLocked = true))
        assertTrue(notesMutationAllowed(notesLocked = false))
    }

    @Test
    fun lockedNotesForwardsOnlyNonImageDrops() {
        val files = listOf("trace.log", "shot.png", "capture.mp4", "readme.txt")

        assertEquals(
            listOf("trace.log", "capture.mp4", "readme.txt"),
            forwardedDropFilesWhenNotesLocked(files) { it.endsWith(".png") },
        )
    }
}
