package com.indagium.ui

import androidx.compose.ui.geometry.Rect
import com.indagium.capture.mirror.EmbeddedMirrorConnection
import com.indagium.capture.mirror.EmbeddedMirrorRuntime
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.MirrorFrame
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacMirrorSelectionTest {
    @Test
    fun macosSelectsNativeMirrorWhileOtherPlatformsRetainCompose() {
        assertTrue(shouldUseMacNativeMirror("Mac OS X"))
        assertTrue(shouldUseMacNativeMirror("macOS"))
        assertFalse(shouldUseMacNativeMirror("Linux"))
        assertFalse(shouldUseMacNativeMirror("Windows 11"))
    }

    @Test
    fun standaloneBackendHidesNativeSurfaceAfterComposeReplacement() {
        val first = blockingRuntime()
        val backend = MirrorBackend.StandaloneRuntime(first)
        try {
            backend.start("serial", com.indagium.capture.mirror.MirrorStreamOptions())
            await { first.snapshot().state == EmbeddedMirrorState.LIVE }

            backend.hideMacSurface()
            assertTrue(backend.snapshot().state == EmbeddedMirrorState.LIVE)
            assertTrue(backend.macSurface == null)
        } finally {
            backend.close()
        }
    }

    @Test
    fun nativeMirrorClipTracksComposeViewportIntersection() {
        val clip = mirrorClipFractions(
            full = Rect(100f, 200f, 500f, 600f),
            clipped = Rect(100f, 280f, 500f, 520f),
        )

        assertEquals(MirrorClipFractions(0f, 0.2f, 1f, 0.8f), clip)
        assertTrue(clip.isVisible)
    }

    @Test
    fun nativeMirrorClipHidesFullyClippedOrEmptyBounds() {
        assertEquals(
            MirrorClipFractions(0f, 0f, 0f, 0f),
            mirrorClipFractions(Rect(0f, 100f, 200f, 300f), Rect(0f, 0f, 0f, 0f)),
        )
        assertFalse(MirrorClipFractions(0f, 0f, 0f, 0f).isVisible)
    }

    @Test
    fun nativeMirrorClipIsEmptyWhileAComposePopupCouldOverlapIt() {
        val visible = MirrorClipFractions(0f, 0.2f, 1f, 0.8f)

        assertEquals(MirrorClipFractions(0f, 0f, 0f, 0f), effectiveMirrorClip(visible, overlayOccluded = true))
        assertEquals(visible, effectiveMirrorClip(visible, overlayOccluded = false))
        assertEquals(MirrorClipFractions(0f, 0f, 0f, 0f), effectiveMirrorClip(null, overlayOccluded = false))
    }

    private fun blockingRuntime(): EmbeddedMirrorRuntime = EmbeddedMirrorRuntime(
        transport = EmbeddedMirrorTransport { _, _ ->
            object : EmbeddedMirrorConnection {
                override val videoInput: InputStream = ByteArrayInputStream(ByteArray(0))
                override val audioInput: InputStream? = null
                override fun sendControl(bytes: ByteArray) = Unit
                override fun close() = Unit
            }
        },
        decoder = object : H264Decoder {
            override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                while (!Thread.currentThread().isInterrupted) Thread.sleep(10)
            }
        },
    )

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "condition did not become true")
    }
}
