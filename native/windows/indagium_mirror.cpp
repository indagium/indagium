#include <jni.h>
#include <jawt.h>
#define NOMINMAX
#include <jawt_md.h>
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <new>
#include <vector>

namespace {

template <typename T> void release(T*& value) {
    if (value) {
        value->Release();
        value = nullptr;
    }
}

struct MirrorSurface {
    jobject canvas = nullptr;
    HWND window = nullptr;
    ID3D11Device* device = nullptr; // Borrowed from the FFmpeg D3D11VA texture, AddRef'd.
    ID3D11DeviceContext* context = nullptr;
    IDXGISwapChain1* swapChain = nullptr;
    ID3D11VideoDevice* videoDevice = nullptr;
    ID3D11VideoContext* videoContext = nullptr;
    ID3D11VideoProcessorEnumerator* processorEnumerator = nullptr;
    ID3D11VideoProcessor* processor = nullptr;
    UINT backbufferWidth = 0;
    UINT backbufferHeight = 0;
    UINT sourceWidth = 0;
    UINT sourceHeight = 0;
    struct PendingGpuRead {
        ID3D11Query* completed = nullptr;
        ID3D11Texture2D* texture = nullptr;
    };
    std::vector<PendingGpuRead> pendingGpuReads;
};

bool canvasWindow(JNIEnv* env, jobject canvas, HWND* window, UINT* width, UINT* height) {
    JAWT awt{};
    awt.version = JAWT_VERSION_9;
    if (!JAWT_GetAWT(env, &awt)) return false;
    JAWT_DrawingSurface* drawing = awt.GetDrawingSurface(env, canvas);
    if (!drawing) return false;
    const jint lock = drawing->Lock(drawing);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
        awt.FreeDrawingSurface(drawing);
        return false;
    }
    JAWT_DrawingSurfaceInfo* info = drawing->GetDrawingSurfaceInfo(drawing);
    bool result = false;
    if (info && info->platformInfo) {
        auto* win32 = static_cast<JAWT_Win32DrawingSurfaceInfo*>(info->platformInfo);
        *window = win32->hwnd;
        RECT bounds{};
        if (*window && GetClientRect(*window, &bounds)) {
            *width = static_cast<UINT>(bounds.right - bounds.left);
            *height = static_cast<UINT>(bounds.bottom - bounds.top);
            result = true;
        }
    }
    if (info) drawing->FreeDrawingSurfaceInfo(info);
    drawing->Unlock(drawing);
    awt.FreeDrawingSurface(drawing);
    return result;
}

void destroyProcessor(MirrorSurface* mirror) {
    release(mirror->processor);
    release(mirror->processorEnumerator);
    mirror->sourceWidth = 0;
    mirror->sourceHeight = 0;
}

void destroySwapChain(MirrorSurface* mirror) {
    destroyProcessor(mirror);
    release(mirror->swapChain);
    mirror->backbufferWidth = 0;
    mirror->backbufferHeight = 0;
}

void destroyDevice(MirrorSurface* mirror) {
    destroySwapChain(mirror);
    if (mirror->context) {
        mirror->context->Flush();
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(250);
        for (auto& pending : mirror->pendingGpuReads) {
            HRESULT result = S_FALSE;
            while (result == S_FALSE && std::chrono::steady_clock::now() < deadline) {
                result = mirror->context->GetData(pending.completed, nullptr, 0, D3D11_ASYNC_GETDATA_DONOTFLUSH);
                if (result == S_FALSE) Sleep(0);
            }
            // D3D11 retains resources referenced by submitted GPU commands. The explicit COM
            // references below additionally cover normal completion and surface teardown.
            release(pending.completed);
            release(pending.texture);
        }
        mirror->pendingGpuReads.clear();
    }
    release(mirror->videoContext);
    release(mirror->videoDevice);
    release(mirror->context);
    release(mirror->device);
}

bool createSwapChain(MirrorSurface* mirror, UINT width, UINT height) {
    IDXGIDevice* dxgiDevice = nullptr;
    IDXGIAdapter* adapter = nullptr;
    IDXGIFactory2* factory = nullptr;
    HRESULT result = mirror->device->QueryInterface(__uuidof(IDXGIDevice), reinterpret_cast<void**>(&dxgiDevice));
    if (SUCCEEDED(result)) result = dxgiDevice->GetAdapter(&adapter);
    if (SUCCEEDED(result)) result = adapter->GetParent(__uuidof(IDXGIFactory2), reinterpret_cast<void**>(&factory));
    if (FAILED(result)) {
        release(factory);
        release(adapter);
        release(dxgiDevice);
        return false;
    }
    DXGI_SWAP_CHAIN_DESC1 description{};
    description.Width = width;
    description.Height = height;
    description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    description.BufferCount = 2;
    description.SwapEffect = DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL;
    description.Scaling = DXGI_SCALING_STRETCH;
    result = factory->CreateSwapChainForHwnd(mirror->device, mirror->window, &description, nullptr, nullptr, &mirror->swapChain);
    if (SUCCEEDED(result)) {
        factory->MakeWindowAssociation(mirror->window, DXGI_MWA_NO_ALT_ENTER);
        mirror->backbufferWidth = width;
        mirror->backbufferHeight = height;
    }
    release(factory);
    release(adapter);
    release(dxgiDevice);
    return SUCCEEDED(result);
}

bool ensureVideoProcessor(MirrorSurface* mirror, UINT width, UINT height) {
    if (mirror->processor && mirror->sourceWidth == width && mirror->sourceHeight == height) return true;
    destroyProcessor(mirror);
    D3D11_VIDEO_PROCESSOR_CONTENT_DESC description{};
    description.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    description.InputFrameRate = {60, 1};
    description.InputWidth = width;
    description.InputHeight = height;
    description.OutputFrameRate = {60, 1};
    description.OutputWidth = mirror->backbufferWidth;
    description.OutputHeight = mirror->backbufferHeight;
    description.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    HRESULT result = mirror->videoDevice->CreateVideoProcessorEnumerator(&description, &mirror->processorEnumerator);
    if (SUCCEEDED(result)) result = mirror->videoDevice->CreateVideoProcessor(mirror->processorEnumerator, 0, &mirror->processor);
    if (FAILED(result)) {
        destroyProcessor(mirror);
        return false;
    }
    mirror->sourceWidth = width;
    mirror->sourceHeight = height;
    return true;
}

bool waitForGpu(MirrorSurface* mirror, ID3D11Texture2D* texture) {
    D3D11_QUERY_DESC description{};
    description.Query = D3D11_QUERY_EVENT;
    ID3D11Query* completed = nullptr;
    ID3D11Device* device = nullptr;
    mirror->context->GetDevice(&device);
    if (!device) return false;
    const HRESULT created = device->CreateQuery(&description, &completed);
    release(device);
    if (FAILED(created)) return false;
    texture->AddRef();
    mirror->context->End(completed);
    mirror->context->Flush();
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(50);
    HRESULT result = S_FALSE;
    while (result == S_FALSE && std::chrono::steady_clock::now() < deadline) {
        result = mirror->context->GetData(completed, nullptr, 0, D3D11_ASYNC_GETDATA_DONOTFLUSH);
        if (result == S_FALSE) Sleep(0);
    }
    if (result == S_OK) {
        release(completed);
        texture->Release();
        return true;
    }
    if (result == S_FALSE) {
        // Keep both the completion fence and source texture alive while the GPU finishes its
        // video-processor read. Decoder submissions use this same immediate context, so later
        // reuse is ordered after the blit; the retained COM reference protects the allocation.
        mirror->pendingGpuReads.push_back({completed, texture});
        return true;
    }
    release(completed);
    texture->Release();
    return false;
}

void collectCompletedGpuReads(MirrorSurface* mirror) {
    auto it = mirror->pendingGpuReads.begin();
    while (it != mirror->pendingGpuReads.end()) {
        const HRESULT result = mirror->context->GetData(it->completed, nullptr, 0, D3D11_ASYNC_GETDATA_DONOTFLUSH);
        if (result == S_FALSE) {
            ++it;
            continue;
        }
        release(it->completed);
        release(it->texture);
        it = mirror->pendingGpuReads.erase(it);
    }
}

void fitAspectRatio(UINT sourceWidth, UINT sourceHeight, UINT targetWidth, UINT targetHeight,
                    RECT* source, RECT* destination) {
    source->left = 0;
    source->top = 0;
    source->right = static_cast<LONG>(sourceWidth);
    source->bottom = static_cast<LONG>(sourceHeight);
    const double scale = (std::min)(
        static_cast<double>(targetWidth) / sourceWidth,
        static_cast<double>(targetHeight) / sourceHeight);
    const LONG width = static_cast<LONG>(sourceWidth * scale + 0.5);
    const LONG height = static_cast<LONG>(sourceHeight * scale + 0.5);
    destination->left = (static_cast<LONG>(targetWidth) - width) / 2;
    destination->top = (static_cast<LONG>(targetHeight) - height) / 2;
    destination->right = destination->left + width;
    destination->bottom = destination->top + height;
}

// Returns 0 after presentation, 1 when the drawable is temporarily unavailable or Present
// reports DXGI_ERROR_WAS_STILL_DRAWING, and a negative value for a permanent bridge failure.
int presentTexture(MirrorSurface* mirror, ID3D11Texture2D* decodedTexture, UINT subresource,
                   UINT sourceWidth, UINT sourceHeight, UINT targetWidth, UINT targetHeight) {
    collectCompletedGpuReads(mirror);
    if (!mirror->videoDevice && FAILED(mirror->device->QueryInterface(__uuidof(ID3D11VideoDevice), reinterpret_cast<void**>(&mirror->videoDevice)))) {
        return -1;
    }
    if (!mirror->videoContext && FAILED(mirror->context->QueryInterface(__uuidof(ID3D11VideoContext), reinterpret_cast<void**>(&mirror->videoContext)))) {
        return -2;
    }
    if (!mirror->swapChain && !createSwapChain(mirror, targetWidth, targetHeight)) return -3;
    if (mirror->backbufferWidth != targetWidth || mirror->backbufferHeight != targetHeight) {
        destroyProcessor(mirror);
        if (FAILED(mirror->swapChain->ResizeBuffers(0, targetWidth, targetHeight, DXGI_FORMAT_UNKNOWN, 0))) return -4;
        mirror->backbufferWidth = targetWidth;
        mirror->backbufferHeight = targetHeight;
    }
    if (!ensureVideoProcessor(mirror, sourceWidth, sourceHeight)) return -5;

    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputDescription{};
    inputDescription.FourCC = 0;
    inputDescription.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    inputDescription.Texture2D.MipSlice = 0;
    inputDescription.Texture2D.ArraySlice = subresource;
    ID3D11VideoProcessorInputView* inputView = nullptr;
    HRESULT result = mirror->videoDevice->CreateVideoProcessorInputView(
        decodedTexture, mirror->processorEnumerator, &inputDescription, &inputView);
    if (FAILED(result)) return -6;

    ID3D11Texture2D* backbuffer = nullptr;
    result = mirror->swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&backbuffer));
    if (FAILED(result)) {
        release(inputView);
        return -7;
    }
    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDescription{};
    outputDescription.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    outputDescription.Texture2D.MipSlice = 0;
    ID3D11VideoProcessorOutputView* outputView = nullptr;
    result = mirror->videoDevice->CreateVideoProcessorOutputView(
        backbuffer, mirror->processorEnumerator, &outputDescription, &outputView);
    release(backbuffer);
    if (FAILED(result)) {
        release(inputView);
        return -8;
    }
    RECT sourceRect{};
    RECT destinationRect{};
    fitAspectRatio(sourceWidth, sourceHeight, targetWidth, targetHeight, &sourceRect, &destinationRect);
    mirror->videoContext->VideoProcessorSetStreamSourceRect(mirror->processor, 0, TRUE, &sourceRect);
    mirror->videoContext->VideoProcessorSetStreamDestRect(mirror->processor, 0, TRUE, &destinationRect);
    D3D11_VIDEO_PROCESSOR_STREAM stream{};
    stream.Enable = TRUE;
    stream.pInputSurface = inputView;
    result = mirror->videoContext->VideoProcessorBlt(mirror->processor, outputView, 0, 1, &stream);
    release(outputView);
    release(inputView);
    if (FAILED(result)) return -9;
    // The input texture belongs to FFmpeg's decoder pool. Commands are submitted on the same
    // immediate D3D11 context used by D3D11VA, so its next reuse is ordered after this blit.
    if (!waitForGpu(mirror, decodedTexture)) return -10;
    const HRESULT presentResult = mirror->swapChain->Present(0, DXGI_PRESENT_DO_NOT_WAIT);
    if (presentResult == DXGI_ERROR_WAS_STILL_DRAWING) return 1;
    return SUCCEEDED(presentResult) ? 0 : -11;
}

MirrorSurface* fromHandle(jlong handle) {
    return reinterpret_cast<MirrorSurface*>(static_cast<uintptr_t>(handle));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_indagium_capture_mirror_WindowsD3D11MirrorNative_nativeCreate(JNIEnv* env, jclass, jobject canvas) {
    auto* mirror = new (std::nothrow) MirrorSurface();
    if (!mirror) return 0;
    mirror->canvas = env->NewGlobalRef(canvas);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(mirror));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_indagium_capture_mirror_WindowsD3D11MirrorNative_nativePresent(
    JNIEnv* env, jclass, jlong handle, jlong textureAddress, jint subresource, jint width, jint height) {
    auto* mirror = fromHandle(handle);
    if (!mirror || !textureAddress || subresource < 0 || width <= 0 || height <= 0) return -1;
    HWND window = nullptr;
    UINT targetWidth = 0;
    UINT targetHeight = 0;
    if (!canvasWindow(env, mirror->canvas, &window, &targetWidth, &targetHeight)) return -2;
    if (targetWidth == 0 || targetHeight == 0) return 1;
    if (mirror->window != window) {
        destroyDevice(mirror);
        mirror->window = window;
    }
    auto* texture = reinterpret_cast<ID3D11Texture2D*>(static_cast<uintptr_t>(textureAddress));
    ID3D11Device* frameDevice = nullptr;
    texture->GetDevice(&frameDevice);
    if (!frameDevice) return -3;
    if (mirror->device != frameDevice) {
        destroyDevice(mirror);
        mirror->device = frameDevice;
        mirror->device->GetImmediateContext(&mirror->context);
    } else {
        frameDevice->Release();
    }
    if (!mirror->context) return -4;
    return presentTexture(mirror, texture, static_cast<UINT>(subresource), static_cast<UINT>(width),
                          static_cast<UINT>(height), targetWidth, targetHeight);
}

extern "C" JNIEXPORT void JNICALL
Java_com_indagium_capture_mirror_WindowsD3D11MirrorNative_nativeClose(JNIEnv* env, jclass, jlong handle) {
    auto* mirror = fromHandle(handle);
    if (!mirror) return;
    destroyDevice(mirror);
    if (mirror->canvas) env->DeleteGlobalRef(mirror->canvas);
    delete mirror;
}
