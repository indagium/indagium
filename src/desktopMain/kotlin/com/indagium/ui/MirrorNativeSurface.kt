package com.indagium.ui

import java.io.Closeable

/**
 * What the mirror lifecycle code needs from a native preview surface (the macOS Metal canvas or the
 * Windows/Linux GPU canvas), kept separate from the concrete AWT classes so the surface
 * publication/binding logic in [MirrorBackend.SharedRecordingSession] and [EmbeddedMirrorHandle]
 * can be exercised without a display or a native library.
 *
 * A surface is single-use: once [close]d it can never present again, so a decoder must never be
 * bound to a closed one (see `SharedRecordingSession.start`).
 */
internal interface MirrorNativeSurface : Closeable {
    /** Short name used in diagnostics, e.g. `videotoolbox-metal`. */
    val mode: String

    /** True once [close] ran (or was queued and completed). Never goes back to false. */
    val isClosed: Boolean

    /** True while the native presentation layer is attached to a window. A surface that Compose
     * never mounts stays false forever, which is the "decoder renders into the void" state. */
    val isPresentationAttached: Boolean

    /** One-line state dump for diagnostics. */
    fun describeAttachment(): String

    /** Compose popups over a heavyweight surface; only the Metal surface needs this. */
    fun setOverlayOccluded(occluded: Boolean) = Unit
}
