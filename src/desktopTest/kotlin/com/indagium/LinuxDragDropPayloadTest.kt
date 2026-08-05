package com.indagium

import com.indagium.ui.localFilesFromUriList
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxDragDropPayloadTest {
    @Test
    fun uriListDropsDecodeLocalFilesInOrder() {
        val first = File("/tmp/log one.log")
        val second = File("/tmp/second.log")

        val dropped = localFilesFromUriList(
            """
            # A text/uri-list comment emitted by some Linux file managers
            ${first.toURI()}
            ${second.toURI()}
            """.trimIndent(),
        )

        assertEquals(listOf(first, second), dropped)
    }

    @Test
    fun uriListDropsIgnoreNonLocalAndMalformedEntries() {
        val local = File("/tmp/local.log")

        val dropped = localFilesFromUriList(
            """
            https://example.com/not-a-file.log
            not a URI
            ${local.toURI()}
            """.trimIndent(),
        )

        assertEquals(listOf(local), dropped)
    }
}
