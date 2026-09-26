package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.capture.AdbRootOutcome
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureCommandResult
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportPreview
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureRecorder
import com.indagium.capture.CaptureScreenshot
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSessionBoundary
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureToolResolver
import com.indagium.capture.CaptureToolValidation
import com.indagium.capture.CaptureTools
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.DeviceLogActivity
import com.indagium.capture.DeviceLogRetryableChange
import com.indagium.capture.DeviceLogState
import com.indagium.capture.ImportedCapture
import com.indagium.capture.LogBufferSizeChoice
import com.indagium.capture.LogTagLevel
import com.indagium.capture.classifyAdbRootOutput
import com.indagium.capture.isPermissionFailure
import com.indagium.capture.parseLogcatBufferSizes
import com.indagium.capture.perTagLogLevelOverrides
import com.indagium.model.Annotations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.time.Duration

internal data class CaptureToolResolution(
    val adbPath: String?,
    val scrcpyPath: String?,
)

/**
 * App-wide capture facilities. The service resolves tools, discovers devices and recovers the
 * session directory; it deliberately owns no recorder. A recorder belongs to one live tab via
 * [TabCaptureController], which keeps a stopped tab's state isolated from the next capture.
 *
 * Sessions live under two roots: [legacyRoot] (fixed — Application Support/Indagium/captures,
 * where every capture was recorded before save folders became configurable) and whatever
 * [sessionsRoot] resolves to right now (AppSettings.captureSessionsDir, or
 * AppSettings.saveRootDir/captures, or [legacyRoot] again when neither is set — see
 * AppState.effectiveCaptureSessionsDir). Listing/recovery/delete always scan both so a session
 * recorded before a user ever touched this setting stays visible and deletable; only
 * [newController] — called once, right as a capture starts — reads [sessionsRoot] fresh, so a
 * folder change takes effect from the next Start rather than moving anything already recording.
 */
@Suppress("TooGenericExceptionCaught")
internal class CaptureService(
    private val app: AppState,
    private val scope: CoroutineScope,
    private val legacyRoot: File,
    private val sessionsRoot: () -> File,
) : AutoCloseable {
    // Both roots when they differ, the one root when a fresh AppState resolves them to the same
    // place (e.g. no save folder ever configured — see AppState.effectiveCaptureSessionsDir's own
    // fallback to legacyRoot).
    private fun roots(): List<File> = listOf(legacyRoot, sessionsRoot()).distinctBy { it.absolutePath }

    private val resolver = CaptureToolResolver()
    private val discoveryLock = Mutex()
    private var tools: CaptureTools? = null
    private var resolvedSettings: CaptureSettings? = null

    var devices by mutableStateOf(emptyList<CaptureDevice>())
        private set
    var sessions by mutableStateOf(emptyList<CaptureSession>())
        private set
    var toolStatus by mutableStateOf<String?>(null)
        private set
    var toolResolution by mutableStateOf<CaptureToolResolution?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True while a [refreshDevices] call is in flight (either the launcher's own initial/polling
     *  refresh or an explicit "Recheck tools"/"Refresh devices" click) — see [refreshDevices]'s own
     *  doc for why this exists instead of blanking [devices]/[toolStatus] the way a naive "clear
     *  then reload" would. */
    var discovering by mutableStateOf(false)
        private set

    /** False only until the very first [refreshDevices] attempt has completed (success, failure or
     *  "adb unavailable") for this process. The Devices panel uses this to tell "haven't looked yet"
     *  (show a same-height "Looking for devices…" placeholder) apart from "looked, found none" (show
     *  the real "No devices discovered" empty state) — see [refreshDevices]'s own doc. */
    var hasCheckedDevicesOnce by mutableStateOf(false)
        private set

    // Device logging (New tab's "Device logging" panel): buffer sizes + log.tag/log.tag.<TAG>,
    // keyed by serial so it survives the launcher's 3s device-refresh poll and a device switch
    // without living in a composable `remember` (per the panel's own doc in CaptureLauncher.kt).
    // Deliberately a *replace-the-whole-map* mutableStateOf, matching every other per-tab map on
    // AppState, rather than a mutableStateMapOf — reads/writes here are always "one serial's state
    // changed", never a partial in-place mutation.
    var deviceLogStates by mutableStateOf(emptyMap<String, DeviceLogState>())
        private set

    /** Reads buffer sizes, the global level and per-tag overrides for [serial] off the caller's
     *  thread. Safe to call repeatedly (device switch, the panel's Refresh action) — each call reads
     *  a fresh snapshot and replaces whatever was there, so a stale in-flight read never overwrites
     *  a newer one out of order in practice (the panel only ever has one device selected at a time). */
    fun refreshDeviceLog(serial: String) {
        setDeviceLogState(serial) { (it ?: DeviceLogState(serial)).copy(activity = DeviceLogActivity.REFRESHING, error = null) }
        scope.launch {
            try {
                val tools = toolsForStart(app.settings.captureSettings)
                val state = runInterruptible { readDeviceLogState(tools, serial) }
                setDeviceLogState(serial) { state }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setDeviceLogState(serial) {
                    (it ?: DeviceLogState(serial)).copy(
                        activity = DeviceLogActivity.IDLE,
                        error = failure.message ?: "Could not read device logging state",
                    )
                }
            }
        }
    }

    /** Applies `-G` to every buffer at once (`-b all`) rather than one call per buffer — see this
     *  feature's own task doc: a single `logcat -b all -G <size>` call is exactly what "applies
     *  immediately, to all buffers" means here. Re-reads on success so the panel reflects what the
     *  device actually accepted (some devices clamp or round a requested size). */
    fun setDeviceLogBufferSize(serial: String, choice: LogBufferSizeChoice) {
        applyDeviceLogChange(serial, DeviceLogRetryableChange.BufferSize(choice), "Could not set buffer size")
    }

    /** Sets (or, for `level == null`, clears) the global `log.tag` filter. Clearing writes an empty
     *  value — `setprop log.tag ""` — rather than removing the property outright; `setprop` has no
     *  "unset" verb, and an empty value is exactly what `log.tag`'s own "device default" behavior
     *  reads as (see [LogTagLevel.fromPropValue]). */
    fun setDeviceGlobalLogLevel(serial: String, level: LogTagLevel?) {
        applyDeviceLogChange(serial, DeviceLogRetryableChange.GlobalLevel(level), "Could not set log level")
    }

    /** Removes one per-tag override, same "empty value" convention as [setDeviceGlobalLogLevel]. */
    fun clearDeviceLogTagOverride(serial: String, tag: String) {
        applyDeviceLogChange(serial, DeviceLogRetryableChange.ClearTagOverride(tag), "Could not clear the override for $tag")
    }

    /** Synchronous counterpart of [refreshDeviceLog] for the AI/MCP `get_device_log_settings` tool,
     *  which needs the state as a return value rather than a [deviceLogStates] publication to poll.
     *  Blocking (runs real adb calls) — callers must invoke it off the Compose thread; the AI tool
     *  path already runs on an IO-dispatcher coroutine. Deliberately does not touch [deviceLogStates]
     *  itself, so an in-flight AI request and the New tab panel's own controls stay independent. */
    internal fun readDeviceLogStateNow(serial: String): DeviceLogState =
        readDeviceLogState(toolsForStart(app.settings.captureSettings), serial)

    /** Synchronous counterpart of [setDeviceLogBufferSize]/[setDeviceGlobalLogLevel] for the AI/MCP
     *  `set_device_log_settings` tool: applies one change and returns the re-read state, throwing on
     *  failure instead of publishing to [deviceLogStates] — see [readDeviceLogStateNow]'s own doc for
     *  why the two stay separate. */
    internal fun applyDeviceLogChangeNow(serial: String, change: DeviceLogRetryableChange): DeviceLogState {
        val tools = toolsForStart(app.settings.captureSettings)
        val result = runRetryableChange(tools, serial, change)
        check(result.exitCode == 0) { adbFailureMessage("Could not apply device log setting", result) }
        return readDeviceLogState(tools, serial)
    }

    /** Runs the exact adb command one [DeviceLogRetryableChange] represents — the single place all
     *  three setters above (and [restartAdbAsRoot]'s own retry) build their command from, so a
     *  retry after "Restart adb as root" re-issues precisely the command that failed rather than a
     *  freshly-derived one. */
    private fun runRetryableChange(tools: CaptureTools, serial: String, change: DeviceLogRetryableChange): CaptureCommandResult =
        when (change) {
            is DeviceLogRetryableChange.BufferSize ->
                tools.runAdb(serial, listOf("logcat", "-b", "all", "-G", change.choice.logcatArg))
            is DeviceLogRetryableChange.GlobalLevel ->
                tools.runAdb(serial, listOf("shell", "setprop", "log.tag", change.level?.propValue ?: ADB_SHELL_EMPTY_VALUE))
            is DeviceLogRetryableChange.ClearTagOverride ->
                tools.runAdb(serial, listOf("shell", "setprop", "log.tag.${change.tag}", ADB_SHELL_EMPTY_VALUE))
        }

    private fun applyDeviceLogChange(serial: String, change: DeviceLogRetryableChange, failurePrefix: String) {
        setDeviceLogState(serial) {
            (it ?: DeviceLogState(serial)).copy(activity = DeviceLogActivity.APPLYING, error = null, failedChange = null)
        }
        scope.launch {
            try {
                val tools = toolsForStart(app.settings.captureSettings)
                val outcome = runInterruptible {
                    val result = runRetryableChange(tools, serial, change)
                    if (result.exitCode == 0) {
                        DeviceLogChangeOutcome.Applied(readDeviceLogState(tools, serial))
                    } else {
                        DeviceLogChangeOutcome.Failed(adbFailureMessage(failurePrefix, result), isPermissionFailure(result))
                    }
                }
                applyDeviceLogChangeOutcome(serial, change, outcome)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setDeviceLogState(serial) {
                    (it ?: DeviceLogState(serial)).copy(
                        activity = DeviceLogActivity.IDLE,
                        error = failure.message ?: failurePrefix,
                        failedChange = null,
                    )
                }
            }
        }
    }

    private fun applyDeviceLogChangeOutcome(serial: String, change: DeviceLogRetryableChange, outcome: DeviceLogChangeOutcome) {
        when (outcome) {
            is DeviceLogChangeOutcome.Applied -> setDeviceLogState(serial) { outcome.state }
            is DeviceLogChangeOutcome.Failed -> setDeviceLogState(serial) { current ->
                deviceLogFailedChangeState(current ?: DeviceLogState(serial), change, outcome.message, outcome.permissionFailure)
            }
        }
    }

    /**
     * "Restart adb as root" (device-logging panel's permission-failure recovery): runs
     * `adb -s <serial> root`, waits out adbd's restart when it actually bounces, retries the one
     * change [DeviceLogState.failedChange] recorded, then re-reads the panel exactly like any other
     * apply. A no-op if there is nothing recorded to retry (the button shouldn't be clickable in
     * that state, but this guards the call directly rather than trusting the caller). The panel
     * itself (CaptureLauncher.kt) is responsible for only showing the button when no capture is
     * live — restarting adbd kills any running logcat stream.
     */
    fun restartAdbAsRoot(serial: String) {
        val change = deviceLogStates[serial]?.failedChange ?: return
        setDeviceLogState(serial) { (it ?: DeviceLogState(serial)).copy(activity = DeviceLogActivity.ROOTING, error = null) }
        scope.launch {
            try {
                val tools = toolsForStart(app.settings.captureSettings)
                val state = runInterruptible { rootThenRetry(tools, serial, change) }
                setDeviceLogState(serial) { state }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setDeviceLogState(serial) { current ->
                    deviceLogRootAttemptFailedState(current ?: DeviceLogState(serial), failure.message ?: "Could not restart adb as root")
                }
            }
        }
    }

    /** The blocking body of [restartAdbAsRoot], run inside `runInterruptible` off the caller's
     *  thread. Always returns a [DeviceLogState] with [DeviceLogState.rootAttempted] set — a root
     *  attempt happened one way or another, whether it ended in success, a production-build refusal,
     *  or a retry that still failed — so the button never reappears for another automatic loop. */
    private fun rootThenRetry(tools: CaptureTools, serial: String, change: DeviceLogRetryableChange): DeviceLogState {
        val current = deviceLogStates[serial] ?: DeviceLogState(serial)
        val rootResult = tools.runAdb(serial, listOf("root"), timeout = ADB_ROOT_TIMEOUT)
        val rootOutput = "${rootResult.stdoutText()}\n${rootResult.stderrText()}"
        when (classifyAdbRootOutput(rootOutput)) {
            AdbRootOutcome.RESTARTING -> {
                tools.runAdb(serial, listOf("wait-for-device"), timeout = ADB_WAIT_FOR_DEVICE_TIMEOUT)
                waitForDeviceShell(tools, serial)
            }
            AdbRootOutcome.ALREADY_ROOT -> Unit
            AdbRootOutcome.PRODUCTION_REFUSAL, AdbRootOutcome.UNKNOWN -> {
                val message = rootOutput.trim().ifBlank { "adb root failed (exit ${rootResult.exitCode})" }
                return deviceLogRootAttemptFailedState(current, message)
            }
        }
        val retryResult = runRetryableChange(tools, serial, change)
        if (retryResult.exitCode != 0) {
            return deviceLogRootAttemptFailedState(current, adbFailureMessage("Could not apply the change", retryResult))
        }
        return readDeviceLogState(tools, serial).copy(rootAttempted = true)
    }

    /** Polls `adb -s <serial> shell id -u` after `wait-for-device` returns: the device is back on
     *  the adb transport at that point, but adbd itself can still take a moment to finish coming up
     *  as root, during which a `shell` command may fail outright. Bounded so a device that never
     *  fully comes back doesn't hang the recovery indefinitely — a poll that runs out simply falls
     *  through to the retry attempt anyway, whose own failure becomes the surfaced error. */
    private fun waitForDeviceShell(tools: CaptureTools, serial: String) {
        val deadline = System.nanoTime() + ADB_SHELL_POLL_TOTAL.toNanos()
        while (System.nanoTime() < deadline) {
            val probe = tools.runAdb(serial, listOf("shell", "id", "-u"), timeout = ADB_SHELL_POLL_TIMEOUT)
            if (probe.exitCode == 0 && probe.stdoutText().isNotBlank()) return
            Thread.sleep(ADB_SHELL_POLL_INTERVAL_MS)
        }
    }

    private fun setDeviceLogState(serial: String, transform: (DeviceLogState?) -> DeviceLogState) {
        deviceLogStates = deviceLogStates + (serial to transform(deviceLogStates[serial]))
    }

    private fun readDeviceLogState(tools: CaptureTools, serial: String): DeviceLogState {
        // Each of the three reads is checked individually (an unauthorized device or an SELinux
        // denial on just one of them must still surface as a readable inline error, not a silently
        // empty panel) rather than only checking apply-time commands.
        val sizesResult = tools.runAdb(serial, listOf("logcat", "-g"))
        check(sizesResult.exitCode == 0) { adbFailureMessage("Could not read buffer sizes", sizesResult) }
        val sizes = parseLogcatBufferSizes("${sizesResult.stdoutText()}\n${sizesResult.stderrText()}")
        val levelResult = tools.runAdb(serial, listOf("shell", "getprop", "log.tag"))
        check(levelResult.exitCode == 0) { adbFailureMessage("Could not read the global log level", levelResult) }
        val globalLevel = LogTagLevel.fromPropValue(levelResult.stdoutText())
        val propsResult = tools.runAdb(serial, listOf("shell", "getprop"))
        check(propsResult.exitCode == 0) { adbFailureMessage("Could not read per-tag overrides", propsResult) }
        val overrides = perTagLogLevelOverrides(propsResult.stdoutText())
        return DeviceLogState(
            serial = serial,
            bufferSizes = sizes,
            globalLevel = globalLevel,
            perTagOverrides = overrides,
            activity = DeviceLogActivity.IDLE,
            error = null,
            loaded = true,
        )
    }

    init {
        scope.launch {
            runCatching { recoverSessions() }
                .onFailure { error = it.message ?: "Capture sessions could not be recovered" }
        }
    }

    /**
     * Refreshes tool validation and device discovery without touching any live controller.
     *
     * Stale-while-revalidate (item 2 of the New-tab flicker fix): [devices]/[toolStatus]/[error] are
     * left exactly as they were while this runs, even for [force] — a forced re-resolution still
     * only invalidates the *internal* [tools]/[resolvedSettings] cache so [resolveTools] actually
     * re-probes, it does not blank the observable state the New tab is already showing. Every one of
     * those three fields is only ever replaced once the async read below has an actual new answer
     * (or a real failure), never pre-emptively — see [discovering] for the transient "a refresh is
     * running" signal a caller can show a busy indicator from instead, and [hasCheckedDevicesOnce]
     * for how the Devices panel tells "never looked yet" apart from "looked, found none".
     */
    fun refreshDevices(force: Boolean = false) {
        if (!discoveryLock.tryLock()) return
        discovering = true
        if (force) {
            tools = null
            resolvedSettings = null
        }
        scope.launch {
            try {
                val settings = app.settings.captureSettings
                val found = resolveTools(settings, force)
                if (!found.validateAdb().available) {
                    devices = emptyList()
                    return@launch
                }
                if (app.settings.captureSettings != settings) return@launch
                devices = runInterruptible { found.listDevices() }.map { it.device }
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Capture tools could not be checked"
            } finally {
                hasCheckedDevicesOnce = true
                discovering = false
                discoveryLock.unlock()
            }
        }
    }

    /** Resolves and validates adb for the synchronous start path. */
    fun toolsForStart(settings: CaptureSettings): CaptureTools = resolveTools(settings, force = false)

    /** Fresh synchronous device listing for AI/MCP callers that cannot depend on launcher polling. */
    fun discoverDevicesNow(): List<CaptureDevice> = toolsForStart(app.settings.captureSettings)
        .listDevices()
        .map { it.device }

    private fun resolveTools(settings: CaptureSettings, force: Boolean): CaptureTools {
        if (!force && tools != null && resolvedSettings == settings) return requireNotNull(tools)
        val found = resolver.resolve(settings)
        val adb = found.validateAdb()
        val scrcpy = runCatching { found.validateScrcpy() }.getOrElse { failure ->
            CaptureToolValidation(false, message = "scrcpy validation failed: ${failure.message}")
        }
        toolStatus = "${toolStatusLine(adb)}\n${toolStatusLine(scrcpy)}"
        tools = found
        resolvedSettings = settings
        if (app.settings.captureSettings == settings) {
            toolResolution = CaptureToolResolution(found.adb.path, found.scrcpy?.path)
        }
        check(adb.available) { adb.message }
        return found
    }

    // CaptureRecorder.listSessions() unconditionally mkdirs() the root it's given — fine for a
    // root that's already in use, but scanning a root that has never held a session would create
    // it right here, at recovery/listing time, purely from opening the home tab or Settings. Since
    // configurable save folders promise that a save folder is only ever created on first
    // real write, every recovery/listing call filters to roots that already exist first.
    private fun existingRoots(): List<File> = roots().filter(File::isDirectory)

    fun recoverSessions(): List<CaptureSession> {
        // Recovery is a filesystem concern. The service does not retain this short-lived helper;
        // live recorders remain owned exclusively by their TabCaptureController. Each root recovers
        // independently — an INTERRUPTED session under one root must not stop the other root's
        // sessions from being read.
        val recovered = mergeCaptureSessions(existingRoots().map { CaptureRecorder(it).use { r -> r.recoverSessions() } })
        sessions = recovered
        return recovered
    }

    fun listSessions(): List<CaptureSession> {
        val listed = mergeCaptureSessions(existingRoots().map { CaptureRecorder(it).use { r -> r.listSessions() } })
        sessions = listed
        return listed
    }

    fun retainedSession(sessionId: String): CaptureSession? =
        listSessions().firstOrNull { it.id == sessionId }

    // Addressed by the session's own directory (its parent is the root that actually contains it,
    // whichever of the two [roots] that turned out to be) rather than blindly trying [sessionsRoot]
    // — a legacy-root session must stay deletable even after the sessions folder setting changes.
    fun discardRetainedSession(sessionId: String): Boolean {
        val session = retainedSession(sessionId) ?: return false
        return CaptureRecorder(session.directory.parentFile).use { it.deleteSession(sessionId) }
    }

    fun exportRetainedSession(sessionId: String, destination: File, notes: Annotations? = null): CaptureExportResult {
        val session = requireNotNull(retainedSession(sessionId)) { "Capture session not found: $sessionId" }
        return CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(
                destination = destination,
                range = com.indagium.capture.CaptureRange.ALL,
                includeVideo = session.settings.recordVideo,
                cutoffElapsedMs = session.elapsedMs,
            ),
            notes = notes,
        )
    }

    // The one place that reads [sessionsRoot] for anything other than listing/recovery — called
    // once, synchronously, right as Start is pressed (AppState.startCaptureTab), so a folder
    // changed in Settings mid-recording never moves a session already in progress.
    fun newController(): TabCaptureController = TabCaptureController(sessionsRoot())

    fun browseAdbFromSettings() = browseTool(adb = true)

    fun browseScrcpyFromSettings() = browseTool(adb = false)

    fun recheckToolsFromSettings() = refreshDevices(force = true)

    internal fun toolResolutionFor(settings: CaptureSettings): CaptureToolResolution? =
        toolResolution.takeIf { resolvedSettings == settings }

    fun openInstallGuidanceFromSettings() {
        // adb (Android SDK Platform-Tools) is required for everything: logcat, the embedded
        // recording session and the in-app embedded mirror all stream the device over adb using a
        // bundled scrcpy server asset, not a host scrcpy install. A host scrcpy executable is only
        // needed for the separate "Open scrcpy mirror" native window.
        val message = "Install Android SDK Platform-Tools, then Recheck tools. " +
            "Enable USB debugging and accept the device authorization prompt. " +
            "scrcpy is only needed for the separate native mirror window, not for recording."
        error = message
        runCatching { Desktop.getDesktop().browse(URI("https://github.com/Genymobile/scrcpy#get-the-app")) }
    }

    internal fun updateSessions() {
        sessions = runCatching { listSessions() }.getOrElse { sessions }
    }

    internal fun reportError(message: String) {
        error = message
    }

    internal fun applyCalibration(sourcePath: String, additionalOffsetMs: Long) {
        scope.launch {
            try {
                runInterruptible {
                    val imported = CaptureArchiveReader.open(File(sourcePath), File(sessionsRoot(), "calibration-cache"))
                    val session = listSessions().firstOrNull { it.id == imported.descriptor.sessionId }
                        ?: return@runInterruptible
                    CaptureRecorder(session.directory.parentFile).use { recorder ->
                        recorder.setManualOffset(session.id, imported.descriptor.manualOffsetMs + additionalOffsetMs)
                    }
                    updateSessions()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Alignment could not be saved: ${failure.message}"
            }
        }
    }

    private fun browseTool(adb: Boolean) {
        val dialog = FileDialog(
            null as Frame?,
            if (adb) "Locate adb executable" else "Locate scrcpy executable",
            FileDialog.LOAD,
        )
        try {
            dialog.isVisible = true
            val name = dialog.file ?: return
            val file = File(dialog.directory, name).absolutePath
            app.updateSettings {
                it.copy(captureSettings = if (adb) {
                    it.captureSettings.copy(adbPath = file)
                } else {
                    it.captureSettings.copy(scrcpyPath = file)
                })
            }
            refreshDevices(force = true)
        } finally {
            dialog.dispose()
        }
    }

    override fun close() {
        // The service owns no active recorder. AppState stops controllers before shutdown; this
        // idempotent hook keeps that lifecycle explicit.
    }
}

/** One recorder and one exporter lane for one live capture tab. */
internal class TabCaptureController(
    root: File,
    videoExporter: CaptureVideoExporter = com.indagium.capture.FfmpegCaptureVideoExporter(),
    // Test seam, same pattern as videoExporter above: production always spawns real adb/scrcpy
    // subprocesses; tests substitute a fake runner (e.g. FakeCaptureRunner) so a capture session
    // can be driven end-to-end (e.g. for the embedded-mirror autostart race) without a real device.
    runner: com.indagium.capture.CaptureProcessRunner = com.indagium.capture.ProcessBuilderCaptureRunner(),
    // Test seam mirroring CaptureRecorder's own: lets a test drive the embedded recording session
    // (video/audio packets, reconnects) with a fake transport instead of a real device — used by
    // the embedded-mirror *session sharing* tests, which need a recording actually producing video
    // packets that a shared mirror decoder can attach to and observe.
    embeddedTransportFactory: ((com.indagium.capture.CaptureTools) -> com.indagium.capture.mirror.EmbeddedMirrorTransport)? = null,
) : AutoCloseable {
    private val recorder = if (embeddedTransportFactory != null) {
        CaptureRecorder(root, runner, embeddedTransportFactory = embeddedTransportFactory)
    } else {
        CaptureRecorder(root, runner)
    }

    // Only the real, production exporter waits for the growing MKV's muxer lag (see
    // CaptureArchiveExporter's videoCoverageWaitMs doc for why the class itself defaults it off).
    private val archiveExporter = CaptureArchiveExporter(
        videoExporter,
        videoCoverageWaitMs = com.indagium.capture.DEFAULT_VIDEO_COVERAGE_WAIT_MS,
    )

    val snapshot get() = recorder.snapshot
    val selectedSession get() = recorder.selectedSession

    fun start(
        device: CaptureDevice,
        settings: CaptureSettings,
        tools: CaptureTools,
        beforeLogcatLaunch: (CaptureSession) -> Unit,
    ): CaptureSession = recorder.start(device, settings, tools, beforeLogcatLaunch)

    fun stop(): CaptureSession? = recorder.stop()

    fun screenshot(): File = recorder.screenshot()

    fun screenshotCapture(): CaptureScreenshot = recorder.screenshotCapture()

    fun readScreen(): ByteArray = recorder.readScreen()

    fun supportsScreenshots(): Boolean = recorder.supportsScreenshots()

    /** Press-time capture-clock snapshot for AppState.markIssue (restyle plan Phase 3): the same
     *  flushed log/index byte bounds plus elapsedMs that [export]/[preview] already read via
     *  [CaptureRecorder.snapshotForExport] — see that function's own doc for why the boundary must
     *  come from there rather than reading `selectedSession.value` directly. */
    fun snapshotForExport(): CaptureSessionBoundary {
        val session = requireNotNull(selectedSession.value) { "Capture has no session to mark" }
        return recorder.snapshotForExport(session.id)
    }

    fun openMirror(): Boolean = recorder.openMirror()

    /** The recording's active embedded device session, if video recording is currently running —
     * see [CaptureRecorder.activeEmbeddedSession]. */
    fun activeEmbeddedSession(): com.indagium.capture.mirror.EmbeddedDeviceSession? = recorder.activeEmbeddedSession()

    /** Flow mirror of [activeEmbeddedSession] — see [CaptureRecorder.embeddedSessionFlow]. */
    val embeddedSessionFlow get() = recorder.embeddedSessionFlow

    /** Builds the durable descriptor/mapping beside the stopped recorder output. */
    fun finalizeStopped(session: CaptureSession): ImportedCapture = archiveExporter.finalizeSessionInPlace(session)

    /**
     * Exports a flushed point-in-time archive while leaving the recorder running. The recorder's
     * checkpoint advances only after the archive has been published successfully; exporter
     * failures and coroutine cancellation therefore cannot stop or mutate a live capture.
     */
    fun export(
        request: CaptureExportRequest,
        onWaitingForVideo: (() -> Unit)? = null,
        // Phase 4 (snapshot archive + import), appended last: threaded straight through to
        // CaptureArchiveExporter.export — see that parameter's own doc for why the tab's Notes must
        // arrive as a plain Annotations value rather than this controller reaching into AppState.
        notes: Annotations? = null,
    ): CaptureExportResult {
        val session = requireNotNull(selectedSession.value) { "Capture has no session to export" }
        val boundary = recorder.snapshotForExport(session.id)
        // Save is a point-in-time operation: ignore the preview's stale cutoff and take a fresh
        // recorder boundary immediately before staging the archive. Selection bounds remain
        // ordinal-based; cutoff only bounds the non-selection ranges.
        val boundedRequest = request.copy(cutoffElapsedMs = boundary.elapsedMs)
        val result = archiveExporter.export(boundary.session, boundedRequest, onWaitingForVideo, notes)
        recorder.updateSuccessfulExportCheckpoints(
            sessionId = boundary.session.id,
            logCheckpointMs = result.logCoveredEndMs,
            videoCheckpointMs = result.videoCoveredEndMs,
        )
        return result
    }

    /**
     * Previously read `session.indexFile`/`logFile` straight from [selectedSession] without
     * flushing the recorder's [java.io.BufferedOutputStream]s first — unlike [export], which
     * flushes+fsyncs via [CaptureRecorder.snapshotForExport]. In the steady state the recorder's
     * watchdog flushes every ~250ms anyway, but a preview opened right after a burst of writes
     * (or right after Stop) could see a shorter on-disk index than what was actually recorded.
     * Route through the same flushed boundary export uses so preview and export agree on what
     * "the current data" is.
     */
    fun preview(request: CaptureExportRequest): CaptureExportPreview {
        val session = requireNotNull(selectedSession.value) { "Capture has no session to preview" }
        val boundary = recorder.snapshotForExport(session.id)
        // Preview requests may be held by the popover for several recorder publication ticks.
        // Rebind the cutoff to this freshly flushed boundary so the bounded refresh observes a
        // growing capture even though the controls request itself is immutable.
        return archiveExporter.preview(boundary.session, request.copy(cutoffElapsedMs = boundary.elapsedMs))
    }

    override fun close() = recorder.close()
}

/**
 * Renders one line of tool status. A failed validation must show its failure message even when a
 * version string was captured before the failure (the old coordinator displayed a misleading
 * green version in that case).
 */
internal fun toolStatusLine(validation: CaptureToolValidation): String =
    if (validation.available) validation.version ?: validation.message else validation.message

/** `adb shell` joins its arguments into one command line for the device's shell, so a bare empty
 *  argument vanishes and `setprop log.tag` fails with a usage error; a quoted empty string survives. */
private const val ADB_SHELL_EMPTY_VALUE = "''"

/** Same "prefer stderr, bound the length" shape as CaptureTools.kt's own private `boundedDiagnostic`
 *  (not reused directly — that one is private to CaptureTools) — a device logging apply failure
 *  (unauthorized device, SELinux denying `setprop`) needs the same readable inline error. */
private const val MAX_ADB_FAILURE_MESSAGE_CHARS = 4_096

internal fun adbFailureMessage(prefix: String, result: CaptureCommandResult): String {
    val detail = (result.stderrText().ifBlank { result.stdoutText() }).trim().take(MAX_ADB_FAILURE_MESSAGE_CHARS)
    return if (detail.isEmpty()) "$prefix (exit ${result.exitCode})" else "$prefix: $detail"
}

/**
 * Pure merge of a failed device-log change into its previous [DeviceLogState] — pulled out of
 * [CaptureService.applyDeviceLogChange] for the same reason [toolStatusLine]/[adbFailureMessage]
 * were (see `CaptureCoordinatorToolStatusTest`'s own doc): `CaptureService` needs a live `AppState`
 * + `CoroutineScope` and isn't reasonably unit-testable end to end, but this decision has none of
 * that dependency. [change] is only ever recorded as [DeviceLogState.failedChange] — the trigger
 * for the "Restart adb as root" button — when [permissionFailure] is true AND this serial hasn't
 * already had a root attempt ([DeviceLogState.rootAttempted]); see that field's own doc for why a
 * prior attempt permanently suppresses the button instead of retrying in a loop.
 */
internal fun deviceLogFailedChangeState(
    base: DeviceLogState,
    change: DeviceLogRetryableChange,
    message: String,
    permissionFailure: Boolean,
): DeviceLogState = base.copy(
    activity = DeviceLogActivity.IDLE,
    error = message,
    failedChange = if (permissionFailure && !base.rootAttempted) change else null,
)

/**
 * Pure terminal state for a "Restart adb as root" attempt that didn't end in a successful,
 * fully-applied retry — a production-build refusal, an unrecognized `adb root` failure, or a retry
 * that still failed after root itself succeeded. All three get identical treatment: clear
 * [DeviceLogState.failedChange] and set [DeviceLogState.rootAttempted] so the button is never
 * offered again for this serial (see that field's own doc), surfacing [message] as the visible
 * error either way.
 */
internal fun deviceLogRootAttemptFailedState(base: DeviceLogState, message: String): DeviceLogState = base.copy(
    activity = DeviceLogActivity.IDLE,
    error = message,
    failedChange = null,
    rootAttempted = true,
)

/** [CaptureService.applyDeviceLogChange]'s own result type — a plain success/failure split so the
 *  blocking `runInterruptible` block can hand back whether the failure looked like a permission
 *  problem without resorting to throwing+parsing an exception message for it. */
private sealed class DeviceLogChangeOutcome {
    data class Applied(val state: DeviceLogState) : DeviceLogChangeOutcome()

    data class Failed(val message: String, val permissionFailure: Boolean) : DeviceLogChangeOutcome()
}

// "Restart adb as root" timing (CaptureService.restartAdbAsRoot/rootThenRetry/waitForDeviceShell):
// `adb root` itself is normally near-instant (it just asks the existing adbd to restart), 15s is
// purely a safety bound against a wedged adb server. wait-for-device's 20s covers a slow USB
// re-enumeration on some hosts/hubs. The shell poll is intentionally short (a "few seconds" per
// the task spec) since by the time wait-for-device returns the transport is already back — this is
// only waiting out adbd's own startup, not a full device reboot.
private val ADB_ROOT_TIMEOUT: Duration = Duration.ofSeconds(15)
private val ADB_WAIT_FOR_DEVICE_TIMEOUT: Duration = Duration.ofSeconds(20)
private val ADB_SHELL_POLL_TOTAL: Duration = Duration.ofSeconds(5)
private val ADB_SHELL_POLL_TIMEOUT: Duration = Duration.ofSeconds(2)
private const val ADB_SHELL_POLL_INTERVAL_MS = 300L

/**
 * Combines the listings CaptureService.listSessions/recoverSessions read from each capture root
 * (the legacy Application Support root and whatever the configurable sessions folder currently
 * resolves to) into the single list the launcher/strip show. A session id can only ever appear
 * under one root in practice (each root's directory names are its own session ids), but a plain
 * concat could still double-list one if a root were ever scanned twice — distinctBy is cheap
 * insurance for that, not an expected case. Pure and root-agnostic on purpose so it's testable
 * without touching disk.
 */
internal fun mergeCaptureSessions(perRootListings: List<List<CaptureSession>>): List<CaptureSession> =
    perRootListings.flatten().distinctBy(CaptureSession::id).sortedByDescending(CaptureSession::startedEpochMs)

internal enum class CaptureScreenshotAvailability { PENDING, ENABLED, DISABLED }

internal data class CaptureScreenshotCapability(
    val availability: CaptureScreenshotAvailability,
    val reason: String? = null,
)

/** What [AppState.undoMarkIssue] removes — the Note (always), the screenshot AnnBlock.Image and
 * its on-disk PNG (only when the screenshot setting was on and the capture succeeded before undo),
 * and the trailing LogRef (only when the postMs rescan finished before undo). Immutable and
 * replaced wholesale on every update (never mutated in place) so it stays a plain Compose
 * `mutableStateMapOf` value like every other per-tab map on AppState. */
internal data class MarkerUndoState(
    val noteId: String,
    val imageBlockId: String? = null,
    val logRefId: String? = null,
    val screenshotFile: File? = null,
)
