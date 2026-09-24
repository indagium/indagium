package com.indagium.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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

    @Test
    fun captureSnapshotPopupStaysBelowAndRightAlignedWhenThereIsRoom() {
        val provider = CaptureSnapshotPopupPositionProvider(marginPx = 8, gapPx = 8, placeAbove = false)

        val position = provider.calculatePosition(
            anchorBounds = IntRect(700, 100, 800, 140),
            windowSize = IntSize(1_000, 1_000),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(480, 600),
        )

        assertEquals(320, position.x)
        assertEquals(148, position.y)
    }

    @Test
    fun captureSnapshotPopupCanFlipAboveWithoutCoveringItsTrigger() {
        val provider = CaptureSnapshotPopupPositionProvider(marginPx = 8, gapPx = 8, placeAbove = true)

        val position = provider.calculatePosition(
            anchorBounds = IntRect(700, 800, 800, 840),
            windowSize = IntSize(1_000, 1_000),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(480, 600),
        )

        assertEquals(320, position.x)
        assertEquals(192, position.y)
        assertTrue(position.y + 600 <= 800 - 8)
    }

    @Test
    fun captureSnapshotPopupFitsAboveTriggerInShortWindow() {
        val margin = 8
        val gap = 8
        val anchor = IntRect(600, 80, 700, 100)
        val window = IntSize(900, 170)
        val popupHeight = 62
        val provider = CaptureSnapshotPopupPositionProvider(marginPx = margin, gapPx = gap, placeAbove = true)

        val position = provider.calculatePosition(
            anchorBounds = anchor,
            windowSize = window,
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(480, popupHeight),
        )

        assertEquals(220, position.x)
        assertEquals(margin + 2, position.y)
        assertTrue(position.y + popupHeight <= anchor.top - gap)
        assertTrue(position.y + popupHeight <= window.height - margin)
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
