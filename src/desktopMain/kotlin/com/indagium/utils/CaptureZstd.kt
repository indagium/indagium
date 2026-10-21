package com.indagium.utils

import com.github.luben.zstd.util.Native

/**
 * Whether zstd-jni's native library can be loaded on this machine. Compact capture archives store
 * their log as ZIP method 93 (zstd), which commons-compress reads and writes through zstd-jni; the
 * jar bundled for this OS/arch normally always loads, but a locked-down temp directory (the library
 * is unpacked there) or an exotic platform makes the load fail with a [LinkageError], which is not
 * an Exception and so must be probed rather than assumed. Resolved once and cached.
 */
object CaptureZstd {
    val isAvailable: Boolean by lazy {
        try {
            Native.load()
            true
        } catch (_: LinkageError) {
            false
        }
    }
}
