package com.indagium.ui

import java.io.File

/** The system property `com.github.luben.zstd.util.Native.load()` reads for the folder it unpacks its native library into. */
internal const val ZSTD_TEMP_FOLDER_PROPERTY = "ZstdTempFolder"

private const val ZSTD_NATIVE_FILE_PREFIX = "libzstd-jni"

// Younger files may belong to a process that is unpacking the library right now (create, then load);
// a leftover from a finished run is older than this by the time anything sweeps it.
internal const val ZSTD_STALE_NATIVE_FILE_MS = 10L * 60L * 1000L

/**
 * Points zstd-jni at an app-owned [directory] instead of `java.io.tmpdir`, and removes the copies of
 * its native library that earlier runs left there.
 *
 * zstd-jni unpacks its library to a fresh temp file on first use. On Windows the loaded DLL cannot be
 * deleted afterwards, so a shared temp folder collects one copy per run; on Linux a `noexec` /tmp makes
 * the load fail. A folder under the app data directory fixes both. An explicit [getProperty] value
 * (a user's own `-DZstdTempFolder`) always wins and is left untouched. Failing to create the folder
 * leaves the property unset, so zstd-jni keeps its default behaviour. Must run before the first zstd
 * use (app startup, before [AppState]); never throws.
 *
 * @return true when the property now points at [directory].
 */
internal fun configureZstdTempFolder(
    directory: File,
    nowMs: Long = System.currentTimeMillis(),
    getProperty: (String) -> String? = System::getProperty,
    setProperty: (String, String) -> Unit = { key, value -> System.setProperty(key, value) },
): Boolean {
    if (!getProperty(ZSTD_TEMP_FOLDER_PROPERTY).isNullOrBlank()) return false
    if (!(directory.isDirectory || directory.mkdirs()) || !directory.isDirectory) return false
    setProperty(ZSTD_TEMP_FOLDER_PROPERTY, directory.absolutePath)
    sweepStaleZstdNativeFiles(directory, nowMs)
    return true
}

/**
 * Best-effort delete of old `libzstd-jni*` files in [directory]. A copy still loaded by a running
 * process cannot be deleted on Windows (the failure is ignored); one that is merely being unpacked
 * is protected by [ZSTD_STALE_NATIVE_FILE_MS].
 */
internal fun sweepStaleZstdNativeFiles(directory: File, nowMs: Long = System.currentTimeMillis()): Int {
    val files = runCatching { directory.listFiles() }.getOrNull() ?: return 0
    return files.count { file ->
        file.isFile &&
            file.name.startsWith(ZSTD_NATIVE_FILE_PREFIX) &&
            nowMs - file.lastModified() >= ZSTD_STALE_NATIVE_FILE_MS &&
            runCatching { file.delete() }.getOrDefault(false)
    }
}
