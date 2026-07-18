package com.openlog.debug

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Opt-in, self-viewable diagnostic logging for openLog itself.  Its output intentionally uses
 * Android's threadtime grammar, so the file can be opened in openLog just like a device log.
 * Messages are a small operational vocabulary, never user log rows, AI content, credentials, or
 * absolute paths.  This is a best-effort diagnostic aid: a write failure never affects the app.
 */
internal object AppLogger {
    private const val LOG_TAG = "openLog"
    private val lock = ReentrantLock()
    private val timestampFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private var writer: Writer? = null
    private var currentPath: String? = null
    private var failureReporter: ((String) -> Unit)? = null

    /** AppState installs this while it is alive so an asynchronous write failure reaches Settings. */
    fun setFailureReporter(reporter: ((String) -> Unit)?) = lock.withLock { failureReporter = reporter }

    /** Returns a display-safe failure reason, or null once the requested configuration is active. */
    fun configure(enabled: Boolean, path: String?): String? = lock.withLock {
        if (!enabled || path.isNullOrBlank()) {
            closeLocked()
            return@withLock null
        }
        if (writer != null && path == currentPath) return@withLock null
        closeLocked()
        runCatching {
            val file = File(path)
            val parent = file.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                error("Could not create diagnostic log folder")
            }
            writer = OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8)
            currentPath = path
        }.exceptionOrNull()?.let { error ->
            closeLocked()
            safeText(error.message ?: error::class.simpleName ?: "unknown error").also(::reportFailureLocked)
        }
    }

    fun debug(component: String, message: String) = write('D', component, message, null)

    fun info(component: String, message: String) = write('I', component, message, null)

    fun warn(component: String, message: String, error: Throwable? = null) = write('W', component, message, error)

    fun error(component: String, message: String, error: Throwable? = null) = write('E', component, message, error)

    fun close() = lock.withLock { closeLocked() }

    private fun write(level: Char, component: String, message: String, error: Throwable?) {
        lock.withLock {
            val output = writer ?: return
            runCatching {
                appendThreadtime(output, level, component, message)
                // Each physical throwable line receives its own threadtime header.  A parser therefore
                // sees every stack line as a normal LogEntry rather than an unstructured RAW row.
                error?.stackTraceToString()?.lineSequence()?.filter { it.isNotBlank() }?.forEach { line ->
                    appendThreadtime(output, level, component, line)
                }
                output.flush()
            }.onFailure { error ->
                closeLocked()
                reportFailureLocked(safeText(error.message ?: error::class.simpleName ?: "unknown error"))
            }
        }
    }

    private fun appendThreadtime(output: Writer, level: Char, component: String, message: String) {
        val pid = runCatching { ProcessHandle.current().pid() }.getOrDefault(0L)
        val tid = Thread.currentThread().threadId()
        val header = buildString {
            append(timestampFormat.format(LocalDateTime.now()))
            append(" ${pid.toString().padStart(5)} ${tid.toString().padStart(5)} ")
            append("$level $LOG_TAG.${safeComponent(component)}: ")
        }
        output.append(header)
            .append("[").append(levelName(level)).append("][")
            .append(safeComponent(component)).append("] ")
            .append(safeText(message))
            .append('\n')
    }

    private fun levelName(level: Char) = when (level) {
        'D' -> "DEBUG"
        'I' -> "INFO"
        'W' -> "WARN"
        else -> "ERROR"
    }

    private fun safeComponent(component: String): String =
        component.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40).ifBlank { "app" }

    /** Keeps diagnostics operationally useful without retaining sensitive/user-owned data. */
    internal fun safeText(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("(?i)(api[_-]?key|token|secret|password|authorization)\\s*([=:])\\s*[^\\s,;]+"), "$1$2[REDACTED]")
        .replace(Regex("(?i)\\b[A-Z]:\\\\[^\\s,;]+"), "[PATH]")
        .replace(Regex("(?<![A-Za-z0-9])/(?:[^\\s,;]+)"), "[PATH]")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(2_000)
        .ifBlank { "(no detail)" }

    private fun closeLocked() {
        runCatching { writer?.close() }
        writer = null
        currentPath = null
    }

    private fun reportFailureLocked(reason: String) {
        failureReporter?.invoke(reason)
    }
}
