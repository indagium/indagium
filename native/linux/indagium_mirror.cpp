#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <va/va.h>
#include <va/va_drmcommon.h>
#include <libavutil/hwcontext_vaapi.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <memory>
#include <unistd.h>

namespace {

struct MirrorSurface {
    jobject canvas = nullptr;
    Display* xDisplay = nullptr; // Borrowed from JAWT; never close it.
    Window window = 0;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint program = 0;
    GLint sampler = -1;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture = nullptr;
};

bool getCanvasDrawable(JNIEnv* env, jobject canvas, Display** display, Window* window, int* width, int* height) {
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
        auto* x11 = static_cast<JAWT_X11DrawingSurfaceInfo*>(info->platformInfo);
        if (x11->display && x11->drawable) {
            XWindowAttributes attributes{};
            if (XGetWindowAttributes(x11->display, x11->drawable, &attributes)) {
                *display = x11->display;
                *window = x11->drawable;
                *width = attributes.width;
                *height = attributes.height;
                result = true;
            }
        }
    }
    if (info) drawing->FreeDrawingSurfaceInfo(info);
    drawing->Unlock(drawing);
    awt.FreeDrawingSurface(drawing);
    return result;
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool createProgram(MirrorSurface* mirror) {
    static constexpr char vertexSource[] =
        "attribute vec2 aPosition;"
        "attribute vec2 aTexCoord;"
        "varying vec2 vTexCoord;"
        "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }";
    static constexpr char fragmentSource[] =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;"
        "varying vec2 vTexCoord;"
        "uniform samplerExternalOES uTexture;"
        "void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }";
    GLuint vertex = compileShader(GL_VERTEX_SHADER, vertexSource);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (!vertex || !fragment) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return false;
    }
    mirror->program = glCreateProgram();
    glAttachShader(mirror->program, vertex);
    glAttachShader(mirror->program, fragment);
    glBindAttribLocation(mirror->program, 0, "aPosition");
    glBindAttribLocation(mirror->program, 1, "aTexCoord");
    glLinkProgram(mirror->program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint status = GL_FALSE;
    glGetProgramiv(mirror->program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        glDeleteProgram(mirror->program);
        mirror->program = 0;
        return false;
    }
    mirror->sampler = glGetUniformLocation(mirror->program, "uTexture");
    return mirror->sampler >= 0;
}

void destroyEgl(MirrorSurface* mirror) {
    if (mirror->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(mirror->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (mirror->program) glDeleteProgram(mirror->program);
        if (mirror->surface != EGL_NO_SURFACE) eglDestroySurface(mirror->display, mirror->surface);
        if (mirror->context != EGL_NO_CONTEXT) eglDestroyContext(mirror->display, mirror->context);
        eglTerminate(mirror->display);
    }
    mirror->program = 0;
    mirror->surface = EGL_NO_SURFACE;
    mirror->context = EGL_NO_CONTEXT;
    mirror->display = EGL_NO_DISPLAY;
    mirror->createImage = nullptr;
    mirror->destroyImage = nullptr;
    mirror->imageTargetTexture = nullptr;
    mirror->window = 0;
    mirror->xDisplay = nullptr;
}

bool createEgl(MirrorSurface* mirror, Display* xDisplay, Window window) {
    EGLDisplay display = eglGetDisplay(reinterpret_cast<EGLNativeDisplayType>(xDisplay));
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;
    const EGLint configAttributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &configCount) || configCount < 1) {
        eglTerminate(display);
        return false;
    }
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    EGLSurface surface = eglCreateWindowSurface(display, config, static_cast<EGLNativeWindowType>(window), nullptr);
    if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE ||
        !eglMakeCurrent(display, surface, surface, context)) {
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        return false;
    }
    auto createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    auto destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    auto imageTargetTexture = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (!createImage || !destroyImage || !imageTargetTexture) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, surface);
        eglDestroyContext(display, context);
        eglTerminate(display);
        return false;
    }
    mirror->xDisplay = xDisplay;
    mirror->window = window;
    mirror->display = display;
    mirror->context = context;
    mirror->surface = surface;
    mirror->createImage = createImage;
    mirror->destroyImage = destroyImage;
    mirror->imageTargetTexture = imageTargetTexture;
    if (!createProgram(mirror)) {
        destroyEgl(mirror);
        return false;
    }
    return true;
}

void closeDescriptor(const VADRMPRIMESurfaceDescriptor& descriptor) {
    for (uint32_t i = 0; i < descriptor.num_objects; ++i) {
        if (descriptor.objects[i].fd >= 0) close(descriptor.objects[i].fd);
    }
}

bool importVaSurface(MirrorSurface* mirror, VADisplay vaDisplay, VASurfaceID surfaceId,
                     int width, int height, int drawableWidth, int drawableHeight) {
    // FFmpeg's decode can still be queued on the VA timeline when it hands the surface to us.
    // libva requires an explicit sync before an external API reads exported surface contents.
    if (vaSyncSurface(vaDisplay, surfaceId) != VA_STATUS_SUCCESS) return false;
    VADRMPRIMESurfaceDescriptor descriptor{};
    const VAStatus exported = vaExportSurfaceHandle(
        vaDisplay,
        surfaceId,
        VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2,
        VA_EXPORT_SURFACE_READ_ONLY | VA_EXPORT_SURFACE_COMPOSED_LAYERS,
        &descriptor);
    if (exported != VA_STATUS_SUCCESS || descriptor.num_objects == 0 || descriptor.num_layers == 0) return false;
    const auto& layer = descriptor.layers[0];
    // The external OES sampler path expects a multi-plane YUV surface (normally NV12). A
    // separate single-plane Y export would appear grayscale, so reject it and let the caller
    // switch to the software decoder instead.
    if (layer.num_planes < 2 || layer.num_planes > 4) {
        closeDescriptor(descriptor);
        return false;
    }
    std::array<EGLint, 48> attributes{};
    size_t at = 0;
    auto add = [&](EGLint key, EGLint value) { attributes[at++] = key; attributes[at++] = value; };
    add(EGL_WIDTH, width);
    add(EGL_HEIGHT, height);
    add(EGL_LINUX_DRM_FOURCC_EXT, static_cast<EGLint>(layer.drm_format));
    const std::array<EGLint, 4> fdKeys = {
        EGL_DMA_BUF_PLANE0_FD_EXT, EGL_DMA_BUF_PLANE1_FD_EXT,
        EGL_DMA_BUF_PLANE2_FD_EXT, EGL_DMA_BUF_PLANE3_FD_EXT,
    };
    const std::array<EGLint, 4> offsetKeys = {
        EGL_DMA_BUF_PLANE0_OFFSET_EXT, EGL_DMA_BUF_PLANE1_OFFSET_EXT,
        EGL_DMA_BUF_PLANE2_OFFSET_EXT, EGL_DMA_BUF_PLANE3_OFFSET_EXT,
    };
    const std::array<EGLint, 4> pitchKeys = {
        EGL_DMA_BUF_PLANE0_PITCH_EXT, EGL_DMA_BUF_PLANE1_PITCH_EXT,
        EGL_DMA_BUF_PLANE2_PITCH_EXT, EGL_DMA_BUF_PLANE3_PITCH_EXT,
    };
    const std::array<EGLint, 4> modifierLoKeys = {
        EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT,
        EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_LO_EXT,
    };
    const std::array<EGLint, 4> modifierHiKeys = {
        EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT,
        EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT, EGL_DMA_BUF_PLANE3_MODIFIER_HI_EXT,
    };
    for (uint32_t plane = 0; plane < layer.num_planes; ++plane) {
        const uint32_t objectIndex = layer.object_index[plane];
        if (objectIndex >= descriptor.num_objects) {
            closeDescriptor(descriptor);
            return false;
        }
        add(fdKeys[plane], descriptor.objects[objectIndex].fd);
        add(offsetKeys[plane], static_cast<EGLint>(layer.offset[plane]));
        add(pitchKeys[plane], static_cast<EGLint>(layer.pitch[plane]));
        const uint64_t modifier = descriptor.objects[objectIndex].drm_format_modifier;
        add(modifierLoKeys[plane], static_cast<EGLint>(modifier & 0xffffffffu));
        add(modifierHiKeys[plane], static_cast<EGLint>(modifier >> 32u));
    }
    attributes[at] = EGL_NONE;
    EGLImageKHR image = mirror->createImage(
        mirror->display, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, nullptr, attributes.data());
    closeDescriptor(descriptor);
    if (image == EGL_NO_IMAGE_KHR) return false;

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    mirror->imageTargetTexture(GL_TEXTURE_EXTERNAL_OES, image);
    EGLint surfaceWidth = 0;
    EGLint surfaceHeight = 0;
    if (!eglQuerySurface(mirror->display, mirror->surface, EGL_WIDTH, &surfaceWidth) ||
        !eglQuerySurface(mirror->display, mirror->surface, EGL_HEIGHT, &surfaceHeight) ||
        surfaceWidth <= 0 || surfaceHeight <= 0) {
        glDeleteTextures(1, &texture);
        mirror->destroyImage(mirror->display, image);
        return false;
    }
    // X11's drawable attributes and EGL surface dimensions should track the Swing Canvas size;
    // use EGL's actual drawable extent for the viewport and keep the decoded frame letterboxed.
    if (drawableWidth <= 0 || drawableHeight <= 0) {
        glDeleteTextures(1, &texture);
        mirror->destroyImage(mirror->display, image);
        return false;
    }
    const float targetAspect = static_cast<float>(surfaceWidth) / static_cast<float>(surfaceHeight);
    const float sourceAspect = static_cast<float>(width) / static_cast<float>(height);
    const float xScale = sourceAspect < targetAspect ? sourceAspect / targetAspect : 1.0f;
    const float yScale = sourceAspect > targetAspect ? targetAspect / sourceAspect : 1.0f;
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(mirror->program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
    glUniform1i(mirror->sampler, 0);
    GLfloat fittedVertices[] = {
        -xScale, -yScale, 0.0f, 1.0f,
         xScale, -yScale, 1.0f, 1.0f,
        -xScale,  yScale, 0.0f, 0.0f,
         xScale,  yScale, 1.0f, 0.0f,
    };
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), fittedVertices);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), fittedVertices + 2);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glFinish(); // Do not return the VAAPI surface to FFmpeg until EGL has finished sampling it.
    const bool rendered = glGetError() == GL_NO_ERROR && eglSwapBuffers(mirror->display, mirror->surface);
    glDeleteTextures(1, &texture);
    mirror->destroyImage(mirror->display, image);
    return rendered;
}

MirrorSurface* fromHandle(jlong handle) {
    return reinterpret_cast<MirrorSurface*>(static_cast<uintptr_t>(handle));
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_indagium_capture_mirror_LinuxVaapiEglMirrorNative_nativeCreate(JNIEnv* env, jclass, jobject canvas) {
    auto* mirror = new MirrorSurface();
    mirror->canvas = env->NewGlobalRef(canvas);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(mirror));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_indagium_capture_mirror_LinuxVaapiEglMirrorNative_nativePresent(
    JNIEnv* env, jclass, jlong handle, jlong deviceContextAddress, jlong surfaceAddress, jint width, jint height) {
    auto* mirror = fromHandle(handle);
    if (!mirror || !deviceContextAddress || !surfaceAddress || width <= 0 || height <= 0) return -1;
    Display* xDisplay = nullptr;
    Window window = 0;
    int drawableWidth = 0;
    int drawableHeight = 0;
    if (!getCanvasDrawable(env, mirror->canvas, &xDisplay, &window, &drawableWidth, &drawableHeight)) return -2;
    if (drawableWidth <= 0 || drawableHeight <= 0) return 1;
    if (mirror->display == EGL_NO_DISPLAY || mirror->xDisplay != xDisplay || mirror->window != window) {
        destroyEgl(mirror);
        if (!createEgl(mirror, xDisplay, window)) return -3;
    }
    if (!eglMakeCurrent(mirror->display, mirror->surface, mirror->surface, mirror->context)) return -4;
    auto* vaapi = reinterpret_cast<AVVAAPIDeviceContext*>(static_cast<uintptr_t>(deviceContextAddress));
    const VADisplay vaDisplay = vaapi->display;
    if (!vaDisplay) return -5;
    const VASurfaceID surfaceId = static_cast<VASurfaceID>(static_cast<uintptr_t>(surfaceAddress));
    return importVaSurface(mirror, vaDisplay, surfaceId, width, height, drawableWidth, drawableHeight) ? 0 : -6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_indagium_capture_mirror_LinuxVaapiEglMirrorNative_nativeClose(JNIEnv* env, jclass, jlong handle) {
    auto* mirror = fromHandle(handle);
    if (!mirror) return;
    destroyEgl(mirror);
    if (mirror->canvas) env->DeleteGlobalRef(mirror->canvas);
    delete mirror;
}
