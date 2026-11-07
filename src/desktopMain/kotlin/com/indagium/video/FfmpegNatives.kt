package com.indagium.video

import com.indagium.debug.AppLogger
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.util.concurrent.CompletableFuture

/** Runs [load] once, on its own daemon thread that nothing interrupts, the first time [await] is
 *  called. [await] ignores interrupts while it waits and leaves the caller's interrupt flag set. */
internal class UninterruptedOneTimeLoad(private val threadName: String, private val load: () -> Unit) {
    @Volatile private var succeeded = false

    private val finished by lazy {
        CompletableFuture<Unit>().also { done ->
            Thread({
                val outcome = runCatching(load)
                outcome.onFailure { AppLogger.error("video", "$threadName failed", it) }
                succeeded = outcome.isSuccess
                done.complete(Unit)
            }, threadName).apply { isDaemon = true }.start()
        }
    }

    /** True once the load has ended without a failure. Never starts the load. */
    val isLoaded: Boolean get() = succeeded

    /** Returns once the load has ended, however it ended: a failed load surfaces at first use. */
    fun await() {
        finished.join()
    }
}

// The one place a JavaCPP class may be initialized for the first time. JavaCPP's Loader.loadLibrary
// holds the Loader class monitor while System.load runs a library's JNI_OnLoad, and that waits for
// the class initialization of Pointer and the presets' classes. A second thread that is itself
// inside such a static initializer (Pointer.<clinit> is one) waits for the Loader monitor: a cycle
// that froze the process. Every thread that can be first to touch JavaCPP (capture preview and
// export, the mirror decoders, the transcoder, HeapTrim, video playback) therefore calls
// awaitFfmpegNatives() first, and this single thread initializes everything in a fixed order, so
// no static initializer is ever running on another thread while a native library loads.
//
// wakeDecoder interrupts the decode thread on every play/seek/rate change, and with an empty
// JavaCPP cache an interrupted load fails FFmpeg's class init for the rest of the process, so the
// load must run on a thread that nothing interrupts.
private val ffmpegNativeLoad = UninterruptedOneTimeLoad("indagium-ffmpeg-load", ::loadFfmpegNatives)

/** Blocks until the process-wide JavaCPP/FFmpeg load has ended (starting it if nobody has yet).
 *  Call it before the first use of any `org.bytedeco` class, from a background thread, never the UI
 *  thread. Ignores interrupts and leaves the caller's interrupt flag set; a failed load is logged
 *  and surfaces as the usual link error at first use. */
internal fun awaitFfmpegNatives() = ffmpegNativeLoad.await()

/** Whether the load above has finished successfully. Never starts it, so a timer may poll it. */
internal fun ffmpegNativesLoaded(): Boolean = ffmpegNativeLoad.isLoaded

// Order matters: JavaCPP core first (Pointer's initializer loads libjnijavacpp), then every FFmpeg
// global the app uses, then the JavaCV classes built on them. A failed step does not stop the
// later ones, so none is left to be initialized on a caller's thread; the first failure is
// rethrown afterwards for the one-time load to log.
private val FFMPEG_NATIVE_CLASSES: List<Class<*>> = listOf(
    Pointer::class.java,
    BytePointer::class.java,
    IntPointer::class.java,
    PointerPointer::class.java,
    avutil::class.java,
    avcodec::class.java,
    avformat::class.java,
    swscale::class.java,
    swresample::class.java,
    avfilter::class.java,
    Frame::class.java,
    FrameGrabber::class.java,
    FFmpegFrameGrabber::class.java,
    FFmpegFrameRecorder::class.java,
    FFmpegFrameFilter::class.java,
    Java2DFrameConverter::class.java,
)

@Suppress("TooGenericExceptionCaught")
private fun loadFfmpegNatives() {
    var firstFailure: Throwable? = null
    for (type in FFMPEG_NATIVE_CLASSES) {
        try {
            Class.forName(type.name, true, type.classLoader)
        } catch (failure: Throwable) {
            val first = firstFailure
            if (first == null) firstFailure = failure else first.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
}
