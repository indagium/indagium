@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.RecordingScreenState
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.device.UiNode
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestSuite
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

// A synthetic recording for the rewrite tests: a shop app where the person taps "Search", types a query and presses Enter. The
// start probe (before input 1) and the probe after the last input carry distinct image bytes so a test can tell which one a row got.

internal const val SHOP_SUITE_ID = "suite-1"
internal const val SHOP_CASE_ID = "case-1"
internal const val SHOP_PACKAGE = "com.example.shop"
internal val SHOP_BEFORE_IMAGE = byteArrayOf(-1, -40, 1, 1, 1)
internal val SHOP_AFTER_IMAGE = byteArrayOf(-1, -40, 2, 2, 2)

internal fun shopLibrary(readOnly: Boolean = false) = TestLibrary(
    suites = listOf(
        TestSuite(
            SHOP_SUITE_ID, "Shop suite", instructions = "Use the staging account.", targetPackage = SHOP_PACKAGE, readOnly = readOnly,
            cases = listOf(TestCase(SHOP_CASE_ID, "Search for music", description = "Find a playlist from the search field.")),
        ),
    ),
)

internal fun shopNode(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    text: String = "",
    resourceId: String = "",
    className: String = "android.widget.TextView",
    password: Boolean = false,
    focused: Boolean = false,
) = UiNode(left, top, right, bottom, text, "", resourceId, className, true, true, false, SHOP_PACKAGE, password, focused)

internal fun shopState(
    start: Long,
    finish: Long,
    activity: String,
    image: ByteArray?,
    vararg nodes: UiNode,
) = RecordingScreenState(0, start, finish, SHOP_PACKAGE, activity, nodes.toList(), 1000, 2000, image)

/** Hands out [states] in call order (the last repeats), so the start probe is the first and the probe after stopping the second. */
internal class ScriptedRecordingProbe(private val states: List<RecordingScreenState?>) {
    private val calls = AtomicInteger()

    fun read(): RecordingScreenState? = states[minOf(calls.getAndIncrement(), states.lastIndex)]
}

private fun touch(action: MirrorTouchAction, x: Int, y: Int) =
    MirrorControlCommand.Touch(action = action, pointerId = 1, x = x, y = y, screenWidth = 100, screenHeight = 200)

private fun awaitState(session: TestStepRecordingSession, seq: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (session.screenState(seq) == null && System.nanoTime() < deadline) Thread.sleep(5)
    assertNotNull(session.screenState(seq), "probe state $seq never arrived")
}

/**
 * A stopped, drained recording of three inputs: a tap on "Search", the text [typed] and Enter. When [passwordField] is set the
 * screen shows a focused password field and the text comes first, so it is hidden. Input 1 has a before screen, input 3 an after screen.
 */
internal fun recordedShopSession(typed: String = "lofi", passwordField: Boolean = false): TestStepRecordingSession {
    val field = shopNode(
        100, 400, 900, 500, resourceId = "$SHOP_PACKAGE:id/query", className = "android.widget.EditText", password = passwordField, focused = true,
    )
    val search = shopNode(500, 550, 700, 700, text = "Search", resourceId = "$SHOP_PACKAGE:id/search_button", className = "android.widget.Button")
    val results = if (passwordField) "Signed in" else "Results for $typed"
    val probe = ScriptedRecordingProbe(
        listOf(
            shopState(0, 50, ".HomeActivity", SHOP_BEFORE_IMAGE, search, field),
            shopState(10_000, 10_100, ".ResultsActivity", SHOP_AFTER_IMAGE, shopNode(0, 100, 900, 200, text = results)),
        ),
    )
    val session = TestStepRecordingSession("fixture-device", screenProbe = probe::read, settleDelayMs = 60_000L, probeDrainTimeoutMs = 5_000L)
    awaitState(session, 1)
    // Only the start probe is a "before" state, so a hidden password has to be typed first for the recorder to see its field.
    if (passwordField) session.accept(MirrorControlCommand.Text(typed), nowMs = 1_000)
    session.accept(touch(MirrorTouchAction.DOWN, 54, 62), nowMs = 1_500)
    session.accept(touch(MirrorTouchAction.UP, 54, 62), nowMs = 1_540)
    if (!passwordField) session.accept(MirrorControlCommand.Text(typed), nowMs = 2_000)
    session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 66), nowMs = 3_000)
    runBlocking { session.stopAndDrain() }
    return session
}

/** The answer of a well-behaved AI for [recordedShopSession]: inputs 1 and 2 merged into one step, input 3 on its own. */
internal const val VALID_SHOP_REWRITE = """{"steps":[
    {"action":"Search for 'lofi'","expected":"The search field shows 'lofi'","sourceInputs":[1,2]},
    {"action":"Submit the search","expected":"Results for 'lofi' are listed","sourceInputs":[3],"expectedScreenshot":"after-of-input-3"}
],"notes":"Merged the tap on Search with the typing."}"""
