package com.indagium.ui

import com.indagium.capture.CAPTURE_DESCRIPTOR_NAME
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureStatus
import com.indagium.capture.readCaptureSessionDirectory
import java.io.File

/**
 * The capture session that [logFile] (a restored tab's source file) is the recorded log of, when
 * that session is over but was never finalized — i.e. the app was quit (or died) while recording, so
 * the recorder stopped it (`STOPPED`/`INTERRUPTED`) but nothing built its `capture.indagium.json`
 * or attached its video to the tab. Null for every other file:
 *  - anything that is not `<session>/logs/logcat.log` of a readable session directory;
 *  - a session still marked RECORDING (the recorder has not stopped it; startup recovery owns it);
 *  - a session that already has a descriptor — finalized once, so whether its video is attached to
 *    a tab is the user's business (they may have detached it), never something to redo silently.
 */
internal fun unfinalizedStoppedCaptureForLog(logFile: File): CaptureSession? {
    if (logFile.name != "logcat.log") return null
    val logsDirectory = logFile.parentFile?.takeIf { it.name == "logs" } ?: return null
    val directory = logsDirectory.parentFile ?: return null
    val session = readCaptureSessionDirectory(directory) ?: return null
    val sameLog = runCatching { session.logFile.canonicalFile == logFile.canonicalFile }.getOrDefault(false)
    return session.takeIf {
        sameLog &&
            it.status != CaptureStatus.RECORDING &&
            !File(directory, CAPTURE_DESCRIPTOR_NAME).exists()
    }
}
