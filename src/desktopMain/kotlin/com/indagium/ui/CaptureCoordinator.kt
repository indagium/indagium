package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.indagium.capture.DeviceLogState
import com.indagium.capture.ImportedCapture
import com.indagium.capture.LogBufferSizeChoice
import com.indagium.capture.LogTagLevel
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
        setDeviceLogState(serial) { (it ?: DeviceLogState(serial)).copy(busy = true, error = null) }
        scope.launch {
            try {
                val tools = toolsForStart(app.settings.captureSettings)
                val state = runInterruptible { readDeviceLogState(tools, serial) }
                setDeviceLogState(serial) { state }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setDeviceLogState(serial) {
                    (it ?: DeviceLogState(serial)).copy(busy = false, error = failure.message ?: "Could not read device logging state")
                }
            }
        }
    }

    /** Applies `-G` to every buffer at once (`-b all`) rather than one call per buffer — see this
     *  feature's own task doc: a single `logcat -b all -G <size>` call is exactly what "applies
     *  immediately, to all buffers" means here. Re-reads on success so the panel reflects what the
     *  device actually accepted (some devices clamp or round a requested size). */
    fun setDeviceLogBufferSize(serial: String, choice: LogBufferSizeChoice) {
        applyDeviceLogChange(serial, "Could not set buffer size") { tools ->
            tools.runAdb(serial, listOf("logcat", "-b", "all", "-G", choice.logcatArg))
        }
    }

    /** Sets (or, for `level == null`, clears) the global `log.tag` filter. Clearing writes an empty
     *  value — `setprop log.tag ""` — rather than removing the property outright; `setprop` has no
     *  "unset" verb, and an empty value is exactly what `log.tag`'s own "device default" behavior
     *  reads as (see [LogTagLevel.fromPropValue]). */
    fun setDeviceGlobalLogLevel(serial: String, level: LogTagLevel?) {
        applyDeviceLogChange(serial, "Could not set log level") { tools ->
            tools.runAdb(serial, listOf("shell", "setprop", "log.tag", level?.propValue ?: ADB_SHELL_EMPTY_VALUE))
        }
    }

    /** Removes one per-tag override, same "empty value" convention as [setDeviceGlobalLogLevel]. */
    fun clearDeviceLogTagOverride(serial: String, tag: String) {
        applyDeviceLogChange(serial, "Could not clear the override for $tag") { tools ->
            tools.runAdb(serial, listOf("shell", "setprop", "log.tag.$tag", ADB_SHELL_EMPTY_VALUE))
        }
    }

    private fun applyDeviceLogChange(serial: String, failurePrefix: String, command: (CaptureTools) -> CaptureCommandResult) {
        setDeviceLogState(serial) { (it ?: DeviceLogState(serial)).copy(busy = true, error = null) }
        scope.launch {
            try {
                val tools = toolsForStart(app.settings.captureSettings)
                val state = runInterruptible {
                    val result = command(tools)
                    check(result.exitCode == 0) { adbFailureMessage(failurePrefix, result) }
                    readDeviceLogState(tools, serial)
                }
                setDeviceLogState(serial) { state }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setDeviceLogState(serial) {
                    (it ?: DeviceLogState(serial)).copy(busy = false, error = failure.message ?: failurePrefix)
                }
            }
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
            busy = false,
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

    /** Refreshes tool validation and device discovery without touching any live controller. */
    fun refreshDevices(force: Boolean = false) {
        if (!discoveryLock.tryLock()) return
        if (force) {
            tools = null
            resolvedSettings = null
            toolResolution = null
            toolStatus = null
            error = null
            devices = emptyList()
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Capture tools could not be checked"
                devices = emptyList()
            } finally {
                discoveryLock.unlock()
            }
        }
    }

    /** Resolves and validates adb for the synchronous start path. */
    fun toolsForStart(settings: CaptureSettings): CaptureTools = resolveTools(settings, force = false)

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
