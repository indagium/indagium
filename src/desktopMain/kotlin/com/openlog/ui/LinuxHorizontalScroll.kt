package com.openlog.ui

// AWT has no horizontal wheel axis (javap java.awt.event.MouseWheelEvent confirms this — there is
// no getWheelRotationX() or equivalent), and Compose Desktop's scroll→Offset conversion is a
// single branch on MouseWheelEvent.isShiftDown(). On Linux/X11, a touchpad's horizontal swipe is
// instead delivered as core pointer buttons 6 (left) / 7 (right) — extra-button MOUSE_PRESSED
// events XToolkit never converts to a wheel event, so this bridges them by hand.
//
// The Java button *numbers* those X buttons surface as are UNVERIFIED from this machine (no X11
// available) — XToolkit skips wheel buttons 4/5 when numbering extra buttons, so X 6/7 most likely
// land as Java 4/5, but X 8/9 (mouse back/forward) land as Java 6/7, and the true mapping can only
// be read off with OPENLOG_DEBUG_INPUT=1 on a real Linux desktop (see Main.kt). Keep the numbers —
// and which one is "left" vs "right" — in this one array so a wrong guess is a one-line fix.
private val HSCROLL_BUTTONS = intArrayOf(4, 5) // [left, right] java.awt.event.MouseEvent.getButton()

/**
 * Maps a raw AWT extra-button number to a horizontal scroll delta in pixels, or null for any
 * button this bridge doesn't handle (including ordinary buttons 1-3) — callers treat null as
 * "not our event, ignore it," making the bridge a safe no-op if [HSCROLL_BUTTONS] never fires.
 * Pure and UI/AWT-free so it's directly unit-testable.
 *
 * Sign is also unverified: this assumes "left" scrolls the view left (negative delta, same
 * direction as ScrollState.dispatchRawDelta) and "right" scrolls it right (positive). Flip the
 * signs here if OPENLOG_DEBUG_INPUT observation shows it backwards.
 */
fun horizontalScrollDelta(button: Int, stepPx: Float): Float? = when (button) {
    HSCROLL_BUTTONS[0] -> -stepPx
    HSCROLL_BUTTONS[1] -> stepPx
    else -> null
}
