package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.capture.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.util.UUID

/** Owns capture services, keeping device IO and exports away from the Compose thread. */
@Suppress("MagicNumber", "TooGenericExceptionCaught")
internal class CaptureCoordinator(
    private val app: AppState,
    private val scope: CoroutineScope,
    private val root: File,
) : AutoCloseable {
    private val recorder = CaptureRecorder(root)
    private val exporter = CaptureArchiveExporter()
    private val resolver = CaptureToolResolver()
    private val discoveryLock = Mutex()
    private var tools: CaptureTools? = null
    private var resolvedSettings: CaptureSettings? = null
    private var exportJob: Job? = null
    private var snapshot by mutableStateOf(RecorderSnapshot())
    private var sessions by mutableStateOf(emptyList<CaptureSession>())
    private var selectedSession by mutableStateOf<CaptureSession?>(null)
    private var devices by mutableStateOf(emptyList<CaptureDevice>())
    private var selectedSerial by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var activeOperations = 0
    private var error by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var toolsStatus by mutableStateOf<String?>(null)
    private var latestVideoCoverage by mutableStateOf<String?>(null)
    private var saveDirectory by mutableStateOf(app.settings.defaultSaveDir?.let(::File))
    private var clockTick by mutableStateOf(0L)
    private var previewRows by mutableStateOf(emptyList<String>())
    private var stopJob: Job? = null

    init {
        scope.launch {
            runCatching { recorder.recoverSessions() }.onSuccess { sessions = it }
                .onFailure { error = it.message }
            recorder.selectedSession.collect { selectedSession = it }
        }
        scope.launch {
            recorder.snapshot.collect { next ->
                snapshot = next
                previewRows = next.preview.map(CapturePreviewLine::text)
                next.session?.let { updated ->
                    sessions = sessions.map { current ->
                        if (current.id == updated.id) updated else current
                    }
                }
            }
        }
    }

    val state: CaptureWorkspaceState
        get() {
            val session = selectedSession
            val active = snapshot.session
            val recording = active != null &&
                (snapshot.state == RecorderState.RECORDING || snapshot.state == RecorderState.STOPPING)
            val selectedIsActive = session?.id == active?.id
            val elapsed = when {
                selectedIsActive -> active?.elapsedMs ?: session?.elapsedMs ?: 0L
                else -> session?.elapsedMs ?: 0L
            }
            return CaptureWorkspaceState(
                settings = app.settings.captureSettings,
                devices = devices,
                selectedDeviceSerial = selectedSerial,
                sessions = sessions,
                selectedSessionId = session?.id,
                activeSessionId = active?.id?.takeIf { recording },
                isRecording = recording,
                isBusy = busy,
                previewRows = if (selectedIsActive) previewRows else emptyList(),
                latestReadableTimestamp = latestVideoCoverage,
                elapsedText = formatElapsed(maxOf(elapsed, clockTick.takeIf { recording && selectedIsActive } ?: 0)),
                storageText = "${snapshot.logBytes / (1024 * 1024)} MiB logs",
                videoStatus = when {
                    session == null || !session.settings.recordVideo -> "Off"
                    snapshot.videoRecording && selectedIsActive -> "Recording (estimated alignment)"
                    session.videoStartElapsedMs != null -> "Recorded / stopped"
                    else -> "Unavailable — see diagnostics"
                },
                diagnostics = snapshot.diagnostics,
                error = error,
                status = status,
                toolsStatus = toolsStatus,
                saveDirectory = saveDirectory,
                templatePreview = session?.let { runCatching { filename(it, CaptureRange.ALL) }.getOrNull() },
                manualOffsetMs = session?.manualOffsetMs ?: 0,
            )
        }

    val actions: CaptureWorkspaceActions
        get() = CaptureWorkspaceActions(
            onSettingsChanged = { next -> app.updateSettings { it.copy(captureSettings = next) } },
            onBrowseAdb = { browseTool(adb = true) },
            onBrowseScrcpy = { browseTool(adb = false) },
            onRecheckTools = { refreshDevices(force = true) },
            onOpenInstallGuide = ::openInstallGuides,
            onDeviceSelected = { selectedSerial = it.serial },
            onRefreshDevices = { refreshDevices() },
            onSelectSession = { id -> perform { recorder.selectSession(id); latestVideoCoverage = null } },
            onStart = ::start,
            onStop = ::stop,
            onSave = { request -> save(request, openAfter = false) },
            onChooseSaveDirectory = {
                app.pickDirectory("Save capture archives", saveDirectory)?.let { saveDirectory = it }
            },
            onScreenshot = { perform { status = "Screenshot saved: ${recorder.screenshot().name}" } },
            onOpenSnapshot = { save(null, openAfter = true) },
            onDeleteSession = { id -> perform {
                check(recorder.deleteSession(id)) { "Session could not be deleted" }
                sessions = recorder.listSessions()
            } },
            onManualOffsetChanged = { offset -> selectedSession?.let { session ->
                perform { recorder.setManualOffset(session.id, offset) }
            } },
            onCancelExport = { exportJob?.cancel() },
            onClose = { app.captureWorkspaceOpen = false },
        )

    /** Called while the workspace is visible; a mutex prevents overlapping adb discovery. */
    fun refreshDevices(force: Boolean = false) {
        if (!discoveryLock.tryLock()) return
        if (force) {
            tools = null
            resolvedSettings = null
            devices = emptyList()
        }
        scope.launch {
            try {
                val settings = app.settings.captureSettings
                val found: CaptureTools
                if (force || tools == null || resolvedSettings != settings) {
                    found = runInterruptible { resolver.resolve(settings) }
                    val adb = runInterruptible { found.validateAdb() }
                    val scrcpy = runInterruptible { found.validateScrcpy() }
                    toolsStatus = "${toolStatusLine(adb)}\n${toolStatusLine(scrcpy)}"
                    if (!adb.available) {
                        devices = emptyList()
                        tools = found
                        resolvedSettings = settings
                        return@launch
                    }
                    if (app.settings.captureSettings != settings) return@launch
                    tools = found
                    resolvedSettings = settings
                } else {
                    found = requireNotNull(tools)
                }
                val discovered = runInterruptible { found.listDevices() }
                devices = discovered.map { it.device }
                if (selectedSerial !in devices.filter(CaptureDevice::available).map(CaptureDevice::serial)) {
                    selectedSerial = devices.singleOrNull(CaptureDevice::available)?.serial
                }
                val active = snapshot.session
                if (active != null && snapshot.state == RecorderState.RECORDING) {
                    clockTick = runInterruptible { recorder.snapshotForExport(active.id).elapsedMs }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                toolsStatus = failure.message
                devices = emptyList()
                tools = null
                resolvedSettings = null
            } finally {
                discoveryLock.unlock()
            }
        }
    }

    private fun start() = perform {
        val settings = app.settings.captureSettings
        val device = devices.firstOrNull { it.serial == selectedSerial && it.available }
            ?: error("Select an available device first")
        val found = resolver.resolve(settings)
        val validation = found.validateAdb()
        check(validation.available) { validation.message }
        recorder.start(device, settings, found)
        latestVideoCoverage = null
        status = "Capture started. Raw logs are retained independently of the preview."
        sessions = recorder.listSessions()
    }

    private fun perform(action: () -> Unit) {
        if (activeOperations > 0) return
        beginOperation()
        error = null
        scope.launch {
            try {
                runInterruptible { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Capture operation failed"
            } finally {
                endOperation()
            }
        }
    }

    private fun stop() {
        if (stopJob?.isActive == true) return
        beginOperation()
        error = null
        stopJob = scope.launch {
            try {
                runInterruptible { recorder.stop() }
                sessions = recorder.listSessions()
                status = "Capture stopped."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Capture stop failed"
            } finally {
                endOperation()
                stopJob = null
            }
        }
    }

    private fun beginOperation() {
        activeOperations += 1
        busy = true
    }

    private fun endOperation() {
        activeOperations = (activeOperations - 1).coerceAtLeast(0)
        busy = activeOperations > 0
    }

    private fun save(request: CaptureExportRequest?, openAfter: Boolean) {
        if (busy) return
        val selected = selectedSession ?: return
        if (!openAfter && saveDirectory == null) {
            saveDirectory = app.pickDirectory("Save capture archive", null) ?: return
        }
        beginOperation()
        error = null
        status = "Preparing capture export…"
        exportJob = scope.launch {
            try {
                val boundary = runInterruptible { recorder.snapshotForExport(selected.id) }
                val session = boundary.session
                val range = request?.range ?: CaptureRange.ALL
                val folder = if (openAfter) File(root, "snapshots").also { it.mkdirs() } else requireNotNull(saveDirectory)
                val filename = if (openAfter) {
                    "snapshot-${UUID.randomUUID()}.zip"
                } else {
                    filename(session, range)
                }
                val destination = availableDestination(folder, filename)
                val actual = CaptureExportRequest(destination, range, request?.customMinutes ?: 5,
                    request?.includeVideo ?: session.settings.recordVideo, boundary.elapsedMs)
                val result = runInterruptible { exporter.export(session, actual) }
                ensureActive()
                if (!openAfter) recorder.updateSuccessfulExportCheckpoints(session.id, result.logCoveredEndMs, result.videoCoveredEndMs)
                latestVideoCoverage = result.videoCoveredEndMs?.let(::formatElapsed)
                status = "Saved ${result.file.name}. ${result.message}"
                sessions = recorder.listSessions()
                if (openAfter) app.openCaptureFile(result.file)
            } catch (cancelled: CancellationException) {
                status = "Export cancelled. Capture continues."
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Capture export failed"
                status = null
            } finally {
                endOperation()
                exportJob = null
            }
        }
    }

    private fun browseTool(adb: Boolean) {
        val dialog = FileDialog(null as Frame?, if (adb) "Locate adb executable" else "Locate scrcpy executable", FileDialog.LOAD)
        try {
            dialog.isVisible = true
            val name = dialog.file ?: return
            val file = File(dialog.directory, name).absolutePath
            app.updateSettings {
                it.copy(
                    captureSettings = if (adb) {
                        it.captureSettings.copy(adbPath = file)
                    } else {
                        it.captureSettings.copy(scrcpyPath = file)
                    },
                )
            }
            refreshDevices(force = true)
        } finally {
            dialog.dispose()
        }
    }

    private fun openInstallGuides() {
        status = "Install Android SDK Platform-Tools and scrcpy for your OS, then Recheck tools. " +
            "On Flatpak, install these on the host. Enable USB debugging and accept the device authorization prompt."
        runCatching { Desktop.getDesktop().browse(URI("https://github.com/Genymobile/scrcpy#get-the-app")) }
            .onFailure { error = "Installation guide: https://github.com/Genymobile/scrcpy#get-the-app" }
    }

    override fun close() {
        exportJob?.cancel()
        recorder.close()
    }

    fun applyCalibration(sourcePath: String, additionalOffsetMs: Long) {
        scope.launch {
            try {
                runInterruptible {
                    val imported = CaptureArchiveReader.open(File(sourcePath), File(root, "calibration-cache"))
                    val session = recorder.listSessions().firstOrNull { it.id == imported.descriptor.sessionId } ?: return@runInterruptible
                    recorder.setManualOffset(session.id, imported.descriptor.manualOffsetMs + additionalOffsetMs)
                    sessions = recorder.listSessions()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Alignment was saved on the tab, but the retained capture could not be updated: ${failure.message}"
            }
        }
    }

    private fun filename(session: CaptureSession, range: CaptureRange): String = renderCaptureFilename(
        session.settings.filenameTemplate, session.device, session.startedEpochMs, range,
        session.exportCounter + 1, session.settings.label,
    )
}

/**
 * Renders one line of `toolsStatus` for a single tool's validation result (B2 fix). Previously
 * this was inlined as `adb.version ?: adb.message`: since a version check failing AFTER the
 * version string was already captured still leaves `version` non-null, that expression showed a
 * green-looking version line and silently hid the real failure reason -- the user just saw "No
 * devices discovered" with no clue why. `message` must always be visible when `available` is
 * false, regardless of whether `version` is set.
 */
internal fun toolStatusLine(validation: CaptureToolValidation): String =
    if (validation.available) validation.version ?: validation.message else validation.message

private fun availableDestination(directory: File, suggested: String): File {
    var target = File(directory, suggested)
    var counter = 1
    while (target.exists()) target = File(directory, "${suggested.removeSuffix(".zip")}-${counter++}.zip")
    return target
}

private fun formatElapsed(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / MILLIS_PER_SECOND
    return "%02d:%02d:%02d".format(
        seconds / SECONDS_PER_HOUR,
        seconds / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE,
        seconds % SECONDS_PER_MINUTE,
    )
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
