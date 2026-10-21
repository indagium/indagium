package com.indagium

import com.github.luben.zstd.Zstd
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Writes ZIPs whose chosen entries use ZIP method 93 (zstd) — what a Compact capture archive's log
 * looks like on disk — for the reader-side tests. Goes through `addRawArchiveEntry` with the zstd
 * frame precomputed because `putArchiveEntry` + write would compute the CRC over the COMPRESSED
 * bytes for method 93 (commons-compress only knows how to hash the stored form there), producing an
 * archive every strict reader rejects. The CRC32 here is of the UNCOMPRESSED bytes, as the ZIP spec
 * requires.
 */
object ZstdZipFixture {
    fun write(file: File, entries: Map<String, ByteArray>, zstdEntries: Set<String>) {
        ZipArchiveOutputStream(file).use { out ->
            entries.forEach { (name, bytes) ->
                if (name in zstdEntries) out.addZstdEntry(name, bytes) else out.addDeflatedEntry(name, bytes)
            }
        }
    }

    /** Copies [source] to [destination], re-storing every entry named in [zstdEntries] as method 93. */
    fun rewrite(source: File, destination: File, zstdEntries: Set<String>) {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipFile.builder().setFile(source).get().use { zip ->
            zip.entries.asSequence().forEach { entry ->
                entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        write(destination, entries, zstdEntries)
    }

    private fun ZipArchiveOutputStream.addDeflatedEntry(name: String, bytes: ByteArray) {
        putArchiveEntry(ZipArchiveEntry(name))
        write(bytes)
        closeArchiveEntry()
    }

    private fun ZipArchiveOutputStream.addZstdEntry(name: String, bytes: ByteArray) {
        val compressed = Zstd.compress(bytes)
        val entry = ZipArchiveEntry(name).apply {
            method = ZipMethod.ZSTD.code
            size = bytes.size.toLong()
            compressedSize = compressed.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
        }
        addRawArchiveEntry(entry, ByteArrayInputStream(compressed))
    }
}
