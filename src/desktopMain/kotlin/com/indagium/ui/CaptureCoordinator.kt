package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportPreview
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureExportResult
import com.indagium.capture.CaptureRecorder
import com.indagium.capture.CaptureScreenshot
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureToolResolver
import com.indagium.capture.CaptureToolValidation
import com.indagium.capture.CaptureTools
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.ImportedCapture
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
 */
@Suppress("TooGenericExceptionCaught")
internal class CaptureService(
    private val app: AppState,
    private val scope: CoroutineScope,
    private val root: File,
) : AutoCloseable {
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

    fun recoverSessions(): List<CaptureSession> {
        // Recovery is a filesystem concern. The service does not retain this short-lived helper;
        // live recorders remain owned exclusively by their TabCaptureController.
        val recovered = CaptureRecorder(root).use { it.recoverSessions() }
        sessions = recovered
        return recovered
    }

    fun listSessions(): List<CaptureSession> {
        val listed = CaptureRecorder(root).use { it.listSessions() }
        sessions = listed
        return listed
    }

    fun retainedSession(sessionId: String): CaptureSession? =
        listSessions().firstOrNull { it.id == sessionId }

    fun discardRetainedSession(sessionId: String): Boolean =
        CaptureRecorder(root).use { it.deleteSession(sessionId) }

    fun exportRetainedSession(sessionId: String, destination: File): CaptureExportResult {
        val session = requireNotNull(retainedSession(sessionId)) { "Capture session not found: $sessionId" }
        return CaptureArchiveExporter().export(
            session,
            CaptureExportRequest(
                destination = destination,
                range = com.indagium.capture.CaptureRange.ALL,
                includeVideo = session.settings.recordVideo,
                cutoffElapsedMs = session.elapsedMs,
            ),
        )
    }

    fun newController(): TabCaptureController = TabCaptureController(root)

    fun browseAdbFromSettings() = browseTool(adb = true)

    fun browseScrcpyFromSettings() = browseTool(adb = false)

    fun recheckToolsFromSettings() = refreshDevices(force = true)

    internal fun toolResolutionFor(settings: CaptureSettings): CaptureToolResolution? =
        toolResolution.takeIf { resolvedSettings == settings }

    fun openInstallGuidanceFromSettings() {
        val message = "Install Android SDK Platform-Tools and scrcpy for your OS, then Recheck tools. " +
            "Enable USB debugging and accept the device authorization prompt."
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
                    val imported = CaptureArchiveReader.open(File(sourcePath), File(root, "calibration-cache"))
                    val session = listSessions().firstOrNull { it.id == imported.descriptor.sessionId }
                        ?: return@runInterruptible
                    CaptureRecorder(root).use { recorder ->
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
) : AutoCloseable {
    private val recorder = CaptureRecorder(root)
    private val archiveExporter = CaptureArchiveExporter(videoExporter)

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

    fun openMirror(): Boolean = recorder.openMirror()

    /** Builds the durable descriptor/mapping beside the stopped recorder output. */
    fun finalizeStopped(session: CaptureSession): ImportedCapture = archiveExporter.finalizeSessionInPlace(session)

    /**
     * Exports a flushed point-in-time archive while leaving the recorder running. The recorder's
     * checkpoint advances only after the archive has been published successfully; exporter
     * failures and coroutine cancellation therefore cannot stop or mutate a live capture.
     */
    fun export(request: CaptureExportRequest): CaptureExportResult {
        val session = requireNotNull(selectedSession.value) { "Capture has no session to export" }
        val boundary = recorder.snapshotForExport(session.id)
        val boundedRequest = request.copy(cutoffElapsedMs = minOf(request.cutoffElapsedMs, boundary.elapsedMs))
        val result = archiveExporter.export(boundary.session, boundedRequest)
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
        val cutoff = minOf(request.cutoffElapsedMs, boundary.elapsedMs)
        return archiveExporter.preview(boundary.session, request.copy(cutoffElapsedMs = cutoff))
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

internal enum class CaptureScreenshotAvailability { PENDING, ENABLED, DISABLED }

internal data class CaptureScreenshotCapability(
    val availability: CaptureScreenshotAvailability,
    val reason: String? = null,
)
