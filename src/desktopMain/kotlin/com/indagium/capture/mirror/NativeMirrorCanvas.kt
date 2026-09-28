package com.indagium.capture.mirror

import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics

/**
 * The AWT [Canvas] backing the Windows D3D11 / Linux VAAPI+EGL direct-decode mirror surface (see
 * `ui/EmbeddedMirrorGpuSurface.kt`, [WindowsD3D11MirrorNative], [LinuxVaapiEglMirrorNative]).
 *
 * AWT/Java2D otherwise erases and repaints this same HWND/X window on every repaint, competing
 * with the D3D11 swapchain / EGL surface presenting into it directly — `ignoreRepaint` plus no-op
 * [paint]/[update] are the standard fix for a natively rendered AWT Canvas.
 *
 * [generation] is bumped whenever this Canvas's platform window is (re)created ([addNotify]) or
 * torn down ([removeNotify]) — e.g. across a SwingPanel unmount/remount when a tab is hidden,
 * detached, or reconnected. [WindowsD3D11MirrorNative]'s native code caches the HWND it resolves
 * through JAWT and only re-resolves it when this counter has moved, instead of on every frame.
 */
internal class NativeMirrorCanvas : Canvas() {
    @Volatile
    var generation: Int = 0
        private set

    init {
        background = Color.BLACK
        isFocusable = true
        ignoreRepaint = true
    }

    override fun addNotify() {
        super.addNotify()
        generation++
    }

    override fun removeNotify() {
        generation++
        super.removeNotify()
    }

    override fun paint(g: Graphics?) = Unit

    override fun update(g: Graphics?) = Unit
}
