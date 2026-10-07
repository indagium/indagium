package com.indagium.testing

import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureProcessSpec
import com.indagium.capture.CaptureRecorder
import com.indagium.capture.CaptureTools
import com.indagium.capture.CompletedFakeProcess
import com.indagium.capture.RunningCaptureProcess
import com.indagium.capture.StreamingFakeProcess
import com.indagium.testing.device.TestDeviceSession
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory

// Synthetic device for the lane tests: an adb that answers by command line, no device and no real process.

internal const val FIXTURE_SERIAL = "SER-1"
internal const val FIXTURE_DEVICE_WIDTH = 1080
internal const val FIXTURE_DEVICE_HEIGHT = 2400
private const val FAST_WATCHDOG_MS = 20L

internal fun fixturePng(width: Int = FIXTURE_DEVICE_WIDTH, height: Int = FIXTURE_DEVICE_HEIGHT): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

internal fun fixtureUiDump(width: Int = FIXTURE_DEVICE_WIDTH, height: Int = FIXTURE_DEVICE_HEIGHT): String =
    """
    <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
    <hierarchy rotation="0">
      <node text="" class="android.widget.FrameLayout" clickable="false" enabled="true" bounds="[0,0][$width,$height]">
        <node text="Sign &amp; go" resource-id="com.example:id/ok" class="android.widget.Button" clickable="true" enabled="true" bounds="[100,200][300,400]" />
        <node text="" class="android.view.View" clickable="false" enabled="true" bounds="[0,500][1080,900]" />
        <node text="secret" resource-id="com.example:id/pw" class="android.widget.EditText" password="true" clickable="true" enabled="false"
          bounds="[100,1000][900,1100]" />
      </node>
    </hierarchy>
    UI hierchary dumped to: /dev/tty
    """.trimIndent()

/**
 * Answers adb commands by their content: logcat is a process the test feeds ([logcat]), screencap returns
 * [png], uiautomator returns [uiDump], everything else (input, monkey, am) is recorded in [shellCommands] and
 * succeeds unless [shellExitCode] says otherwise.
 */
internal class ScriptedAdbRunner(
    var png: ByteArray = fixturePng(),
    var uiDump: String = fixtureUiDump(),
) : CaptureProcessRunner {
    val logcat = StreamingFakeProcess()
    val specs = CopyOnWriteArrayList<CaptureProcessSpec>()
    val shellCommands = CopyOnWriteArrayList<List<String>>()

    @Volatile
    var shellExitCode = 0

    @Volatile
    var shellStdout = ""

    /** Optional blocking seam for proving a caller's coroutine cancellation interrupts screenshot capture. */
    @Volatile
    var beforeScreenshot: (() -> Unit)? = null

    override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
        specs += spec
        val command = spec.command
        return when {
            "logcat" in command -> logcat
            "screencap" in command -> {
                beforeScreenshot?.invoke()
                CompletedFakeProcess(png)
            }
            "uiautomator" in command -> CompletedFakeProcess(uiDump)
            else -> {
                shellCommands += command.dropWhile { it != "shell" }.drop(1)
                CompletedFakeProcess(shellStdout, code = shellExitCode)
            }
        }
    }
}

internal fun fixtureTools(runner: CaptureProcessRunner): CaptureTools = CaptureTools(CaptureExecutable("adb"), null, runner)

internal suspend fun openFixtureSession(
    runner: ScriptedAdbRunner,
    laneDir: File = createTempDirectory("indagium-lane").toFile(),
    isLiveCaptureSerial: (String) -> Boolean = { false },
): TestDeviceSession = TestDeviceSession.open(
    serial = FIXTURE_SERIAL,
    laneDir = laneDir,
    tools = fixtureTools(runner),
    runner = runner,
    isLiveCaptureSerial = isLiveCaptureSerial,
    recorderFactory = { root, processRunner -> CaptureRecorder(root, processRunner, watchdogIntervalMs = FAST_WATCHDOG_MS) },
)

internal fun logRow(message: String, tag: String = "App", level: Char = 'I', seconds: Int = 5): String =
    "01-02 03:04:%02d.006  100  101 %c %s: %s\n".format(seconds, level, tag, message)
