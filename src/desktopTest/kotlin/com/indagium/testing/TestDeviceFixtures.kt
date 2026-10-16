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
 * [png], uiautomator returns [uiDump], and finite read-only probes return fixture state. Device
 * mutations (input, monkey, am, settings writes) are recorded in [shellCommands] and succeed
 * unless [shellExitCode] says otherwise.
 */
internal class ScriptedAdbRunner(
    var png: ByteArray = fixturePng(),
    @Volatile var uiDump: String = fixtureUiDump(),
) : CaptureProcessRunner {
    val logcat = StreamingFakeProcess()

    /** The long-running `getevent -lt` stream a test feeds. */
    val getevent = StreamingFakeProcess()
    val specs = CopyOnWriteArrayList<CaptureProcessSpec>()
    val shellCommands = CopyOnWriteArrayList<List<String>>()

    @Volatile
    var shellExitCode = 0

    @Volatile
    var uiDumpExitCode = 0

    @Volatile
    var shellStdout = ""

    @Volatile
    var adbState = "device"

    @Volatile
    var bootId = "fixture-boot-1"

    @Volatile
    var bootUptimeMs = 10_000_000L

    /** Answers a shell command (the arguments after `shell`) with its own output and exit code; null falls back to [shellStdout]. */
    @Volatile
    var shellResponder: ((List<String>) -> CompletedFakeProcess?)? = null

    /** Optional blocking seam for proving a caller's coroutine cancellation interrupts screenshot capture. */
    @Volatile
    var beforeScreenshot: (() -> Unit)? = null

    /** Optional blocking seam for exercising cancellation and deadline bounds on boot identity probes. */
    @Volatile
    var beforeBootId: (() -> Unit)? = null

    override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
        specs += spec
        val command = spec.command
        return when {
            command.lastOrNull() == "get-state" -> CompletedFakeProcess(adbState, code = if (adbState == "device") 0 else 1)
            // `runAdb(logcat -g)` is a finite Device logging configuration read. Match it before
            // the streaming logcat branch so it cannot steal (and then close) the capture stream.
            "logcat" in command && command.lastOrNull() == "-g" -> CompletedFakeProcess(LOGCAT_BUFFER_SIZES)
            "logcat" in command -> logcat
            "screencap" in command -> {
                beforeScreenshot?.invoke()
                CompletedFakeProcess(png)
            }
            "uiautomator" in command -> CompletedFakeProcess(uiDump, code = uiDumpExitCode)
            "getevent" in command && "-lt" in command -> getevent
            else -> {
                val arguments = command.dropWhile { it != "shell" }.drop(1)
                if (arguments == listOf("cat", "/proc/sys/kernel/random/boot_id")) beforeBootId?.invoke()
                // These are readiness, uptime, or Device logging reads, not app-under-test shell
                // actions. Keep the mutation list stable for input and permission assertions while
                // preserving every command in [specs] for tests that inspect the adb protocol.
                if (!arguments.isReadOnlyFixtureProbe()) shellCommands += arguments
                shellResponder?.invoke(arguments) ?: defaultShellResponse(arguments)
            }
        }
    }

    private fun defaultShellResponse(arguments: List<String>): CompletedFakeProcess = when {
        arguments == listOf("getprop", "sys.boot_completed") -> CompletedFakeProcess("1")
        arguments == listOf("getprop", "ro.boot.boot_id") -> CompletedFakeProcess(bootId)
        arguments == listOf("getprop", "log.tag") -> CompletedFakeProcess("\n")
        arguments == listOf("getprop") -> CompletedFakeProcess("[log.tag.FOO]: [D]\n")
        arguments == listOf("cat", "/proc/sys/kernel/random/boot_id") -> CompletedFakeProcess(bootId)
        arguments == listOf("cat", "/proc/uptime") -> CompletedFakeProcess("${bootUptimeMs / MILLIS_PER_SECOND} 0.00\n")
        arguments == listOf("date", "-u", "+%m-%dT%H:%M:%S") -> CompletedFakeProcess("01-02T03:04:05\n")
        arguments == listOf("echo", "capture-ready") -> CompletedFakeProcess("capture-ready\n")
        arguments == listOf("id", "-u") -> CompletedFakeProcess("2000\n")
        else -> CompletedFakeProcess(shellStdout, code = shellExitCode)
    }

    private fun List<String>.isReadOnlyFixtureProbe(): Boolean = when {
        this == listOf("cat", "/proc/sys/kernel/random/boot_id") -> true
        this == listOf("cat", "/proc/uptime") -> true
        this == listOf("date", "-u", "+%m-%dT%H:%M:%S") -> true
        this == listOf("echo", "capture-ready") -> true
        this == listOf("id", "-u") -> true
        this == listOf("getprop") -> true
        this == listOf("getprop", "log.tag") -> true
        this == listOf("getprop", "sys.boot_completed") -> true
        this == listOf("getprop", "ro.boot.boot_id") -> true
        else -> false
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val LOGCAT_BUFFER_SIZES = """
            main: ring buffer is 256Kb
            system: ring buffer is 256Kb
            crash: ring buffer is 256Kb
        """
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
