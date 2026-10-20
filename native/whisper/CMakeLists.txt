cmake_minimum_required(VERSION 3.20)
project(indagium_whisper LANGUAGES C CXX)

include(FetchContent)

# Keep the complete native implementation pinned to a release archive and verify its content
# before CMake unpacks it. FetchContent stores sources under the Gradle build directory cache.
set(WHISPER_CPP_URL "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz")
set(WHISPER_CPP_SHA256 "ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Link Whisper and GGML statically into the app bridge" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "Use the pinned stable whisper.cpp release" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Build portable CPU kernels" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Avoid external OpenMP runtime dependencies" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Keep GGML statically linked" FORCE)
# Avoid instruction sets that are absent from older x86-64 CPUs. The upstream defaults may enable
# these based on the build host even with GGML_NATIVE disabled.
set(GGML_SSE42 OFF CACHE BOOL "" FORCE)
set(GGML_AVX OFF CACHE BOOL "" FORCE)
set(GGML_AVX_VNNI OFF CACHE BOOL "" FORCE)
set(GGML_AVX2 OFF CACHE BOOL "" FORCE)
set(GGML_BMI2 OFF CACHE BOOL "" FORCE)
set(GGML_AVX512 OFF CACHE BOOL "" FORCE)
if(NOT MSVC)
    set(GGML_FMA OFF CACHE BOOL "" FORCE)
    set(GGML_F16C OFF CACHE BOOL "" FORCE)
endif()
if(APPLE)
    set(GGML_METAL ON CACHE BOOL "Use embedded Metal kernels on macOS" FORCE)
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "Embed Metal kernels in the bridge" FORCE)
else()
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(whispercpp
    URL "${WHISPER_CPP_URL}"
    URL_HASH "SHA256=${WHISPER_CPP_SHA256}"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(whispercpp)

# whisper.cpp 1.9.5 assumes every backend tensor allocation succeeds. Metal can be unavailable or
# unable to allocate a model on a constrained host; the following null buffer dereference would
# assert inside ggml before the JNI caller could fall back to CPU. Patch only the two pinned model
# loaders so an allocation failure returns normally. Exact anchors make an upstream source change
# fail closed rather than silently building an unpatched release.
set(WHISPER_CPP_SOURCE "${whispercpp_SOURCE_DIR}/src/whisper.cpp")
file(READ "${WHISPER_CPP_SOURCE}" WHISPER_CPP_SOURCE_TEXT)
set(WHISPER_MODEL_PATCH_MARKER "if (!buf) {\n            WHISPER_LOG_ERROR(\"%s: backend tensor allocation failed; aborting model load\\n\", __func__);\n            return false;\n        }\n        if (buf) {\n            model.buffers.emplace_back(buf);")
set(WHISPER_VAD_PATCH_MARKER "if (!buf) {\n            WHISPER_LOG_ERROR(\"%s: backend tensor allocation failed; aborting VAD model load\\n\", __func__);\n            return nullptr;\n        }\n        if (buf) {\n            model.buffers.emplace_back(buf);")
string(FIND "${WHISPER_CPP_SOURCE_TEXT}" "${WHISPER_MODEL_PATCH_MARKER}" WHISPER_MODEL_PATCH_INDEX)
string(FIND "${WHISPER_CPP_SOURCE_TEXT}" "${WHISPER_VAD_PATCH_MARKER}" WHISPER_VAD_PATCH_INDEX)
if(WHISPER_MODEL_PATCH_INDEX EQUAL -1 AND WHISPER_VAD_PATCH_INDEX EQUAL -1)
set(WHISPER_MODEL_ALLOC_ANCHOR "ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, buft);\n        if (buf) {\n            model.buffers.emplace_back(buf);")
set(WHISPER_MODEL_ALLOC_PATCH "ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, buft);\n        if (!buf) {\n            WHISPER_LOG_ERROR(\"%s: backend tensor allocation failed; aborting model load\\n\", __func__);\n            return false;\n        }\n        if (buf) {\n            model.buffers.emplace_back(buf);")
string(FIND "${WHISPER_CPP_SOURCE_TEXT}" "${WHISPER_MODEL_ALLOC_ANCHOR}" WHISPER_FIRST_ALLOC_INDEX)
if(WHISPER_FIRST_ALLOC_INDEX EQUAL -1)
    message(FATAL_ERROR "Pinned whisper.cpp model-loader allocation anchor was not found")
endif()
string(LENGTH "${WHISPER_MODEL_ALLOC_ANCHOR}" WHISPER_MODEL_ALLOC_ANCHOR_LENGTH)
math(EXPR WHISPER_FIRST_ALLOC_END "${WHISPER_FIRST_ALLOC_INDEX} + ${WHISPER_MODEL_ALLOC_ANCHOR_LENGTH}")
string(SUBSTRING "${WHISPER_CPP_SOURCE_TEXT}" 0 ${WHISPER_FIRST_ALLOC_INDEX} WHISPER_CPP_PREFIX)
string(SUBSTRING "${WHISPER_CPP_SOURCE_TEXT}" ${WHISPER_FIRST_ALLOC_END} -1 WHISPER_CPP_SUFFIX)
set(WHISPER_CPP_SOURCE_TEXT "${WHISPER_CPP_PREFIX}${WHISPER_MODEL_ALLOC_PATCH}${WHISPER_CPP_SUFFIX}")
set(WHISPER_VAD_ALLOC_ANCHOR "${WHISPER_MODEL_ALLOC_ANCHOR}")
set(WHISPER_VAD_ALLOC_PATCH "ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors_from_buft(ctx, buft);\n        if (!buf) {\n            WHISPER_LOG_ERROR(\"%s: backend tensor allocation failed; aborting VAD model load\\n\", __func__);\n            return nullptr;\n        }\n        if (buf) {\n            model.buffers.emplace_back(buf);")
string(FIND "${WHISPER_CPP_SOURCE_TEXT}" "${WHISPER_VAD_ALLOC_ANCHOR}" WHISPER_VAD_ALLOC_INDEX)
if(WHISPER_VAD_ALLOC_INDEX EQUAL -1)
    message(FATAL_ERROR "Pinned whisper.cpp VAD allocation anchor was not found")
endif()
string(LENGTH "${WHISPER_VAD_ALLOC_ANCHOR}" WHISPER_VAD_ALLOC_ANCHOR_LENGTH)
math(EXPR WHISPER_VAD_ALLOC_END "${WHISPER_VAD_ALLOC_INDEX} + ${WHISPER_VAD_ALLOC_ANCHOR_LENGTH}")
string(SUBSTRING "${WHISPER_CPP_SOURCE_TEXT}" 0 ${WHISPER_VAD_ALLOC_INDEX} WHISPER_CPP_PREFIX)
string(SUBSTRING "${WHISPER_CPP_SOURCE_TEXT}" ${WHISPER_VAD_ALLOC_END} -1 WHISPER_CPP_SUFFIX)
set(WHISPER_CPP_SOURCE_TEXT "${WHISPER_CPP_PREFIX}${WHISPER_VAD_ALLOC_PATCH}${WHISPER_CPP_SUFFIX}")
file(WRITE "${WHISPER_CPP_SOURCE}" "${WHISPER_CPP_SOURCE_TEXT}")
elseif(WHISPER_MODEL_PATCH_INDEX EQUAL -1 OR WHISPER_VAD_PATCH_INDEX EQUAL -1)
    message(FATAL_ERROR "Pinned whisper.cpp contains only part of the expected allocation-failure patch")
endif()

if(NOT DEFINED JAVA_HOME)
    message(FATAL_ERROR "JAVA_HOME must point to the JDK used for the JNI headers")
endif()
find_path(JNI_INCLUDE_DIR jni.h PATHS "${JAVA_HOME}/include" REQUIRED NO_DEFAULT_PATH)
if(WIN32)
    set(JNI_PLATFORM_INCLUDE "${JAVA_HOME}/include/win32")
elseif(APPLE)
    set(JNI_PLATFORM_INCLUDE "${JAVA_HOME}/include/darwin")
else()
    set(JNI_PLATFORM_INCLUDE "${JAVA_HOME}/include/linux")
endif()

add_library(indagium_whisper SHARED indagium_whisper.cpp)
target_include_directories(indagium_whisper PRIVATE "${JNI_INCLUDE_DIR}" "${JNI_PLATFORM_INCLUDE}")
target_link_libraries(indagium_whisper PRIVATE whisper)
target_compile_features(indagium_whisper PRIVATE cxx_std_17)
set_target_properties(indagium_whisper PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    WINDOWS_EXPORT_ALL_SYMBOLS OFF
)
if(APPLE)
    target_link_libraries(indagium_whisper PRIVATE "-framework Foundation")
elseif(UNIX)
    target_link_libraries(indagium_whisper PRIVATE Threads::Threads)
endif()
