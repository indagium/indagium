package com.indagium

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.ui.DiagramAttachmentKind
import com.indagium.ui.DiagramLibraryAttachment
import com.indagium.ui.DiagramLibrarySnapshot
import com.indagium.ui.DiagramLibraryStore
import com.indagium.ui.DiagramSourceIdentity
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagramLibraryRetargetTest {
    private fun newItem(store: DiagramLibraryStore, title: String) = store.create(
        title,
        "",
        DiagramSourceIdentity.NONE,
        DiagramLibrarySnapshot(encodeSeq3Note(Seq3Document(title = title), Seq3Dialect.PLANTUML)),
    )

    private fun attachment(tab: String, block: String) = DiagramLibraryAttachment(tab, block, DiagramAttachmentKind.SNAPSHOT, 1L)

    @Test
    fun draftUpdateOverlappingAMoveKeepsTheNewOwnerAndTheDraft() {
        val root = createTempDirectory("indagium-library-draft-move").toFile()
        try {
            val file = File(root, "library.cache")
            val store = DiagramLibraryStore(file)
            val item = newItem(store, "before edit")
            store.addAttachment(item.id, attachment("a", "block"))
            val move = FutureTask { store.retargetAttachments("a", "host", listOf("block")) }
            val mover = Thread(move)
            val edited = DiagramLibrarySnapshot.create(Seq3Document(title = "edited draft"))
            val draft = store.tryUpdate(item.id) { previous ->
                mover.start()
                // Let the move either finish or reach the lock protecting this draft edit.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!move.isDone && mover.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(move.isDone || mover.state == Thread.State.BLOCKED)
                previous.copy(title = "edited draft", snapshot = edited)
            }
            assertNotNull(draft).getOrThrow()
            assertTrue(move.get(5, TimeUnit.SECONDS))

            val restored = assertNotNull(DiagramLibraryStore(file).get(item.id))
            assertEquals("edited draft", restored.title)
            assertEquals(edited, restored.snapshot)
            assertEquals(listOf(attachment("host", "block")), restored.attachments)
            assertTrue(store.removeAttachments("host", listOf("block")))
            assertFalse(assertNotNull(DiagramLibraryStore(file).get(item.id)).isAttached)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retargetingMovesOnlyTheNamedBlocksOfTheNamedTabAndPersistsOnce() {
        val root = createTempDirectory("indagium-library-retarget").toFile()
        try {
            val file = File(root, "library.cache")
            val store = DiagramLibraryStore(file)
            val first = newItem(store, "first")
            val second = newItem(store, "second")
            store.addAttachment(first.id, attachment("a", "n1"))
            store.addAttachment(first.id, attachment("a", "n2"))
            store.addAttachment(first.id, attachment("c", "n1"))
            store.addAttachment(second.id, attachment("a", "n1"))

            assertTrue(store.retargetAttachments("a", "host", listOf("n1")))

            assertEquals(setOf("host" to "n1", "a" to "n2", "c" to "n1"), store.get(first.id)!!.attachments.map { it.tabId to it.blockId }.toSet())
            assertEquals(listOf("host" to "n1"), store.get(second.id)!!.attachments.map { it.tabId to it.blockId })
            val reloaded = DiagramLibraryStore(file)
            assertEquals(3, assertNotNull(reloaded.get(first.id)).attachments.size)
            assertEquals(listOf("host" to "n1"), reloaded.get(second.id)!!.attachments.map { it.tabId to it.blockId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retargetingOntoAnExistingReferenceKeepsOneReferencePerBlock() {
        val root = createTempDirectory("indagium-library-retarget-dedupe").toFile()
        try {
            val store = DiagramLibraryStore(File(root, "library.cache"))
            val item = newItem(store, "item")
            store.addAttachment(item.id, attachment("a", "n1"))
            store.addAttachment(item.id, attachment("host", "n1"))

            assertTrue(store.retargetAttachments("a", "host", listOf("n1")))

            assertEquals(listOf("host" to "n1"), store.get(item.id)!!.attachments.map { it.tabId to it.blockId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retargetingNothingOrToABlankTabChangesNothingAndDoesNotThrow() {
        val root = createTempDirectory("indagium-library-retarget-noop").toFile()
        try {
            val store = DiagramLibraryStore(File(root, "library.cache"))
            val item = newItem(store, "item")
            store.addAttachment(item.id, attachment("a", "n1"))

            assertFalse(store.retargetAttachments("a", "host", emptyList()))
            assertFalse(store.retargetAttachments("a", "host", listOf("other")))
            assertFalse(store.retargetAttachments("a", "", listOf("n1")))
            assertFalse(store.retargetAttachments("a", "a", listOf("n1")))
            assertEquals(listOf("a" to "n1"), store.get(item.id)!!.attachments.map { it.tabId to it.blockId })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removingAttachmentsDropsOnlyTheNamedBlocksOfTheNamedTabInOneWrite() {
        val root = createTempDirectory("indagium-library-remove-attachments").toFile()
        try {
            val file = File(root, "library.cache")
            val store = DiagramLibraryStore(file)
            val first = newItem(store, "first")
            val second = newItem(store, "second")
            store.addAttachment(first.id, attachment("a", "n1"))
            store.addAttachment(first.id, attachment("a", "n2"))
            store.addAttachment(first.id, attachment("c", "n1"))
            store.addAttachment(second.id, attachment("a", "n1"))

            assertTrue(store.removeAttachments("a", listOf("n1", "n9")))

            assertEquals(setOf("a" to "n2", "c" to "n1"), store.get(first.id)!!.attachments.map { it.tabId to it.blockId }.toSet())
            assertFalse(store.get(second.id)!!.isAttached)
            assertFalse(DiagramLibraryStore(file).get(second.id)!!.isAttached, "persisted")
            assertFalse(store.removeAttachments("a", emptyList()))
            assertFalse(store.removeAttachments("a", listOf("n1")), "nothing left to remove")
        } finally {
            root.deleteRecursively()
        }
    }
}
