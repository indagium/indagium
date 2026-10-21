package com.indagium.capture

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException

/**
 * Opens [entry] for reading. A zstd (ZIP method 93) entry is decoded by zstd-jni while it is read, so a
 * damaged one fails there with a raw `ZstdIOException`; [ZstdDecodeInputStream] turns that into a
 * [CaptureArchiveException] the import paths can show. Every other method is returned untouched.
 */
internal fun ZipFile.openEntryStream(entry: ZipArchiveEntry, what: String): InputStream {
    val stream = getInputStream(entry)
    return if (entry.method == ZipMethod.ZSTD.code) ZstdDecodeInputStream(stream, what) else stream
}

/**
 * Maps any [IOException] raised while reading a zstd entry to a [CaptureArchiveException] with the
 * original as its cause. Only reads of the compressed entry are wrapped, so a failure writing the
 * extracted file is never mistaken for corruption; cancellation ([InterruptedIOException],
 * [ClosedByInterruptException]) propagates unchanged.
 */
private class ZstdDecodeInputStream(delegate: InputStream, private val what: String) : FilterInputStream(delegate) {
    override fun read(): Int = decoding { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = decoding { super.read(buffer, offset, length) }

    private inline fun <T> decoding(block: () -> T): T = try {
        block()
    } catch (e: InterruptedIOException) {
        throw e
    } catch (e: ClosedByInterruptException) {
        throw e
    } catch (e: CaptureArchiveException) {
        throw e
    } catch (e: IOException) {
        throw CaptureArchiveException("The $what in this archive is corrupted (zstd data could not be decoded)", e)
    }
}
