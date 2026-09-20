package com.indagium.capture

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_dict_copy
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val MICROS_PER_SECOND = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val COPY_BUFFER_BYTES = 1024 * 1024
private const val FFMPEG_ERROR_BUFFER_BYTES = 256L
private val NO_FORMAT_OPTIONS: PointerPointer<*>? = null

/**
 * Exports a playable interval from the immutable prefix of a scrcpy Matroska recording that exists
 * when [export] starts. The encoded packets are remuxed; the source is never sought, truncated, or
 * otherwise modified and no codec is required on the machine beyond the bundled JavaCPP FFmpeg.
 */
class FfmpegCaptureVideoExporter : CaptureVideoExporter {
    override fun export(
        source: File,
        destination: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): CaptureVideoClip {
        require(requestedStartMs >= 0L) { "Video export start must be non-negative" }
        require(requestedEndMs > requestedStartMs) { "Video export end must be after its start" }
        require(source.isFile) { "Capture video does not exist: ${source.absolutePath}" }
        require(source.canonicalFile != destination.canonicalFile) { "Video destination must differ from its capture source" }

        val destinationParent = destination.absoluteFile.parentFile
            ?: throw IOException("Video destination has no parent: ${destination.absolutePath}")
        destinationParent.mkdirs()
        val snapshot = Files.createTempFile(destinationParent.toPath(), ".capture-snapshot-", ".mkv").toFile()
        val staging = Files.createTempFile(destinationParent.toPath(), ".capture-export-", ".mkv").toFile()
        try {
            copyCurrentPrefix(source, snapshot)
            val window = scanWindow(snapshot, requestedStartMs, requestedEndMs)
            remux(snapshot, staging, window)
            replaceDestination(staging, destination)
            return CaptureVideoClip(
                actualStartMs = window.actualStartUs / MILLIS_PER_SECOND,
                coveredEndMs = window.coveredEndUs / MILLIS_PER_SECOND,
                durationMs = (window.coveredEndUs - window.actualStartUs) / MILLIS_PER_SECOND,
            )
        } finally {
            snapshot.delete()
            staging.delete()
        }
    }
}

private data class ExportWindow(val actualStartUs: Long, val coveredEndUs: Long, val videoStreamIndex: Int)

/** File.length() is sampled once: bytes appended after this point are deliberately invisible. */
private fun copyCurrentPrefix(source: File, snapshot: File) {
    val prefixLength = source.length()
    if (prefixLength <= 0L) throw IOException("Capture video has no readable bytes: ${source.absolutePath}")
    FileChannel.open(source.toPath(), StandardOpenOption.READ).use { input ->
        FileChannel.open(snapshot.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
            var remaining = prefixLength
            while (remaining > 0L) {
                checkInterrupted()
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                buffer.flip()
                while (buffer.hasRemaining()) output.write(buffer)
                remaining -= read
            }
            if (remaining != 0L) {
                throw IOException("Capture video became shorter while its prefix was being snapshotted")
            }
            output.force(false)
        }
    }
}

private fun scanWindow(snapshot: File, requestedStartMs: Long, requestedEndMs: Long): ExportWindow =
    withInput(snapshot) { input ->
        val videoStreamIndex = firstVideoStream(input)
        val requestedStartUs = requestedStartMs * MILLIS_PER_SECOND
        val requestedEndUs = requestedEndMs * MILLIS_PER_SECOND
        var actualStartUs: Long? = null
        var coveredEndUs = Long.MIN_VALUE
        var readableVideoPackets = 0L
        readPackets(input, snapshot.length()) { packet ->
            if (packet.stream_index() != videoStreamIndex) return@readPackets
            val stream = input.streams(videoStreamIndex)
            val startUs = packetTimestampUs(packet, stream) ?: return@readPackets
            val endUs = packetEndUs(packet, stream, startUs)
            readableVideoPackets++
            if ((packet.flags() and AV_PKT_FLAG_KEY) != 0) {
                when {
                    startUs <= requestedStartUs -> {
                        // A later preceding keyframe supersedes any earlier candidate and its
                        // coverage. Starting at an earlier keyframe would include unnecessary
                        // video and can make the reported interval inconsistent with remuxing.
                        actualStartUs = startUs
                        coveredEndUs = Long.MIN_VALUE
                    }
                    actualStartUs == null && startUs < requestedEndUs -> {
                        // Video may start after the session's monotonic zero (for example when
                        // another stream starts first). Report the first usable video keyframe.
                        actualStartUs = startUs
                        coveredEndUs = Long.MIN_VALUE
                    }
                }
            }
            val selectedStartUs = actualStartUs
            if (selectedStartUs != null && startUs >= selectedStartUs && startUs < requestedEndUs && endUs <= requestedEndUs) {
                coveredEndUs = maxOf(coveredEndUs, endUs)
            }
        }
        if (readableVideoPackets == 0L) throw IOException("Capture snapshot contains no readable video packets")
        val actualStart = actualStartUs
            ?: throw IOException("Capture snapshot has no readable keyframe in the requested interval")
        if (coveredEndUs <= actualStart) {
            throw IOException("Capture snapshot contains no complete video in the requested interval")
        }
        ExportWindow(actualStart, coveredEndUs, videoStreamIndex)
    }

private fun remux(snapshot: File, staging: File, window: ExportWindow) {
    withInput(snapshot) { input ->
        val outputPointer = PointerPointer<Pointer>(1)
        outputPointer.put(null as Pointer?)
        var output: AVFormatContext? = null
        var outputOpened = false
        var headerWritten = false
        try {
            BytePointer("matroska").use { formatName ->
                BytePointer(staging.absolutePath).use { destinationName ->
                    ffmpegCheck(
                        avformat_alloc_output_context2(outputPointer, null, formatName, destinationName),
                        "allocate Matroska output",
                    )
                }
            }
            output = AVFormatContext(outputPointer.get())
            if (output.isNull) throw IOException("FFmpeg did not allocate an output context")
            val context = output
            val streamMap = copyStreams(input, context)
            if ((context.oformat().flags() and AVFMT_NOFILE) == 0) {
                openOutputIo(context, staging)
                outputOpened = true
            }
            copyMetadata(input.metadata(), "copy container metadata") { context.metadata(it) }
            ffmpegCheck(avformat_write_header(context, NO_FORMAT_OPTIONS), "write export header")
            headerWritten = true

            readPackets(input, snapshot.length()) { packet ->
                val inputIndex = packet.stream_index()
                val outputIndex = streamMap[inputIndex]
                if (outputIndex < 0) return@readPackets
                val inputStream = input.streams(inputIndex)
                if (!packetIsInWindow(packet, inputStream, inputIndex == window.videoStreamIndex, window)) {
                    return@readPackets
                }
                val outputStream = context.streams(outputIndex)
                rebasePacket(packet, inputStream, outputStream, window.actualStartUs)
                packet.stream_index(outputIndex).pos(-1L)
                ffmpegCheck(av_interleaved_write_frame(context, packet), "write export packet")
            }
            checkInterrupted()
            val trailerResult = av_write_trailer(context)
            headerWritten = false
            ffmpegCheck(trailerResult, "finalize export")
        } finally {
            val context = output
            if (headerWritten && context != null) runCatching { av_write_trailer(context) }
            if (outputOpened && context != null) closeOutputIo(context)
            if (context != null && !context.isNull) avformat_free_context(context)
            outputPointer.close()
        }
    }
}

private fun openOutputIo(output: AVFormatContext, destination: File) {
    val ioPointer = PointerPointer<Pointer>(1)
    try {
        ioPointer.put(null as Pointer?)
        BytePointer(destination.absolutePath).use { destinationName ->
            ffmpegCheck(avio_open(ioPointer, destinationName, AVIO_FLAG_WRITE), "open export output")
        }
        val io = AVIOContext(ioPointer.get())
        if (io.isNull) throw IOException("FFmpeg did not allocate export output I/O")
        output.pb(io)
    } finally {
        ioPointer.close()
    }
}

private fun closeOutputIo(output: AVFormatContext) {
    val io = output.pb() ?: return
    if (io.isNull) return
    val ioPointer = PointerPointer<Pointer>(1)
    try {
        ioPointer.put(io)
        avio_closep(ioPointer)
        output.pb(null)
    } finally {
        ioPointer.close()
    }
}

/** Copies into a real native pointer slot, then transfers ownership to the format context. */
private fun copyMetadata(source: AVDictionary?, operation: String, install: (AVDictionary) -> Unit) {
    if (source == null || source.isNull) return
    val destination = PointerPointer<Pointer>(1)
    try {
        destination.put(null as Pointer?)
        ffmpegCheck(av_dict_copy(destination, source, 0), operation)
        val copy = AVDictionary(destination.get())
        if (!copy.isNull) install(copy)
    } finally {
        destination.close()
    }
}

private fun copyStreams(input: AVFormatContext, output: AVFormatContext): IntArray {
    val mapping = IntArray(input.nb_streams()) { -1 }
    repeat(input.nb_streams()) { index ->
        checkInterrupted()
        val inputStream = input.streams(index)
        val outputStream = avformat_new_stream(output, null)
        if (outputStream == null || outputStream.isNull) throw IOException("FFmpeg could not allocate output stream $index")
        ffmpegCheck(avcodec_parameters_copy(outputStream.codecpar(), inputStream.codecpar()), "copy stream $index codec")
        outputStream.id(inputStream.id())
        outputStream.disposition(inputStream.disposition())
        outputStream.time_base().num(inputStream.time_base().num()).den(inputStream.time_base().den())
        outputStream.avg_frame_rate().num(inputStream.avg_frame_rate().num()).den(inputStream.avg_frame_rate().den())
        outputStream.r_frame_rate().num(inputStream.r_frame_rate().num()).den(inputStream.r_frame_rate().den())
        outputStream.sample_aspect_ratio()
            .num(inputStream.sample_aspect_ratio().num())
            .den(inputStream.sample_aspect_ratio().den())
        copyMetadata(inputStream.metadata(), "copy stream $index metadata") { outputStream.metadata(it) }
        mapping[index] = outputStream.index()
    }
    return mapping
}

private fun packetIsInWindow(
    packet: AVPacket,
    stream: AVStream,
    video: Boolean,
    window: ExportWindow,
): Boolean {
    val startUs = packetTimestampUs(packet, stream) ?: return false
    val endUs = packetEndUs(packet, stream, startUs)
    return if (video) {
        startUs >= window.actualStartUs && startUs < window.coveredEndUs && endUs <= window.coveredEndUs
    } else {
        endUs > window.actualStartUs && startUs < window.coveredEndUs
    }
}

private fun rebasePacket(packet: AVPacket, input: AVStream, output: AVStream, startUs: Long) {
    val offset = microsToTicks(startUs, input)
    if (packet.pts() != AV_NOPTS_VALUE) packet.pts(packet.pts() - offset)
    if (packet.dts() != AV_NOPTS_VALUE) packet.dts(packet.dts() - offset)
    av_packet_rescale_ts(packet, input.time_base(), output.time_base())
}

private fun firstVideoStream(input: AVFormatContext): Int {
    repeat(input.nb_streams()) { index ->
        if (input.streams(index).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) return index
    }
    throw IOException("Capture snapshot contains no video stream")
}

private inline fun <T> withInput(file: File, block: (AVFormatContext) -> T): T {
    checkInterrupted()
    val inputPointer = PointerPointer<Pointer>(1)
    inputPointer.put(null as Pointer?)
    var opened = false
    try {
        BytePointer(file.absolutePath).use { filename ->
            ffmpegCheck(avformat_open_input(inputPointer, filename, null, NO_FORMAT_OPTIONS), "open capture snapshot")
        }
        opened = true
        val input = AVFormatContext(inputPointer.get())
        if (input.isNull) throw IOException("FFmpeg did not allocate an input context")
        val infoResult = avformat_find_stream_info(input, NO_FORMAT_OPTIONS)
        if (infoResult < 0 && input.nb_streams() == 0) ffmpegCheck(infoResult, "read capture stream information")
        return block(input)
    } finally {
        if (opened) avformat_close_input(inputPointer)
        inputPointer.close()
    }
}

/** Truncated live tails may end as INVALIDDATA only after the demuxer consumed the physical EOF. */
private inline fun readPackets(input: AVFormatContext, inputLength: Long, consume: (AVPacket) -> Unit) {
    val packet = org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc()
        ?: throw IOException("FFmpeg could not allocate a packet")
    var packetsRead = 0L
    try {
        while (true) {
            checkInterrupted()
            val result = av_read_frame(input, packet)
            if (result < 0) {
                val inputEndedMidElement = result == AVERROR_INVALIDDATA &&
                    packetsRead > 0L && atPhysicalEof(input, inputLength)
                if (result == AVERROR_EOF || inputEndedMidElement) break
                ffmpegCheck(result, "read capture packet")
            }
            consume(packet)
            packetsRead++
            av_packet_unref(packet)
        }
    } finally {
        av_packet_unref(packet)
        av_packet_free(packet)
    }
}

/**
 * `AVIOContext.pos` points after the buffered data. Subtract unread bytes so corrupt data that
 * happens to share a read-ahead buffer with EOF remains an error instead of looking like a live
 * recording's incomplete final element.
 */
private fun atPhysicalEof(input: AVFormatContext, inputLength: Long): Boolean {
    val io = input.pb()
    if (io.isNull || io.eof_reached() == 0) return false
    val unreadBytes = (io.buf_end().address() - io.buf_ptr().address()).coerceAtLeast(0L)
    return io.pos() - unreadBytes >= inputLength
}

private fun packetTimestampUs(packet: AVPacket, stream: AVStream): Long? {
    val timestamp = if (packet.pts() != AV_NOPTS_VALUE) packet.pts() else packet.dts()
    if (timestamp == AV_NOPTS_VALUE || stream.time_base().den() == 0) return null
    return ticksToMicros(timestamp, stream)
}

private fun packetEndUs(packet: AVPacket, stream: AVStream, startUs: Long): Long {
    if (packet.duration() <= 0L) return startUs
    return startUs + ticksToMicros(packet.duration(), stream).coerceAtLeast(0L)
}

private fun ticksToMicros(ticks: Long, stream: AVStream): Long =
    (ticks.toDouble() * stream.time_base().num() * MICROS_PER_SECOND / stream.time_base().den()).toLong()

private fun microsToTicks(micros: Long, stream: AVStream): Long =
    (micros.toDouble() * stream.time_base().den() / (stream.time_base().num() * MICROS_PER_SECOND)).toLong()

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Video export interrupted")
}

private fun ffmpegCheck(result: Int, operation: String) {
    if (result >= 0) return
    val buffer = BytePointer(FFMPEG_ERROR_BUFFER_BYTES)
    try {
        av_strerror(result, buffer, FFMPEG_ERROR_BUFFER_BYTES)
        throw IOException("Could not $operation: ${buffer.string}")
    } finally {
        buffer.close()
    }
}

private fun replaceDestination(staging: File, destination: File) {
    try {
        Files.move(
            staging.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
