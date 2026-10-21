package com.indagium

import com.indagium.utils.CaptureZstd
import kotlin.test.Test
import kotlin.test.assertTrue

class CaptureZstdTest {
    @Test
    fun nativeLibraryLoadsOnTheBuildHost() {
        assertTrue(CaptureZstd.isAvailable, "the bundled zstd-jni classifier jar must load on the host running the tests")
    }
}
