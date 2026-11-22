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
}
