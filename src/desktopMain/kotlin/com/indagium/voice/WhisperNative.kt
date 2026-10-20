package com.indagium.voice

/** JNI boundary for the app-owned bridge, which statically links pinned whisper.cpp and GGML. */
internal object WhisperNative {
    @JvmStatic
    external fun transcribe(
        modelPath: String,
        samples: FloatArray,
        language: String,
        translate: Boolean,
        initialPrompt: String,
    ): String
}
