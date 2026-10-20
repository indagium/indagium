#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
std::mutex inference_mutex;

void throw_java(JNIEnv *env, const char *class_name, const std::string &message) {
    jclass type = env->FindClass(class_name);
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string utf8_from_jstring(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Could not read a Java string");
    const jsize length = env->GetStringLength(value);
    std::string utf8;
    utf8.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xdc00 && low <= 0xdfff) {
                codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
                ++i;
            }
        }
        if (codepoint <= 0x7f) {
            utf8.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7ff) {
            utf8.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else if (codepoint <= 0xffff) {
            utf8.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            utf8.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else {
            utf8.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
            utf8.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
            utf8.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            utf8.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return utf8;
}

jstring jstring_from_utf8(JNIEnv *env, const std::string &utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    for (size_t i = 0; i < utf8.size();) {
        const uint8_t first = static_cast<uint8_t>(utf8[i++]);
        uint32_t codepoint;
        if (first < 0x80) {
            codepoint = first;
        } else if ((first & 0xe0) == 0xc0 && i < utf8.size()) {
            codepoint = (first & 0x1f) << 6;
            codepoint |= static_cast<uint8_t>(utf8[i++]) & 0x3f;
        } else if ((first & 0xf0) == 0xe0 && i + 1 < utf8.size()) {
            codepoint = (first & 0x0f) << 12;
            codepoint |= (static_cast<uint8_t>(utf8[i++]) & 0x3f) << 6;
            codepoint |= static_cast<uint8_t>(utf8[i++]) & 0x3f;
        } else if ((first & 0xf8) == 0xf0 && i + 2 < utf8.size()) {
            codepoint = (first & 0x07) << 18;
            codepoint |= (static_cast<uint8_t>(utf8[i++]) & 0x3f) << 12;
            codepoint |= (static_cast<uint8_t>(utf8[i++]) & 0x3f) << 6;
            codepoint |= static_cast<uint8_t>(utf8[i++]) & 0x3f;
        } else {
            codepoint = 0xfffd;
        }
        if (codepoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::filesystem::path path_from_jstring(JNIEnv *env, jstring value) {
    if (value == nullptr) throw std::runtime_error("Model path is missing");
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Could not read the model path");
    const jsize length = env->GetStringLength(value);
#ifdef _WIN32
    std::wstring path(reinterpret_cast<const wchar_t *>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return std::filesystem::path(path);
#else
    env->ReleaseStringChars(value, chars);
    return std::filesystem::u8path(utf8_from_jstring(env, value));
#endif
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_indagium_voice_WhisperNative_transcribe(JNIEnv *env, jclass, jstring model_path,
                                                  jfloatArray audio, jstring language,
                                                  jboolean translate, jstring initial_prompt) {
    try {
        if (audio == nullptr) throw std::runtime_error("Audio samples are missing");
        const jsize sample_count = env->GetArrayLength(audio);
        if (sample_count <= 0) throw std::runtime_error("The recording contains no audio samples");
        std::vector<float> samples(static_cast<size_t>(sample_count));
        env->GetFloatArrayRegion(audio, 0, sample_count, samples.data());
        if (env->ExceptionCheck()) return nullptr;

        const std::string language_value = utf8_from_jstring(env, language);
        const std::string prompt_value = utf8_from_jstring(env, initial_prompt);
        const std::filesystem::path model_file = path_from_jstring(env, model_path);
        std::lock_guard<std::mutex> inference_lock(inference_mutex);

        const auto model_path_utf8 = model_file.u8string();
        auto load_context = [&](bool use_gpu) {
            whisper_context_params context_params = whisper_context_default_params();
            context_params.use_gpu = use_gpu;
            return std::unique_ptr<whisper_context, decltype(&whisper_free)>(
                whisper_init_from_file_with_params(model_path_utf8.c_str(), context_params),
                &whisper_free);
        };
#ifdef __APPLE__
        auto context = load_context(true);
        if (context == nullptr) {
            // A Metal device may be unavailable or short on memory. The pinned loader patch turns
            // its failed buffer allocation into a normal null context so this CPU retry can run.
            context = load_context(false);
        }
#else
        auto context = load_context(false);
#endif
        if (context == nullptr) throw std::runtime_error("Could not load the selected local Whisper model");

        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.translate = translate == JNI_TRUE;
        params.language = language_value.empty() ? "auto" : language_value.c_str();
        params.detect_language = false; // auto means detect, then transcribe; detect_language alone stops before decoding.
        params.no_timestamps = true;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.initial_prompt = prompt_value.empty() ? nullptr : prompt_value.c_str();
        params.n_threads = std::max(1, std::min(8, static_cast<int>(std::thread::hardware_concurrency())));

        const int result = whisper_full(context.get(), params, samples.data(), sample_count);
        if (result != 0) {
            throw std::runtime_error("Whisper transcription failed with native status " + std::to_string(result));
        }
        std::string text;
        const int segments = whisper_full_n_segments(context.get());
        for (int i = 0; i < segments; ++i) {
            const char *segment = whisper_full_get_segment_text(context.get(), i);
            if (segment != nullptr) text.append(segment);
        }
        return jstring_from_utf8(env, text);
    } catch (const std::exception &error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Unknown local Whisper native failure");
        return nullptr;
    }
}
