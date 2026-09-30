#import <AppKit/AppKit.h>
#import <QuartzCore/CATransaction.h>
#import <QuartzCore/CAShapeLayer.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>
#import <VideoToolbox/VideoToolbox.h>
#import <jawt.h>
#import <jawt_md.h>
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

struct Nalu { std::vector<uint8_t> bytes; uint8_t type; };
struct Mirror;
struct DecoderContext { Mirror *mirror; uint64_t generation; };

struct LayerFrameSnapshot {
    std::mutex lock;
    CGRect layerFrame = CGRectZero;
    CGRect parentFrame = CGRectZero;
    bool samplePending = false;
};

// AWT hierarchy events must not wait synchronously for AppKit to detach a layer. Generation
// numbers let the main-queue detach become a no-op when a newer host has already attached it.
struct LayerAttachmentState {
    std::mutex lock;
    uint64_t generation = 0;
    bool closed = false;
};

// Resize notifications arrive on the AWT event-dispatch thread, while CAMetalLayer geometry must
// be changed on AppKit's main queue. Keep their shared state independent from Mirror so a queued
// AppKit block can safely outlive nativeClose without dereferencing a deleted Mirror.
struct PendingGeometryUpdate {
    std::mutex lock;
    uint64_t generation = 0;
    bool scheduled = false;
    bool closed = false;
    CGFloat width = 0.0;
    CGFloat height = 0.0;
    CGFloat pixelWidth = 0.0;
    CGFloat pixelHeight = 0.0;
    CGFloat clipLeft = 0.0;
    CGFloat clipTop = 0.0;
    CGFloat clipRight = 1.0;
    CGFloat clipBottom = 1.0;
    // Where the Canvas sits in its window, in the same terms nativeAttach receives. With it the
    // geometry pass re-asserts the layer frame instead of trusting JAWT to have done so: after a
    // detach/return round trip JAWT can apply the new peer's bounds before our layer is back in
    // its tree, and nothing reapplies them — the layer then stays 0x0 (or at the previous
    // window's size) and the mirror is black while decoding and touches keep working.
    bool hasOrigin = false;
    CGFloat windowX = 0.0;
    CGFloat windowY = 0.0;
    CGFloat insetLeft = 0.0;
    CGFloat insetTop = 0.0;
};

template <typename T> struct CfOwner {
    T value = nullptr;
    ~CfOwner() { if (value) CFRelease(value); }
    T *out() { return &value; }
    operator T() const { return value; }
};

struct Mirror {
    jobject canvas = nullptr;
    CAMetalLayer *layer = nil;
    CAShapeLayer *clipMask = nil;
    id<MTLDevice> device = nil;
    id<MTLCommandQueue> commands = nil;
    id<MTLRenderPipelineState> renderPipeline = nil;
    CVMetalTextureCacheRef textureCache = nullptr;
    CMVideoFormatDescriptionRef format = nullptr;
    VTDecompressionSessionRef decoder = nullptr;
    DecoderContext *decoderContext = nullptr;
    CVPixelBufferRef latest = nullptr;
    // The frame most recently put on screen. `latest` is consumed by a successful present, so without
    // this a layer that is re-attached (detach/return, a closed overlay) or resized has nothing to
    // draw until the device sends another frame — and a still Android screen may send none for a
    // long time, leaving the mirror black. See replayLastPresentedLocked.
    CVPixelBufferRef lastPresented = nullptr;
    dispatch_queue_t renderQueue = nullptr;
    std::mutex lock;
    std::condition_variable decodeCompleted;
    std::shared_ptr<LayerFrameSnapshot> layerFrameSnapshot = std::make_shared<LayerFrameSnapshot>();
    std::shared_ptr<LayerAttachmentState> layerAttachment = std::make_shared<LayerAttachmentState>();
    std::shared_ptr<PendingGeometryUpdate> pendingGeometry = std::make_shared<PendingGeometryUpdate>();
    bool renderScheduled = false;
    bool decodeInFlight = false;
    bool failed = false;
    bool closed = false;
    bool testPattern = false;
    bool underlayOrdering = false;
    int failureCode = 0;
    OSStatus failureStatus = noErr;
    int width = 0;
    int height = 0;
    uint64_t decoderGeneration = 0;
    int64_t decodeIngressNs = 0;
    int64_t latestIngressNs = 0;
    int64_t decodeCount = 0;
    int64_t decodeLatencyTotalNs = 0;
    int64_t decodeLatencyMaxNs = 0;
    int64_t decodedImageCount = 0;
    int64_t presentCount = 0;
    int64_t presentAgeTotalNs = 0;
    int64_t presentAgeMaxNs = 0;
    int64_t presentAgeHistogram[26]{};
    int64_t renderErrors = 0;
    int64_t attachAttempts = 0;
    int64_t attachSuccesses = 0;
    int64_t attachFailures = 0;
    int64_t drawableMisses = 0;
    int64_t lastAttachResult = 0; // 1=attached, 2=JAWT, 3=drawing surface, 4=lock, 5=surface info, 6=platform layers
    int64_t lastAttachWidth = 0;
    int64_t lastAttachHeight = 0;
    bool pixelSampled = false;
    int64_t decodedCallbackCount = 0;
    int64_t decodedPixelFormat = 0;
    int64_t decodedSampleMin = -1;
    int64_t decodedSampleMax = -1;
    int64_t decodedAlphaMin = -1;
    int64_t decodedAlphaMax = -1;
    int64_t lastLayerHasSuperlayer = 0;
    int64_t lastWindowLayerAvailable = 0;
    int64_t lastLayerHidden = 0;
    int64_t lastLayerOpacityMilli = 0;
    int64_t lastLayerDescendsFromWindow = 0;
    int64_t lastParentHidden = 0;
    int64_t lastParentOpacityMilli = 0;
    int64_t lastLayerZMilli = 0;
    int64_t lastLayerSiblingIndex = -1;
    int64_t lastLayerSiblingCount = 0;
    int64_t lastLayerSiblingMaxZMilli = 0;
    int64_t lastLayerFrameWidth = 0;
    int64_t lastLayerFrameHeight = 0;
    int64_t lastLayerFrameX = 0;
    int64_t lastLayerFrameY = 0;
    int64_t lastComponentBoundsX = 0;
    int64_t lastComponentBoundsY = 0;
    int64_t lastCanvasWindowX = 0;
    int64_t lastCanvasWindowY = 0;
    int64_t lastParentFrameWidth = 0;
    int64_t lastParentFrameHeight = 0;
    bool drawableReadbackDone = false;
    int64_t drawableReadbackStatus = 0;
    int64_t drawablePixelMin = -1;
    int64_t drawablePixelMax = -1;
    int64_t drawableNonBlackGridSamples = -1;
    int64_t drawableAlphaMin = -1;
    int64_t drawableAlphaMax = -1;
    CGFloat clipLeft = 0.0;
    CGFloat clipTop = 0.0;
    CGFloat clipRight = 1.0;
    CGFloat clipBottom = 1.0;
    std::vector<uint8_t> stagedSps;
    std::vector<uint8_t> stagedPps;
    std::vector<uint8_t> activeSps;
    std::vector<uint8_t> activePps;
};

static int64_t steadyNowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** Turns off Core Animation's implicit animations for [layer]. Our own updates already run inside
 *  setDisableActions transactions, but JAWT's frame writes and anything else that touches the layer
 *  do not, so each window resize animated the picture to its new frame over the default 0.25 s:
 *  it visibly trailed the window, and the transparent hole Compose had already moved showed the
 *  black window background beside it. With no actions every change lands in the frame it is made. */
static void disableImplicitAnimations(CALayer *layer) {
    if (!layer) return;
    NSNull *none = [NSNull null];
    layer.actions = @{
        @"position": none, @"bounds": none, @"frame": none, @"anchorPoint": none,
        @"contents": none, @"contentsScale": none, @"contentsRect": none,
        @"zPosition": none, @"hidden": none, @"opacity": none, @"mask": none,
        @"path": none, @"sublayers": none, @"onOrderIn": none, @"onOrderOut": none,
    };
}

static void updateDrawableGeometry(CAMetalLayer *layer, CGFloat width, CGFloat height, CGFloat pixelWidth, CGFloat pixelHeight) {
    if (!layer || width <= 0 || height <= 0 || pixelWidth <= 0 || pixelHeight <= 0) return;
    // JAWT_SurfaceLayers owns the CALayer frame and positions it over the AWT Component bounds.
    // Only the drawable's backing-pixel size and scale belong to the Metal renderer.
    layer.contentsScale = std::max(pixelWidth / width, pixelHeight / height);
    layer.drawableSize = CGSizeMake(pixelWidth, pixelHeight);
}

static CGRect layerClipMaskRect(CGRect bounds, CGFloat left, CGFloat top, CGFloat right, CGFloat bottom) {
    const CGFloat width = std::max<CGFloat>(0.0, bounds.size.width);
    const CGFloat height = std::max<CGFloat>(0.0, bounds.size.height);
    left = std::clamp(left, 0.0, 1.0);
    top = std::clamp(top, 0.0, 1.0);
    right = std::clamp(right, 0.0, 1.0);
    bottom = std::clamp(bottom, 0.0, 1.0);
    if (width <= 0 || height <= 0 || right <= left || bottom <= top) return CGRectZero;
    // Compose positions use a top-left origin; Core Animation mask paths use bottom-left.
    return CGRectMake(
        left * width,
        (1.0 - bottom) * height,
        (right - left) * width,
        (bottom - top) * height);
}

static void updateLayerClipMask(
    CAMetalLayer *layer,
    CAShapeLayer *mask,
    CGFloat left,
    CGFloat top,
    CGFloat right,
    CGFloat bottom) {
    if (!layer || !mask) return;
    const CGRect layerBounds = layer.bounds;
    const CGSize size = layerBounds.size;
    mask.bounds = CGRectMake(0, 0, size.width, size.height);
    mask.position = CGPointMake(CGRectGetMidX(layerBounds), CGRectGetMidY(layerBounds));
    mask.fillColor = NSColor.whiteColor.CGColor;
    mask.strokeColor = nil;
    CGMutablePathRef path = CGPathCreateMutable();
    const CGRect visibleRect = layerClipMaskRect(CGRectMake(0, 0, size.width, size.height), left, top, right, bottom);
    if (visibleRect.size.width > 0 && visibleRect.size.height > 0) {
        CGPathAddRect(path, nullptr, visibleRect);
    }
    mask.path = path;
    CGPathRelease(path);
    layer.mask = mask;
}

static CGRect initialLayerFrame(
    CGFloat windowHeight,
    CGFloat topLevelX,
    CGFloat topLevelY,
    CGFloat insetLeft,
    CGFloat insetTop,
    CGFloat width,
    CGFloat height) {
    const CGFloat contentX = topLevelX - insetLeft;
    const CGFloat contentY = topLevelY - insetTop;
    return CGRectMake(contentX, windowHeight - contentY - height, width, height);
}

static void schedulePendingGeometryUpdate(
    const std::shared_ptr<PendingGeometryUpdate> &pending,
    CAMetalLayer *layer,
    CAShapeLayer *clipMask) {
    if (!pending || !layer || !clipMask) return;
    // Blocks capture C++ reference variables as references. `pending` is a reference parameter,
    // so using it directly from this asynchronous block leaves the block pointing at this stack
    // slot after schedulePendingGeometryUpdate returns. Keep an owning value in the block instead:
    // nativeClose may destroy Mirror before this runs, but PendingGeometryUpdate remains alive and
    // its closed bit makes the queued work a harmless no-op.
    const std::shared_ptr<PendingGeometryUpdate> retainedPending = pending;
    dispatch_async(dispatch_get_main_queue(), ^{
        CGFloat width = 0.0, height = 0.0, pixelWidth = 0.0, pixelHeight = 0.0;
        CGFloat clipLeft = 0.0, clipTop = 0.0, clipRight = 1.0, clipBottom = 1.0;
        bool hasOrigin = false;
        CGFloat windowX = 0.0, windowY = 0.0, insetLeft = 0.0, insetTop = 0.0;
        uint64_t appliedGeneration = 0;
        {
            std::lock_guard<std::mutex> guard(retainedPending->lock);
            if (retainedPending->closed) {
                retainedPending->scheduled = false;
                return;
            }
            width = retainedPending->width;
            height = retainedPending->height;
            pixelWidth = retainedPending->pixelWidth;
            pixelHeight = retainedPending->pixelHeight;
            clipLeft = retainedPending->clipLeft;
            clipTop = retainedPending->clipTop;
            clipRight = retainedPending->clipRight;
            clipBottom = retainedPending->clipBottom;
            hasOrigin = retainedPending->hasOrigin;
            windowX = retainedPending->windowX;
            windowY = retainedPending->windowY;
            insetLeft = retainedPending->insetLeft;
            insetTop = retainedPending->insetTop;
            appliedGeneration = retainedPending->generation;
        }
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        // Frame first: the clip mask below is computed from the layer's bounds.
        CALayer *parentLayer = layer.superlayer;
        if (hasOrigin && parentLayer && parentLayer.bounds.size.height > 0 && width > 0 && height > 0) {
            const CGRect frame = initialLayerFrame(
                parentLayer.bounds.size.height, windowX, windowY, insetLeft, insetTop, width, height);
            if (!CGRectEqualToRect(layer.frame, frame)) layer.frame = frame;
        }
        updateDrawableGeometry(layer, width, height, pixelWidth, pixelHeight);
        updateLayerClipMask(layer, clipMask, clipLeft, clipTop, clipRight, clipBottom);
        [CATransaction commit];

        bool needsAnotherPass = false;
        {
            std::lock_guard<std::mutex> guard(retainedPending->lock);
            if (retainedPending->closed) {
                retainedPending->scheduled = false;
            } else if (retainedPending->generation == appliedGeneration) {
                retainedPending->scheduled = false;
            } else {
                // A resize landed after this main-queue pass sampled the state. Queue one more
                // pass; all intervening resize events remain coalesced in the latest generation.
                needsAnotherPass = true;
            }
        }
        if (needsAnotherPass) schedulePendingGeometryUpdate(retainedPending, layer, clipMask);
    });
}

// JAWT's macOS SurfaceLayers implementation adds layers directly to an AppKit-hosted layer tree.
// OpenJDK marshals its own bounds changes to the main queue; do the same for attach/resize so the
// host CALayer tree is only mutated on AppKit's main thread.
static void performOnAppKitMainThreadSync(dispatch_block_t block) {
    if (!block) return;
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static void detachLayerFromTree(CAMetalLayer *layer) {
    if (!layer) return;
    if ([NSThread isMainThread]) {
        [layer removeFromSuperlayer];
    } else {
        // Retain the layer until AppKit removes the JAWT overlay. Do not reacquire a drawing
        // surface here: Compose may already have disposed the Canvas peer before close runs.
        __strong CAMetalLayer *retainedLayer = layer;
        dispatch_async(dispatch_get_main_queue(), ^{ [retainedLayer removeFromSuperlayer]; });
    }
}

static uint64_t nextLayerAttachmentGeneration(const std::shared_ptr<LayerAttachmentState> &state) {
    if (!state) return 0;
    std::lock_guard<std::mutex> guard(state->lock);
    if (state->closed) return 0;
    return ++state->generation;
}

static bool isCurrentLayerAttachmentGeneration(
    const std::shared_ptr<LayerAttachmentState> &state,
    uint64_t generation) {
    if (!state || generation == 0) return false;
    std::lock_guard<std::mutex> guard(state->lock);
    return !state->closed && state->generation == generation;
}

static void detachLayerFromTreeWhenCurrent(
    CAMetalLayer *layer,
    const std::shared_ptr<LayerAttachmentState> &state,
    uint64_t generation) {
    if (!layer || !state || generation == 0) return;
    __strong CAMetalLayer *retainedLayer = layer;
    const std::shared_ptr<LayerAttachmentState> retainedState = state;
    dispatch_async(dispatch_get_main_queue(), ^{
        if (isCurrentLayerAttachmentGeneration(retainedState, generation)) {
            [retainedLayer removeFromSuperlayer];
        }
    });
}

static void closeLayerAttachmentState(const std::shared_ptr<LayerAttachmentState> &state) {
    if (!state) return;
    std::lock_guard<std::mutex> guard(state->lock);
    state->closed = true;
    ++state->generation;
}

static void sampleDrawableGrid(
    const uint8_t *pixels,
    size_t width,
    size_t height,
    size_t bytesPerRow,
    int64_t *pixelMin,
    int64_t *pixelMax,
    int64_t *nonBlackSamples,
    int64_t *alphaMin,
    int64_t *alphaMax) {
    if (!pixels || width == 0 || height == 0 || bytesPerRow < width * 4 ||
        !pixelMin || !pixelMax || !nonBlackSamples || !alphaMin || !alphaMax) return;
    *pixelMin = 255;
    *pixelMax = 0;
    *nonBlackSamples = 0;
    *alphaMin = 255;
    *alphaMax = 0;
    for (size_t gy = 0; gy < 9; ++gy) {
        for (size_t gx = 0; gx < 9; ++gx) {
            const size_t x = gx * (width - 1) / 8;
            const size_t y = gy * (height - 1) / 8;
            const uint8_t *sample = pixels + y * bytesPerRow + x * 4;
            uint8_t sampleMax = 0;
            for (size_t channel = 0; channel < 3; ++channel) {
                *pixelMin = std::min<int64_t>(*pixelMin, sample[channel]);
                *pixelMax = std::max<int64_t>(*pixelMax, sample[channel]);
                sampleMax = std::max(sampleMax, sample[channel]);
            }
            *alphaMin = std::min<int64_t>(*alphaMin, sample[3]);
            *alphaMax = std::max<int64_t>(*alphaMax, sample[3]);
            if (sampleMax > 16) ++*nonBlackSamples;
        }
    }
}

static CGFloat mirrorLayerZPosition(
    bool underlayOrdering,
    bool testPattern,
    bool hasOtherSibling,
    CGFloat siblingMinZ,
    CGFloat siblingMaxZ) {
    if (testPattern) return 1000.0;
    if (underlayOrdering) return hasOtherSibling ? siblingMinZ - 1.0 : -1.0;
    const CGFloat aboveSiblingsZ = hasOtherSibling ? siblingMaxZ + 1.0 : 1.0;
    return std::max(aboveSiblingsZ, 1.0);
}

/** True when [layer] has a strictly lower zPosition than every other sibling in [siblings]. Trivially
 *  true when there are no other siblings — pair with a sibling-exists check (nativeUnderlayStatus's
 *  bit2) for a meaningful "genuinely below something" signal. */
static bool layerIsBelowAllSiblings(CAMetalLayer *layer, NSArray<CALayer *> *siblings) {
    if (!layer || !siblings) return false;
    for (CALayer *sibling in siblings) {
        if (sibling == layer) continue;
        if (layer.zPosition >= sibling.zPosition) return false;
    }
    return true;
}

/** True when [layer] has at least one other sibling and none of them is opaque. Deliberately
 *  independent of the siblings' z-order relative to ours: the underlay decision must be answerable
 *  while the fallback has our layer ABOVE its siblings, or a fallback could never upgrade again
 *  (e.g. our attach won the race against skiko's layer and saw no sibling on the first sample).
 *  An opaque sibling anywhere is treated as "not supported" — conservative, and what
 *  -Dcompose.interop.blending=false produces (skiko's layer turns opaque). */
static bool otherSiblingsAreTransparent(CAMetalLayer *layer, NSArray<CALayer *> *siblings) {
    if (!layer || !siblings) return false;
    bool hasOtherSibling = false;
    for (CALayer *sibling in siblings) {
        if (sibling == layer) continue;
        hasOtherSibling = true;
        if (sibling.opaque) return false;
    }
    return hasOtherSibling;
}

static bool needsInitialLayerFrame(CALayer *previousParent, CALayer *targetWindowLayer) {
    return previousParent != targetWindowLayer;
}

static void setInitialLayerFrame(
    CAMetalLayer *layer,
    CGFloat windowHeight,
    CGFloat topLevelX,
    CGFloat topLevelY,
    CGFloat insetLeft,
    CGFloat insetTop,
    CGFloat width,
    CGFloat height) {
    if (!layer || windowHeight <= 0 || width <= 0 || height <= 0) return;
    const CGRect frame = initialLayerFrame(windowHeight, topLevelX, topLevelY, insetLeft, insetTop, width, height);
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    layer.frame = frame;
    [CATransaction commit];
}

static void readCurrentLayerFrames(
    CAMetalLayer *layer,
    const std::shared_ptr<LayerFrameSnapshot> &snapshot,
    CGRect *layerFrame,
    CGRect *parentFrame) {
    if (!layer || !layerFrame || !parentFrame) return;
    if (!snapshot) return;

    if ([NSThread isMainThread]) {
        std::lock_guard<std::mutex> guard(snapshot->lock);
        snapshot->layerFrame = layer.frame;
        snapshot->parentFrame = layer.superlayer ? layer.superlayer.frame : CGRectZero;
    } else {
        bool schedule = false;
        {
            std::lock_guard<std::mutex> guard(snapshot->lock);
            if (!snapshot->samplePending) {
                snapshot->samplePending = true;
                schedule = true;
            }
        }
        if (schedule) {
            // AWT applies JAWT layer bounds asynchronously on AppKit's main queue. Sample there
            // without blocking the decoder/metrics thread; waiting synchronously could deadlock
            // with EDT close while the Kotlin lifecycle read lock is held.
            __strong CAMetalLayer *retainedLayer = layer;
            std::shared_ptr<LayerFrameSnapshot> retainedSnapshot = snapshot;
            dispatch_async(dispatch_get_main_queue(), ^{
                const CGRect sampledLayerFrame = retainedLayer.frame;
                const CGRect sampledParentFrame = retainedLayer.superlayer ? retainedLayer.superlayer.frame : CGRectZero;
                std::lock_guard<std::mutex> guard(retainedSnapshot->lock);
                retainedSnapshot->layerFrame = sampledLayerFrame;
                retainedSnapshot->parentFrame = sampledParentFrame;
                retainedSnapshot->samplePending = false;
            });
        }
    }

    std::lock_guard<std::mutex> guard(snapshot->lock);
    *layerFrame = snapshot->layerFrame;
    *parentFrame = snapshot->parentFrame;
}

static NSView *findViewForLayer(NSView *root, CALayer *targetLayer, NSUInteger depth) {
    if (!root || !targetLayer || depth > 12) return nil;
    if (root.layer == targetLayer) return root;
    for (NSView *child in root.subviews) {
        NSView *match = findViewForLayer(child, targetLayer, depth + 1);
        if (match) return match;
    }
    return nil;
}

static NSString *describeAppKitViewHierarchy(CALayer *windowLayer, CAMetalLayer *metalLayer) {
    NSMutableString *description = [NSMutableString stringWithCapacity:1024];
    id delegate = windowLayer.delegate;
    NSView *hostView = [delegate isKindOfClass:[NSView class]] ? (NSView *)delegate : nil;
    bool hostFoundByTreeWalk = false;
    if (!hostView && NSApp) {
        NSUInteger searchedWindows = 0;
        for (NSWindow *candidateWindow in NSApp.windows) {
            if (++searchedWindows > 16) break;
            hostView = findViewForLayer(candidateWindow.contentView, windowLayer, 0);
            if (hostView) {
                hostFoundByTreeWalk = true;
                break;
            }
        }
    }
    [description appendFormat:@"window_layer=%@ bounds=%@ delegate=%@",
        NSStringFromClass([windowLayer class]), NSStringFromRect(windowLayer.bounds),
        delegate ? NSStringFromClass([delegate class]) : @"nil"];
    if (!hostView) {
        [description appendString:@" host_view=not-found-in-appkit-windows"];
        return description;
    }

    NSWindow *window = hostView.window;
    NSView *contentView = window.contentView;
    [description appendFormat:@" host_lookup=%@ window=%p host_is_content=%d content=%@",
        hostFoundByTreeWalk ? @"window-tree" : @"delegate",
        (__bridge void *)window, hostView == contentView,
        contentView ? NSStringFromClass([contentView class]) : @"nil"];
    NSView *view = hostView;
    for (NSUInteger depth = 0; view && depth < 12; ++depth) {
        NSView *parent = view.superview;
        NSInteger siblingIndex = parent ? [parent.subviews indexOfObjectIdenticalTo:view] : -1;
        [description appendFormat:@" | v%lu=%@ idx=%ld/%lu frame=%@ bounds=%@ hidden=%d alpha=%.2f opaque=%d visible=%@ layer=%@ awtLayer=%d",
            (unsigned long)depth,
            NSStringFromClass([view class]),
            (long)siblingIndex,
            (unsigned long)(parent ? parent.subviews.count : 0),
            NSStringFromRect(view.frame),
            NSStringFromRect(view.bounds),
            view.hidden,
            view.alphaValue,
            view.opaque,
            NSStringFromRect(view.visibleRect),
            view.wantsLayer && view.layer ? NSStringFromClass([view.layer class]) : @"none",
            view.layer == windowLayer];
        if (view == contentView) break;
        if (!parent) break;
        [description appendString:@" siblings={"];
        for (NSUInteger index = 0; index < parent.subviews.count; ++index) {
            NSView *sibling = parent.subviews[index];
            [description appendFormat:@"%s%lu:%@:%@:%d:%.2f",
                index == 0 ? "" : ",",
                (unsigned long)index,
                NSStringFromClass([sibling class]),
                NSStringFromRect(sibling.frame),
                sibling.hidden,
                sibling.alphaValue];
        }
        [description appendString:@"}"];
        view = parent;
    }
    if (metalLayer) {
        CALayer *presentationLayer = metalLayer.presentationLayer;
        [description appendFormat:@" | metal_parent=%@ metal_frame=%@ metal_z=%.3f presentation=%@ presentation_parent=%@",
            metalLayer.superlayer ? NSStringFromClass([metalLayer.superlayer class]) : @"nil",
            NSStringFromRect(metalLayer.frame),
            metalLayer.zPosition,
            presentationLayer ? NSStringFromClass([presentationLayer class]) : @"nil",
            presentationLayer.superlayer ? NSStringFromClass([presentationLayer.superlayer class]) : @"nil"];
        CALayer *parentLayer = metalLayer.superlayer;
        if (parentLayer.sublayers.count > 0) {
            [description appendString:@" metal_siblings={"];
            for (NSUInteger index = 0; index < parentLayer.sublayers.count; ++index) {
                CALayer *sibling = parentLayer.sublayers[index];
                id siblingDelegate = sibling.delegate;
                // opaque= decides whether a transparent hole Compose clears in its own layer can
                // reveal the mirror beneath: an opaque sibling above it hides it regardless.
                [description appendFormat:@"%s%lu:%@ z=%.3f frame=%@ hidden=%d opacity=%.2f opaque=%d delegate=%@",
                    index == 0 ? "" : ",",
                    (unsigned long)index,
                    NSStringFromClass([sibling class]),
                    sibling.zPosition,
                    NSStringFromRect(sibling.frame),
                    sibling.hidden,
                    sibling.opacity,
                    sibling.opaque,
                    siblingDelegate ? NSStringFromClass([siblingDelegate class]) : @"nil"];
            }
            [description appendString:@"}"];
        }
    }
    return description;
}

static jlong failureValue(int code, OSStatus status = noErr) {
    int64_t wideStatus = status;
    uint32_t codeValue = (uint32_t)(wideStatus < 0 ? -wideStatus : wideStatus);
    return -(((jlong)code << 32) | codeValue);
}

static void setFailureLocked(Mirror *mirror, int code, OSStatus status = noErr) {
    mirror->failed = true;
    mirror->failureCode = code;
    mirror->failureStatus = status;
}

static std::vector<Nalu> parseAnnexB(const uint8_t *data, size_t count) {
    std::vector<Nalu> result;
    auto startCodeAt = [&](size_t at) {
        return at + 3 < count && data[at] == 0 && data[at + 1] == 0 &&
            (data[at + 2] == 1 || (data[at + 2] == 0 && data[at + 3] == 1));
    };
    size_t cursor = 0;
    while (cursor + 3 < count) {
        while (cursor + 3 < count && !startCodeAt(cursor)) ++cursor;
        if (cursor + 3 >= count) break;
        size_t payload = cursor + (data[cursor + 2] == 1 ? 3 : 4);
        size_t end = payload;
        while (end + 3 < count && !startCodeAt(end)) ++end;
        if (end + 3 >= count) end = count;
        while (end > payload && data[end - 1] == 0) --end; // Annex-B trailing_zero_8bits.
        if (payload < end) result.push_back({std::vector<uint8_t>(data + payload, data + end), uint8_t(data[payload] & 0x1f)});
        cursor = end;
    }
    return result;
}

/** Queues the last presented frame again when nothing newer is pending. Caller holds mirror->lock.
 *  A replay carries ingress 0 so it stays out of the latency metrics: its age measures how long the
 *  screen was still, not how late the pipeline was. */
static void replayLastPresentedLocked(Mirror *mirror) {
    if (mirror->closed || mirror->latest || !mirror->lastPresented) return;
    mirror->latest = (CVPixelBufferRef)CFRetain(mirror->lastPresented);
    mirror->latestIngressNs = 0;
}

static void renderLoop(Mirror *mirror) {
    dispatch_async(mirror->renderQueue, ^{
        while (true) {
            CVPixelBufferRef pixel = nullptr;
            CAMetalLayer *layer = nil;
            int64_t ingressNs = 0;
            {
                std::lock_guard<std::mutex> guard(mirror->lock);
                if (mirror->closed || !mirror->latest || !mirror->layer) {
                    mirror->renderScheduled = false;
                    return;
                }
                pixel = (CVPixelBufferRef)CFRetain(mirror->latest);
                ingressNs = mirror->latestIngressNs;
                layer = mirror->layer;
            }

            CVMetalTextureRef textureRef = nullptr;
            CVReturn textureStatus = CVMetalTextureCacheCreateTextureFromImage(
                kCFAllocatorDefault, mirror->textureCache, pixel, nullptr, MTLPixelFormatBGRA8Unorm,
                CVPixelBufferGetWidth(pixel), CVPixelBufferGetHeight(pixel), 0, &textureRef);
            id<MTLTexture> sourceTexture = textureStatus == kCVReturnSuccess && textureRef
                ? CVMetalTextureGetTexture(textureRef) : nil;
            id<CAMetalDrawable> drawable = sourceTexture ? [layer nextDrawable] : nil;
            bool renderFailed = textureStatus != kCVReturnSuccess || !sourceTexture;
            OSStatus renderStatus = textureStatus != kCVReturnSuccess ? (OSStatus)textureStatus : noErr;
            if (!drawable && !renderFailed) {
                std::lock_guard<std::mutex> guard(mirror->lock);
                ++mirror->drawableMisses;
            }
            if (!renderFailed && drawable) {
                bool readbackThisFrame = false;
                int64_t readbackStatus = -1;
                int64_t pixelMin = -1;
                int64_t pixelMax = -1;
                int64_t nonBlackGridSamples = -1;
                int64_t alphaMin = -1;
                int64_t alphaMax = -1;
                {
                    std::lock_guard<std::mutex> guard(mirror->lock);
                    if (!mirror->drawableReadbackDone && mirror->decodedImageCount >= 60) {
                        mirror->drawableReadbackDone = true;
                        readbackThisFrame = true;
                    }
                }
                id<MTLBuffer> readback = nil;
                NSUInteger readbackBytesPerRow = 0;
                NSUInteger readbackSize = 0;
                if (readbackThisFrame) {
                    const NSUInteger tightRow = drawable.texture.width * 4;
                    readbackBytesPerRow = (tightRow + 255) & ~((NSUInteger)255);
                    readbackSize = readbackBytesPerRow * drawable.texture.height;
                    readback = [mirror->device newBufferWithLength:readbackSize options:MTLResourceStorageModeShared];
                }
                id<MTLCommandBuffer> command = [mirror->commands commandBuffer];
                if (!command) {
                    renderFailed = true;
                    renderStatus = -1;
                } else {
                    @try {
                        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
                        pass.colorAttachments[0].texture = drawable.texture;
                        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
                        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
                        id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
                        if (!encoder || !mirror->renderPipeline) {
                            renderFailed = true;
                            renderStatus = -1;
                        } else {
                            [encoder setRenderPipelineState:mirror->renderPipeline];
                            [encoder setFragmentTexture:sourceTexture atIndex:0];
                            const uint32_t testPattern = mirror->testPattern ? 1 : 0;
                            [encoder setFragmentBytes:&testPattern length:sizeof(testPattern) atIndex:0];
                            [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
                            [encoder endEncoding];
                            bool readbackEncoded = false;
                            if (readbackThisFrame && readback) {
                                id<MTLBlitCommandEncoder> blit = [command blitCommandEncoder];
                                if (blit) {
                                    [blit copyFromTexture:drawable.texture
                                             sourceSlice:0
                                             sourceLevel:0
                                            sourceOrigin:MTLOriginMake(0, 0, 0)
                                              sourceSize:MTLSizeMake(drawable.texture.width, drawable.texture.height, 1)
                                                toBuffer:readback
                                       destinationOffset:0
                                  destinationBytesPerRow:readbackBytesPerRow
                                destinationBytesPerImage:readbackSize];
                                    [blit endEncoding];
                                    readbackEncoded = true;
                                }
                            }
                            [command presentDrawable:drawable];
                            [command commit];
                            [command waitUntilCompleted];
                            renderFailed = command.status != MTLCommandBufferStatusCompleted || command.error != nil;
                            if (renderFailed) renderStatus = command.error ? (OSStatus)command.error.code : (OSStatus)-1;
                            if (readbackThisFrame && readbackEncoded && !renderFailed && readback) {
                                readbackStatus = 1;
                                sampleDrawableGrid(
                                    (const uint8_t *)readback.contents,
                                    drawable.texture.width,
                                    drawable.texture.height,
                                    readbackBytesPerRow,
                                    &pixelMin,
                                    &pixelMax,
                                    &nonBlackGridSamples,
                                    &alphaMin,
                                    &alphaMax);
                            }
                        }
                    } @catch (NSException *exception) {
                        (void)exception;
                        renderFailed = true;
                        renderStatus = -1;
                    }
                }
                if (readbackThisFrame) {
                    std::lock_guard<std::mutex> guard(mirror->lock);
                    mirror->drawableReadbackStatus = readbackStatus;
                    mirror->drawablePixelMin = pixelMin;
                    mirror->drawablePixelMax = pixelMax;
                    mirror->drawableNonBlackGridSamples = nonBlackGridSamples;
                    mirror->drawableAlphaMin = alphaMin;
                    mirror->drawableAlphaMax = alphaMax;
                }
            }
            if (textureRef) CFRelease(textureRef);
            int64_t presentedAtNs = steadyNowNs();
            {
                std::lock_guard<std::mutex> guard(mirror->lock);
                if (renderFailed) {
                    ++mirror->renderErrors;
                    setFailureLocked(mirror, 9, renderStatus);
                } else if (drawable && !mirror->closed && ingressNs > 0) {
                    int64_t ageNs = std::max<int64_t>(0, presentedAtNs - ingressNs);
                    ++mirror->presentCount;
                    mirror->presentAgeTotalNs += ageNs;
                    mirror->presentAgeMaxNs = std::max(mirror->presentAgeMaxNs, ageNs);
                    size_t bucket = (size_t)std::min<int64_t>(25, ageNs / 10000000);
                    ++mirror->presentAgeHistogram[bucket];
                }
                if (!renderFailed && !drawable) {
                    // A hidden or just-resized CAMetalLayer may temporarily have no drawable.
                    // Keep the newest pixel alive and let the next attach/resize or decoded frame
                    // schedule another attempt; do not spin or turn a transient into fallback.
                    CFRelease(pixel);
                    mirror->renderScheduled = false;
                    return;
                }
                if (!renderFailed && drawable && mirror->lastPresented != pixel) {
                    if (mirror->lastPresented) CFRelease(mirror->lastPresented);
                    mirror->lastPresented = (CVPixelBufferRef)CFRetain(pixel);
                }
                if (mirror->latest == pixel) { CFRelease(mirror->latest); mirror->latest = nullptr; }
                bool again = !mirror->closed && mirror->latest != nullptr && mirror->layer != nil;
                CFRelease(pixel);
                if (!again) { mirror->renderScheduled = false; return; }
            }
        }
    });
}

static void decoded(void *reference, void *, OSStatus status, VTDecodeInfoFlags, CVImageBufferRef image, CMTime, CMTime) {
    auto *context = static_cast<DecoderContext *>(reference);
    Mirror *mirror = context->mirror;
    const int64_t callbackNs = steadyNowNs();
    bool samplePixels = false;
    if (status == noErr && image) {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (!mirror->closed && context->generation == mirror->decoderGeneration) {
            ++mirror->decodedCallbackCount;
        }
        if (!mirror->closed && context->generation == mirror->decoderGeneration &&
            !mirror->pixelSampled && mirror->decodedCallbackCount >= 60) {
            mirror->pixelSampled = true;
            samplePixels = true;
        }
    }
    int64_t pixelFormat = 0;
    int64_t sampleMin = -1;
    int64_t sampleMax = -1;
    int64_t alphaMin = -1;
    int64_t alphaMax = -1;
    if (samplePixels) {
        CVPixelBufferRef pixel = (CVPixelBufferRef)image;
        pixelFormat = CVPixelBufferGetPixelFormatType(pixel);
        if (CVPixelBufferLockBaseAddress(pixel, kCVPixelBufferLock_ReadOnly) == kCVReturnSuccess) {
            const size_t planeCount = CVPixelBufferGetPlaneCount(pixel);
            uint8_t *base = planeCount == 0
                ? (uint8_t *)CVPixelBufferGetBaseAddress(pixel)
                : (uint8_t *)CVPixelBufferGetBaseAddressOfPlane(pixel, 0);
            const size_t width = planeCount == 0 ? CVPixelBufferGetWidth(pixel) : CVPixelBufferGetWidthOfPlane(pixel, 0);
            const size_t height = planeCount == 0 ? CVPixelBufferGetHeight(pixel) : CVPixelBufferGetHeightOfPlane(pixel, 0);
            const size_t rowBytes = planeCount == 0 ? CVPixelBufferGetBytesPerRow(pixel) : CVPixelBufferGetBytesPerRowOfPlane(pixel, 0);
            const bool bgra = pixelFormat == kCVPixelFormatType_32BGRA;
            const size_t pixelBytes = bgra ? 4 : 1; // For planar formats, sample the luma plane.
            if (base && width > 0 && height > 0 && rowBytes >= width * pixelBytes) {
                sampleMin = 255;
                sampleMax = 0;
                if (bgra) { alphaMin = 255; alphaMax = 0; }
                for (size_t py = 0; py <= 8; ++py) {
                    for (size_t px = 0; px <= 8; ++px) {
                        const size_t x = px * (width - 1) / 8;
                        const size_t y = py * (height - 1) / 8;
                        const uint8_t *pixelBytesAt = base + y * rowBytes + x * pixelBytes;
                        const size_t channels = bgra ? 3 : 1;
                        for (size_t c = 0; c < channels; ++c) {
                            sampleMin = std::min<int64_t>(sampleMin, pixelBytesAt[c]);
                            sampleMax = std::max<int64_t>(sampleMax, pixelBytesAt[c]);
                        }
                        if (bgra) {
                            alphaMin = std::min<int64_t>(alphaMin, pixelBytesAt[3]);
                            alphaMax = std::max<int64_t>(alphaMax, pixelBytesAt[3]);
                        }
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(pixel, kCVPixelBufferLock_ReadOnly);
        }
    }
    bool schedule = false;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (context->generation == mirror->decoderGeneration && mirror->decodeInFlight) {
            const int64_t latencyNs = std::max<int64_t>(0, callbackNs - mirror->decodeIngressNs);
            ++mirror->decodeCount;
            mirror->decodeLatencyTotalNs += latencyNs;
            mirror->decodeLatencyMaxNs = std::max(mirror->decodeLatencyMaxNs, latencyNs);
            mirror->decodeInFlight = false;
            mirror->decodeCompleted.notify_all();
        }
        if (mirror->closed || context->generation != mirror->decoderGeneration) return;
        if (status != noErr || image == nullptr) { setFailureLocked(mirror, 8, status); return; }
        ++mirror->decodedImageCount;
        if (samplePixels) {
            mirror->decodedPixelFormat = pixelFormat;
            mirror->decodedSampleMin = sampleMin;
            mirror->decodedSampleMax = sampleMax;
            mirror->decodedAlphaMin = alphaMin;
            mirror->decodedAlphaMax = alphaMax;
        }
        if (mirror->latest) CFRelease(mirror->latest);
        mirror->latest = (CVPixelBufferRef)CFRetain(image);
        mirror->latestIngressNs = mirror->decodeIngressNs;
        mirror->width = (int)CVPixelBufferGetWidth(image);
        mirror->height = (int)CVPixelBufferGetHeight(image);
        if (!mirror->renderScheduled && mirror->layer) { mirror->renderScheduled = true; schedule = true; }
    }
    if (schedule) renderLoop(mirror);
}

static bool createDecoder(Mirror *mirror, int *failureCode, OSStatus *failureStatus) {
    if (mirror->stagedSps.empty() || mirror->stagedPps.empty()) {
        *failureCode = 2;
        *failureStatus = noErr;
        return false;
    }
    const uint8_t *sets[] = {mirror->stagedSps.data(), mirror->stagedPps.data()};
    size_t sizes[] = {mirror->stagedSps.size(), mirror->stagedPps.size()};
    CfOwner<CMVideoFormatDescriptionRef> nextFormat;
    OSStatus formatStatus = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, 2, sets, sizes, 4, nextFormat.out());
    if (formatStatus != noErr) { *failureCode = 2; *failureStatus = formatStatus; return false; }
    NSDictionary *attributes = @{
        (__bridge NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        (__bridge NSString *)kCVPixelBufferMetalCompatibilityKey: @YES,
        (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{},
    };
    uint64_t nextGeneration = mirror->decoderGeneration + 1;
    auto *context = new DecoderContext{mirror, nextGeneration};
    VTDecompressionOutputCallbackRecord callback = {decoded, context};
    VTDecompressionSessionRef nextDecoder = nullptr;
    OSStatus status = VTDecompressionSessionCreate(
        kCFAllocatorDefault, nextFormat.value, nullptr, (__bridge CFDictionaryRef)attributes, &callback, &nextDecoder);
    if (status != noErr || !nextDecoder) { delete context; *failureCode = 3; *failureStatus = status; return false; }

    VTDecompressionSessionRef previousDecoder = mirror->decoder;
    CMVideoFormatDescriptionRef previousFormat = mirror->format;
    DecoderContext *previousContext = mirror->decoderContext;
    mirror->decoder = nextDecoder;
    mirror->format = (CMVideoFormatDescriptionRef)CFRetain(nextFormat.value);
    mirror->decoderContext = context;
    mirror->decoderGeneration = nextGeneration;
    mirror->activeSps = mirror->stagedSps;
    mirror->activePps = mirror->stagedPps;
    CMVideoDimensions dimensions = CMVideoFormatDescriptionGetDimensions(nextFormat.value);
    mirror->width = dimensions.width;
    mirror->height = dimensions.height;
    if (previousDecoder) {
        VTDecompressionSessionInvalidate(previousDecoder);
        VTDecompressionSessionWaitForAsynchronousFrames(previousDecoder);
        CFRelease(previousDecoder);
    }
    if (previousFormat) CFRelease(previousFormat);
    delete previousContext;
    return true;
}

static id<MTLRenderPipelineState> createRenderPipeline(id<MTLDevice> device) {
    NSString *source = @"#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct MirrorVertex { float4 position [[position]]; float2 uv; };\n"
        "vertex MirrorVertex mirror_vertex(uint id [[vertex_id]]) {\n"
        "  MirrorVertex out;\n"
        "  switch (id) {\n"
        "    case 0: out.position = float4(-1, 1, 0, 1); out.uv = float2(0, 0); break;\n"
        "    case 1: out.position = float4(1, 1, 0, 1); out.uv = float2(1, 0); break;\n"
        "    case 2: out.position = float4(-1, -1, 0, 1); out.uv = float2(0, 1); break;\n"
        "    default: out.position = float4(1, -1, 0, 1); out.uv = float2(1, 1); break;\n"
        "  }\n"
        "  return out;\n"
        "}\n"
        "fragment half4 mirror_fragment(MirrorVertex in [[stage_in]], texture2d<half> frame [[texture(0)]], constant uint &testPattern [[buffer(0)]]) {\n"
        "  if (testPattern != 0) {\n"
        "    uint cell = (uint(in.position.x) / 24 + uint(in.position.y) / 24) & 1;\n"
        "    return cell == 0 ? half4(1, 0, 1, 1) : half4(0, 1, 1, 1);\n"
        "  }\n"
        "  constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);\n"
        "  half3 rgb = frame.sample(s, in.uv).rgb;\n"
        "  return half4(rgb, 1.0h);\n"
        "}\n";
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&error];
    if (!library) return nil;
    id<MTLFunction> vertex = [library newFunctionWithName:@"mirror_vertex"];
    id<MTLFunction> fragment = [library newFunctionWithName:@"mirror_fragment"];
    if (!vertex || !fragment) return nil;
    MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
    descriptor.vertexFunction = vertex;
    descriptor.fragmentFunction = fragment;
    descriptor.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    return [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
}

extern "C" JNIEXPORT jlong JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeCreate(
    JNIEnv *env, jclass, jobject canvas, jboolean underlayOrdering) {
    Mirror *mirror = new Mirror{};
    mirror->canvas = env->NewGlobalRef(canvas);
    mirror->underlayOrdering = underlayOrdering == JNI_TRUE;
    mirror->device = MTLCreateSystemDefaultDevice();
    if (!mirror->device) { env->DeleteGlobalRef(mirror->canvas); delete mirror; return 0; }
    mirror->commands = [mirror->device newCommandQueue];
    const char *testPattern = std::getenv("INDAGIUM_MIRROR_TEST_PATTERN");
    mirror->testPattern = testPattern && std::string(testPattern) == "1";
    mirror->renderPipeline = createRenderPipeline(mirror->device);
    mirror->renderQueue = dispatch_queue_create("com.indagium.mirror.render", DISPATCH_QUEUE_SERIAL);
    if (!mirror->commands || !mirror->renderPipeline || !mirror->renderQueue ||
        CVMetalTextureCacheCreate(kCFAllocatorDefault, nullptr, mirror->device, nullptr, &mirror->textureCache) != kCVReturnSuccess) {
        if (mirror->textureCache) CFRelease(mirror->textureCache);
        env->DeleteGlobalRef(mirror->canvas);
        delete mirror;
        return 0;
    }
    return (jlong)mirror;
}

extern "C" JNIEXPORT void JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeDetach(
    JNIEnv *, jclass, jlong handle) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return;
    CAMetalLayer *layer = nil;
    std::shared_ptr<LayerAttachmentState> attachmentState;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return;
        layer = mirror->layer;
        attachmentState = mirror->layerAttachment;
    }
    // A tab-owned SwingPanel can leave the main window while its recorder and decoder remain
    // alive. Remove our retained overlay layer directly instead of asking JAWT for a peer that
    // may already have been disposed; nativeAttach will reparent it when the panel is shown again.
    // The generation check lets hierarchy teardown return immediately and prevents a delayed
    // AppKit removal from detaching a layer that was already reattached to a newer host.
    const uint64_t generation = nextLayerAttachmentGeneration(attachmentState);
    detachLayerFromTreeWhenCurrent(layer, attachmentState, generation);
}

/** Whether our layer is currently in a Core Animation tree. An attach can return normally and still
 *  leave it unparented (a window handoff racing JAWT), so the Kotlin side cannot infer this from
 *  having called attach; it asks here. A plain property read, deliberately without a main-thread
 *  hop: it is polled from the AWT thread, which must never block on AppKit. */
extern "C" JNIEXPORT jboolean JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeLayerAttached(
    JNIEnv *, jclass, jlong handle) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return JNI_FALSE;
    CAMetalLayer *layer = nil;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return JNI_FALSE;
        layer = mirror->layer;
    }
    return layer && layer.superlayer != nil ? JNI_TRUE : JNI_FALSE;
}

/** Packed bitfield the Kotlin side polls to decide whether the underlay can work here: bit0
 *  attached (superlayer != nil), bit1 our layer is currently below every sibling, bit2 at least one
 *  other sibling exists, bit3 every other sibling is non-opaque. The decision uses bits 0, 2 and 3
 *  only; bit1 describes the ordering we applied ourselves (it is false by construction while the
 *  fallback keeps us above), so it is reported for diagnostics and never gates an upgrade. A plain property read, deliberately without a main-thread hop — see
 *  nativeLayerAttached's comment for why: it is polled from the EDT, which must never block on
 *  AppKit. */
extern "C" JNIEXPORT jlong JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeUnderlayStatus(
    JNIEnv *, jclass, jlong handle) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return 0;
    CAMetalLayer *layer = nil;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return 0;
        layer = mirror->layer;
    }
    if (!layer) return 0;
    CALayer *parent = layer.superlayer;
    if (!parent) return 0;
    jlong status = 0x1; // bit0: attached
    NSArray<CALayer *> *siblings = parent.sublayers;
    bool hasOtherSibling = false;
    for (CALayer *sibling in siblings) {
        if (sibling != layer) { hasOtherSibling = true; break; }
    }
    if (layerIsBelowAllSiblings(layer, siblings)) status |= 0x2; // bit1
    if (hasOtherSibling) status |= 0x4; // bit2
    if (otherSiblingsAreTransparent(layer, siblings)) status |= 0x8; // bit3
    return status;
}

/** Kotlin flips this after reading nativeUnderlayStatus, both to fall back (no longer supported)
 *  and to upgrade (a later sample finds the underlay conditions satisfied after all — e.g. our
 *  attach won the race against skiko's own layer and there was no sibling yet on the first
 *  sample). Re-applies mirrorLayerZPosition against the layer's current siblings on AppKit's main
 *  thread. Always dispatch_async, never dispatch_sync: this can be called from the EDT, which must
 *  never block on AppKit (see performOnAppKitMainThreadSync's callers for the same rule). */
extern "C" JNIEXPORT void JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeSetUnderlayOrdering(
    JNIEnv *, jclass, jlong handle, jboolean underlayOrdering) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return;
    const bool requested = underlayOrdering == JNI_TRUE;
    CAMetalLayer *layer = nil;
    bool testPattern = false;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return;
        mirror->underlayOrdering = requested;
        layer = mirror->layer;
        testPattern = mirror->testPattern;
    }
    if (!layer) return;
    __strong CAMetalLayer *retainedLayer = layer;
    dispatch_async(dispatch_get_main_queue(), ^{
        CALayer *parentLayer = retainedLayer.superlayer;
        if (!parentLayer) return;
        NSArray<CALayer *> *siblings = parentLayer.sublayers;
        CGFloat siblingMinZ = 0.0, siblingMaxZ = 0.0;
        bool hasOtherSibling = false;
        for (CALayer *sibling in siblings) {
            if (sibling == retainedLayer) continue;
            if (hasOtherSibling) {
                siblingMinZ = std::min(siblingMinZ, sibling.zPosition);
                siblingMaxZ = std::max(siblingMaxZ, sibling.zPosition);
            } else {
                siblingMinZ = siblingMaxZ = sibling.zPosition;
            }
            hasOtherSibling = true;
        }
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        retainedLayer.zPosition = mirrorLayerZPosition(requested, testPattern, hasOtherSibling, siblingMinZ, siblingMaxZ);
        [CATransaction commit];
    });
}

extern "C" JNIEXPORT jstring JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeAttach(
    JNIEnv *env, jclass, jlong handle, jint windowX, jint windowY, jint insetLeft, jint insetTop) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return nullptr;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return nullptr;
        ++mirror->attachAttempts;
    }
    JAWT awt{};
    awt.version = JAWT_VERSION_1_7;
    if (!JAWT_GetAWT(env, &awt)) {
        std::lock_guard<std::mutex> guard(mirror->lock);
        ++mirror->attachFailures;
        mirror->lastAttachResult = 2;
        return nullptr;
    }
    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, mirror->canvas);
    if (!surface) {
        std::lock_guard<std::mutex> guard(mirror->lock);
        ++mirror->attachFailures;
        mirror->lastAttachResult = 3;
        return nullptr;
    }
    __block CAMetalLayer *layer = nil;
    __block CAShapeLayer *clipMask = nil;
    __block CGFloat clipLeft = 0.0;
    __block CGFloat clipTop = 0.0;
    __block CGFloat clipRight = 1.0;
    __block CGFloat clipBottom = 1.0;
    bool locked = (surface->Lock(surface) & JAWT_LOCK_ERROR) == 0;
    __block int64_t attachResult = locked ? 5 : 4;
    __block int64_t attachWidth = 0;
    __block int64_t attachHeight = 0;
    __block int64_t hasSuperlayer = 0;
    __block int64_t hasWindowLayer = 0;
    __block int64_t layerHidden = 0;
    __block int64_t layerOpacityMilli = 0;
    __block int64_t layerDescendsFromWindow = 0;
    __block int64_t parentHidden = 0;
    __block int64_t parentOpacityMilli = 0;
    __block int64_t layerZMilli = 0;
    __block int64_t layerSiblingIndex = -1;
    __block int64_t layerSiblingCount = 0;
    __block int64_t layerSiblingMaxZMilli = 0;
    __block int64_t layerFrameWidth = 0;
    __block int64_t layerFrameHeight = 0;
    __block int64_t layerFrameX = 0;
    __block int64_t layerFrameY = 0;
    __block int64_t componentBoundsX = 0;
    __block int64_t componentBoundsY = 0;
    __block int64_t canvasWindowX = windowX;
    __block int64_t canvasWindowY = windowY;
    __block int64_t parentFrameWidth = 0;
    __block int64_t parentFrameHeight = 0;
    __block NSString *appKitHierarchy = @"";
    id<JAWT_SurfaceLayers> platformLayers = nil;
    bool hasSurfaceInfo = false;
    if (locked) {
        JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
        if (info) {
            hasSurfaceInfo = true;
            attachWidth = (int64_t)info->bounds.width;
            attachHeight = (int64_t)info->bounds.height;
            componentBoundsX = (int64_t)info->bounds.x;
            componentBoundsY = (int64_t)info->bounds.y;
            platformLayers = (__bridge id<JAWT_SurfaceLayers>)info->platformInfo;
            surface->FreeDrawingSurfaceInfo(info);
        }
        surface->Unlock(surface);
    }
    // Release the JAWT info and surface lock before synchronously entering AppKit. This avoids
    // holding a peer lock across the EDT -> main-thread hop, which could deadlock a resize/close.
    awt.FreeDrawingSurface(surface);
    if (platformLayers) {
        const auto attachmentState = mirror->layerAttachment;
        const uint64_t attachmentGeneration = nextLayerAttachmentGeneration(attachmentState);
        performOnAppKitMainThreadSync(^{
                    if (!isCurrentLayerAttachmentGeneration(attachmentState, attachmentGeneration)) {
                        attachResult = 6;
                        return;
                    }
                    {
                        std::lock_guard<std::mutex> guard(mirror->lock);
                        if (!mirror->closed) {
                            if (!mirror->layer) {
                                mirror->layer = [CAMetalLayer layer];
                                mirror->layer.device = mirror->device;
                                mirror->layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
                                mirror->layer.opaque = YES;
                                // The underlay starts behind siblings; attach will refine this
                                // against the actual parent layer's sibling depths.
                                mirror->layer.zPosition = mirror->underlayOrdering ? -1.0 : 1.0;
                                mirror->layer.framebufferOnly = NO;
                                mirror->layer.presentsWithTransaction = NO;
                                disableImplicitAnimations(mirror->layer);
                            }
                            if (!mirror->clipMask) {
                                mirror->clipMask = [CAShapeLayer layer];
                                mirror->clipMask.fillColor = NSColor.whiteColor.CGColor;
                                mirror->clipMask.strokeColor = nil;
                                disableImplicitAnimations(mirror->clipMask);
                            }
                            mirror->layer.opaque = YES;
                            updateDrawableGeometry(
                                mirror->layer,
                                attachWidth,
                                attachHeight,
                                attachWidth,
                                attachHeight);
                            layer = mirror->layer;
                            clipMask = mirror->clipMask;
                            clipLeft = mirror->clipLeft;
                            clipTop = mirror->clipTop;
                            clipRight = mirror->clipRight;
                            clipBottom = mirror->clipBottom;
                        }
                    }
                    if (!layer) {
                        attachResult = 6;
                        return;
                    }
                    CALayer *previousParent = layer.superlayer;
                    CALayer *windowLayer = platformLayers.windowLayer;
                    // AWTSurfaceLayers.setLayer mutates the NSView-hosted Core Animation tree
                    // directly. Keep this, initial placement and z-order on the AppKit thread.
                    [CATransaction begin];
                    [CATransaction setDisableActions:YES];
                    platformLayers.layer = layer;
                    // AWTSurfaceLayers.setLayer is a no-op when handed the layer it already holds,
                    // and nativeDetach removes our layer from the tree without clearing JAWT's
                    // reference (the peer may be gone by then). So every re-attach to a Canvas we
                    // had detached from left the layer outside the window: decoding and touches
                    // kept working behind a black hole, and Disconnect/Connect (same layer) could
                    // not recover it. Re-add it ourselves; JAWT keeps its reference, so its later
                    // bounds updates still move this layer.
                    if (!layer.superlayer && windowLayer && platformLayers.layer == layer) {
                        [windowLayer addSublayer:layer];
                    }
                    if (needsInitialLayerFrame(previousParent, windowLayer) && windowLayer &&
                        windowLayer.bounds.size.height > 0 && attachWidth > 0 && attachHeight > 0) {
                        // AWT peer bounds can be sent before JAWT attaches this new layer. Seed
                        // its first frame in window coordinates, and repeat only if JAWT moves it
                        // to another window layer. Later resizes remain owned by AWT.
                        setInitialLayerFrame(
                            layer,
                            windowLayer.bounds.size.height,
                            windowX,
                            windowY,
                            insetLeft,
                            insetTop,
                            attachWidth,
                            attachHeight);
                    }
                    updateLayerClipMask(layer, clipMask, clipLeft, clipTop, clipRight, clipBottom);
                    CALayer *parentLayer = layer.superlayer;
                    bool descendsFromWindow = false;
                    for (CALayer *ancestor = parentLayer; ancestor; ancestor = ancestor.superlayer) {
                        if (ancestor == windowLayer) { descendsFromWindow = true; break; }
                    }
                    NSArray<CALayer *> *siblings = parentLayer.sublayers;
                    if (siblings) {
                        layerSiblingCount = (int64_t)siblings.count;
                        CGFloat siblingMinZ = 0.0;
                        CGFloat siblingMaxZ = 0.0;
                        bool hasOtherSibling = false;
                        for (NSUInteger index = 0; index < siblings.count; ++index) {
                            CALayer *sibling = siblings[index];
                            if (sibling == layer) {
                                layerSiblingIndex = (int64_t)index;
                            } else {
                                if (hasOtherSibling) {
                                    siblingMinZ = std::min(siblingMinZ, sibling.zPosition);
                                    siblingMaxZ = std::max(siblingMaxZ, sibling.zPosition);
                                } else {
                                    siblingMinZ = siblingMaxZ = sibling.zPosition;
                                }
                                hasOtherSibling = true;
                            }
                        }
                        // The underlay leaves the live layer at natural depth so Compose content
                        // can paint over it; the fallback keeps the prior above-siblings placement,
                        // and the test pattern stays topmost for native diagnostics either way.
                        layer.zPosition = mirrorLayerZPosition(
                            mirror->underlayOrdering,
                            mirror->testPattern,
                            hasOtherSibling,
                            siblingMinZ,
                            siblingMaxZ);
                        layerSiblingMaxZMilli = hasOtherSibling ? (int64_t)(siblingMaxZ * 1000.0) : 0;
                    }
                    hasSuperlayer = layer.superlayer != nil;
                    hasWindowLayer = windowLayer != nil;
                    layerHidden = layer.hidden;
                    layerOpacityMilli = (int64_t)(layer.opacity * 1000.0f);
                    layerDescendsFromWindow = descendsFromWindow;
                    parentHidden = parentLayer.hidden;
                    parentOpacityMilli = (int64_t)(parentLayer.opacity * 1000.0f);
                    layerZMilli = (int64_t)(layer.zPosition * 1000.0f);
                    layerFrameWidth = (int64_t)layer.frame.size.width;
                    layerFrameHeight = (int64_t)layer.frame.size.height;
                    layerFrameX = (int64_t)layer.frame.origin.x;
                    layerFrameY = (int64_t)layer.frame.origin.y;
                    parentFrameWidth = (int64_t)parentLayer.frame.size.width;
                    parentFrameHeight = (int64_t)parentLayer.frame.size.height;
                    [CATransaction commit];
                    [CATransaction flush];
                    appKitHierarchy = describeAppKitViewHierarchy(windowLayer, layer);
                    attachResult = 1;
        });
    } else if (hasSurfaceInfo) {
        attachResult = 6;
    }
    if (!layer) {
        std::lock_guard<std::mutex> guard(mirror->lock);
        ++mirror->attachFailures;
        mirror->lastAttachResult = attachResult;
        mirror->lastAttachWidth = attachWidth;
        mirror->lastAttachHeight = attachHeight;
        mirror->lastLayerHasSuperlayer = hasSuperlayer;
        mirror->lastWindowLayerAvailable = hasWindowLayer;
        mirror->lastLayerHidden = layerHidden;
        mirror->lastLayerOpacityMilli = layerOpacityMilli;
        mirror->lastLayerDescendsFromWindow = layerDescendsFromWindow;
        mirror->lastParentHidden = parentHidden;
        mirror->lastParentOpacityMilli = parentOpacityMilli;
        mirror->lastLayerZMilli = layerZMilli;
        mirror->lastLayerSiblingIndex = layerSiblingIndex;
        mirror->lastLayerSiblingCount = layerSiblingCount;
        mirror->lastLayerSiblingMaxZMilli = layerSiblingMaxZMilli;
        mirror->lastLayerFrameWidth = layerFrameWidth;
        mirror->lastLayerFrameHeight = layerFrameHeight;
        mirror->lastLayerFrameX = layerFrameX;
        mirror->lastLayerFrameY = layerFrameY;
        mirror->lastComponentBoundsX = componentBoundsX;
        mirror->lastComponentBoundsY = componentBoundsY;
        mirror->lastCanvasWindowX = canvasWindowX;
        mirror->lastCanvasWindowY = canvasWindowY;
        mirror->lastParentFrameWidth = parentFrameWidth;
        mirror->lastParentFrameHeight = parentFrameHeight;
        return env->NewStringUTF([appKitHierarchy UTF8String] ?: "");
    }
    bool schedule = false;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        replayLastPresentedLocked(mirror);
        if (!mirror->closed && mirror->latest && !mirror->renderScheduled) {
            mirror->renderScheduled = true;
            schedule = true;
        }
    }
    if (schedule) renderLoop(mirror);
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (layer) ++mirror->attachSuccesses;
        else ++mirror->attachFailures;
        mirror->lastAttachResult = 1;
        mirror->lastAttachWidth = attachWidth;
        mirror->lastAttachHeight = attachHeight;
        mirror->lastLayerHasSuperlayer = hasSuperlayer;
        mirror->lastWindowLayerAvailable = hasWindowLayer;
        mirror->lastLayerHidden = layerHidden;
        mirror->lastLayerOpacityMilli = layerOpacityMilli;
        mirror->lastLayerDescendsFromWindow = layerDescendsFromWindow;
        mirror->lastParentHidden = parentHidden;
        mirror->lastParentOpacityMilli = parentOpacityMilli;
        mirror->lastLayerZMilli = layerZMilli;
        mirror->lastLayerSiblingIndex = layerSiblingIndex;
        mirror->lastLayerSiblingCount = layerSiblingCount;
        mirror->lastLayerSiblingMaxZMilli = layerSiblingMaxZMilli;
        mirror->lastLayerFrameWidth = layerFrameWidth;
        mirror->lastLayerFrameHeight = layerFrameHeight;
        mirror->lastLayerFrameX = layerFrameX;
        mirror->lastLayerFrameY = layerFrameY;
        mirror->lastComponentBoundsX = componentBoundsX;
        mirror->lastComponentBoundsY = componentBoundsY;
        mirror->lastCanvasWindowX = canvasWindowX;
        mirror->lastCanvasWindowY = canvasWindowY;
        mirror->lastParentFrameWidth = parentFrameWidth;
        mirror->lastParentFrameHeight = parentFrameHeight;
    }
    return env->NewStringUTF([appKitHierarchy UTF8String] ?: "");
}

extern "C" JNIEXPORT jlong JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeDecode(
    JNIEnv *env, jclass, jlong handle, jbyteArray bytes, jint count, jlong pts, jboolean config, jboolean keyFrame, jlong queueAgeNs) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return failureValue(1);
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return failureValue(1);
        if (mirror->failed) return failureValue(mirror->failureCode == 0 ? 10 : mirror->failureCode, mirror->failureStatus);
    }
    if (!bytes || count <= 0 || count > env->GetArrayLength(bytes)) return failureValue(1);
    jbyte *raw = env->GetByteArrayElements(bytes, nullptr);
    if (!raw) return failureValue(1);
    std::vector<Nalu> units = parseAnnexB((const uint8_t *)raw, (size_t)count);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);

    std::vector<uint8_t> avcc;
    for (const auto &unit : units) {
        if (unit.bytes.empty()) continue;
        uint32_t size = CFSwapInt32HostToBig((uint32_t)unit.bytes.size());
        const uint8_t *sizeBytes = reinterpret_cast<const uint8_t *>(&size);
        avcc.insert(avcc.end(), sizeBytes, sizeBytes + sizeof(size));
        avcc.insert(avcc.end(), unit.bytes.begin(), unit.bytes.end());
    }
    if (avcc.empty()) return failureValue(1);

    if (config) {
        const Nalu *sps = nullptr;
        const Nalu *pps = nullptr;
        for (const auto &unit : units) { if (unit.type == 7) sps = &unit; else if (unit.type == 8) pps = &unit; }
        if (!sps || !pps) return failureValue(2);
        std::unique_lock<std::mutex> guard(mirror->lock);
        if (mirror->closed) return failureValue(1);
        if (!mirror->decodeCompleted.wait_for(
                guard, std::chrono::milliseconds(500), [&] { return !mirror->decodeInFlight || mirror->closed; })) {
            setFailureLocked(mirror, 10);
            return failureValue(10);
        }
        mirror->stagedSps = sps->bytes;
        mirror->stagedPps = pps->bytes;
        return ((jlong)mirror->width << 32) | (uint32_t)mirror->height;
    }

    CfOwner<CMBlockBufferRef> block;
    CfOwner<CMSampleBufferRef> sample;
    OSStatus blockStatus = CMBlockBufferCreateEmpty(kCFAllocatorDefault, 0, 0, block.out());
    if (blockStatus == noErr) blockStatus = CMBlockBufferAppendMemoryBlock(
        block.value, nullptr, avcc.size(), kCFAllocatorDefault, nullptr, 0, avcc.size(), 0);
    if (blockStatus == noErr) blockStatus = CMBlockBufferReplaceDataBytes(avcc.data(), block.value, 0, avcc.size());
    if (blockStatus != noErr) return failureValue(4, blockStatus);

    VTDecompressionSessionRef session = nullptr;
    CMVideoFormatDescriptionRef format = nullptr;
    uint64_t generation = 0;
    {
        std::unique_lock<std::mutex> guard(mirror->lock);
        if (mirror->closed || mirror->failed || !mirror->decodeCompleted.wait_for(
                guard, std::chrono::milliseconds(500), [&] { return !mirror->decodeInFlight || mirror->closed || mirror->failed; })) {
            setFailureLocked(mirror, 10);
            return failureValue(10);
        }
        if (mirror->closed) return failureValue(1);
        if (mirror->failed) return failureValue(mirror->failureCode == 0 ? 10 : mirror->failureCode, mirror->failureStatus);
        if (keyFrame && !mirror->stagedSps.empty() && !mirror->stagedPps.empty() &&
            (mirror->decoder == nullptr || mirror->stagedSps != mirror->activeSps || mirror->stagedPps != mirror->activePps)) {
            int stage = 0;
            OSStatus status = noErr;
            if (!createDecoder(mirror, &stage, &status)) {
                setFailureLocked(mirror, stage, status);
                return failureValue(stage, status);
            }
        }
        if (!mirror->decoder || !mirror->format) return failureValue(5);
        session = (VTDecompressionSessionRef)CFRetain(mirror->decoder);
        format = (CMVideoFormatDescriptionRef)CFRetain(mirror->format);
        generation = mirror->decoderGeneration;
    }

    CMSampleTimingInfo timing = {kCMTimeInvalid, CMTimeMake(pts, 1000000), kCMTimeInvalid};
    size_t sampleSize = avcc.size();
    OSStatus sampleStatus = CMSampleBufferCreateReady(
        kCFAllocatorDefault, block.value, format, 1, 1, &timing, 1, &sampleSize, sample.out());
    CFRelease(format);
    if (sampleStatus != noErr || !sample.value) { CFRelease(session); return failureValue(6, sampleStatus); }

    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed || mirror->failed || mirror->decoderGeneration != generation || mirror->decodeInFlight) {
            CFRelease(session);
            return failureValue(10);
        }
        mirror->decodeInFlight = true;
        mirror->decodeIngressNs = steadyNowNs() - std::max<jlong>(0, queueAgeNs);
    }

    // Do not hold Mirror::lock here: VideoToolbox is permitted to invoke its output callback
    // before DecodeFrame returns. The extra retain keeps this session alive while close/reconfigure
    // waits for the in-flight callback.
    OSStatus decodeStatus = VTDecompressionSessionDecodeFrame(
        session, sample.value, kVTDecodeFrame_EnableAsynchronousDecompression, nullptr, nullptr);
    CFRelease(session);
    if (decodeStatus != noErr) {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->decoderGeneration == generation) {
            mirror->decodeInFlight = false;
            setFailureLocked(mirror, 7, decodeStatus);
            mirror->decodeCompleted.notify_all();
        }
        return failureValue(7, decodeStatus);
    }
    std::lock_guard<std::mutex> guard(mirror->lock);
    if (mirror->closed) return failureValue(1);
    if (mirror->failed) return failureValue(mirror->failureCode == 0 ? 10 : mirror->failureCode, mirror->failureStatus);
    return ((jlong)mirror->width << 32) | (uint32_t)mirror->height;
}

extern "C" JNIEXPORT void JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeSetBounds(
    JNIEnv *, jclass, jlong handle, jint width, jint height, jint pixelWidth, jint pixelHeight,
    jboolean hasOrigin, jint windowX, jint windowY, jint insetLeft, jint insetTop) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror || width <= 0 || height <= 0 || pixelWidth <= 0 || pixelHeight <= 0) return;
    bool schedule = false;
    __strong CAMetalLayer *layer = nil;
    __strong CAShapeLayer *clipMask = nil;
    std::shared_ptr<PendingGeometryUpdate> pending;
    CGFloat clipLeft = 0.0, clipTop = 0.0, clipRight = 1.0, clipBottom = 1.0;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (!mirror->closed && mirror->layer) {
            layer = mirror->layer;
            clipMask = mirror->clipMask;
            pending = mirror->pendingGeometry;
            clipLeft = mirror->clipLeft;
            clipTop = mirror->clipTop;
            clipRight = mirror->clipRight;
            clipBottom = mirror->clipBottom;
            replayLastPresentedLocked(mirror);
            if (mirror->latest && !mirror->renderScheduled) {
                mirror->renderScheduled = true;
                schedule = true;
            }
        }
    }
    if (layer && clipMask && pending) {
        bool shouldSchedule = false;
        {
            std::lock_guard<std::mutex> guard(pending->lock);
            if (!pending->closed) {
                pending->width = width;
                pending->height = height;
                pending->pixelWidth = pixelWidth;
                pending->pixelHeight = pixelHeight;
                pending->hasOrigin = hasOrigin == JNI_TRUE;
                pending->windowX = windowX;
                pending->windowY = windowY;
                pending->insetLeft = insetLeft;
                pending->insetTop = insetTop;
                pending->clipLeft = clipLeft;
                pending->clipTop = clipTop;
                pending->clipRight = clipRight;
                pending->clipBottom = clipBottom;
                ++pending->generation;
                if (!pending->scheduled) {
                    pending->scheduled = true;
                    shouldSchedule = true;
                }
            }
        }
        // Component resize notifications run on the AWT event-dispatch thread. Waiting for the
        // AppKit main queue from that thread can deadlock while a detached window is being
        // resized: AppKit is waiting for AWT to finish the resize that is itself waiting here.
        // The latest geometry is coalesced above and applied asynchronously after the current
        // resize transaction; no explicit transaction flush is needed during a live resize.
        if (shouldSchedule) schedulePendingGeometryUpdate(pending, layer, clipMask);
    }
    if (schedule) renderLoop(mirror);
}

extern "C" JNIEXPORT void JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeSetClip(
    JNIEnv *, jclass, jlong handle, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return;
    const CGFloat normalizedLeft = std::clamp<CGFloat>(left, 0.0, 1.0);
    const CGFloat normalizedTop = std::clamp<CGFloat>(top, 0.0, 1.0);
    const CGFloat normalizedRight = std::clamp<CGFloat>(right, 0.0, 1.0);
    const CGFloat normalizedBottom = std::clamp<CGFloat>(bottom, 0.0, 1.0);
    __strong CAMetalLayer *layer = nil;
    __strong CAShapeLayer *clipMask = nil;
    std::shared_ptr<PendingGeometryUpdate> pending;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        if (mirror->closed) return;
        mirror->clipLeft = normalizedLeft;
        mirror->clipTop = normalizedTop;
        mirror->clipRight = normalizedRight;
        mirror->clipBottom = normalizedBottom;
        layer = mirror->layer;
        clipMask = mirror->clipMask;
        pending = mirror->pendingGeometry;
    }
    bool shouldSchedule = false;
    if (pending) {
        std::lock_guard<std::mutex> guard(pending->lock);
        if (!pending->closed) {
            pending->clipLeft = normalizedLeft;
            pending->clipTop = normalizedTop;
            pending->clipRight = normalizedRight;
            pending->clipBottom = normalizedBottom;
            ++pending->generation;
            // Before JAWT attaches the layer there is nowhere to post this update. Keep the
            // latest clip here; nativeSetBounds will schedule it once the layer is available.
            if (layer && clipMask && !pending->scheduled) {
                pending->scheduled = true;
                shouldSchedule = true;
            }
        }
    }
    if (!layer || !clipMask) return;
    if (shouldSchedule) schedulePendingGeometryUpdate(pending, layer, clipMask);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeReadMetrics(
    JNIEnv *env, jclass, jlong handle) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return nullptr;
    CAMetalLayer *layer = nil;
    std::shared_ptr<LayerFrameSnapshot> frameSnapshot;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        layer = mirror->layer;
        frameSnapshot = mirror->layerFrameSnapshot;
    }
    CGRect currentLayerFrame = CGRectZero;
    CGRect currentParentFrame = CGRectZero;
    readCurrentLayerFrames(layer, frameSnapshot, &currentLayerFrame, &currentParentFrame);
    jlong values[49]{};
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        // JAWT schedules its frame writes asynchronously on AppKit's main queue. The snapshot
        // helper samples there without blocking; only replace attach data after a sized frame is
        // available, so the first metrics read cannot erase useful initial placement evidence.
        if (layer && mirror->layer == layer && currentLayerFrame.size.width > 0 && currentLayerFrame.size.height > 0) {
            mirror->lastLayerFrameX = (int64_t)currentLayerFrame.origin.x;
            mirror->lastLayerFrameY = (int64_t)currentLayerFrame.origin.y;
            mirror->lastLayerFrameWidth = (int64_t)currentLayerFrame.size.width;
            mirror->lastLayerFrameHeight = (int64_t)currentLayerFrame.size.height;
            mirror->lastParentFrameWidth = (int64_t)currentParentFrame.size.width;
            mirror->lastParentFrameHeight = (int64_t)currentParentFrame.size.height;
        }
        values[0] = mirror->decodeCount;
        values[1] = mirror->decodeLatencyTotalNs;
        values[2] = mirror->decodeLatencyMaxNs;
        values[3] = mirror->presentCount;
        values[4] = mirror->presentAgeTotalNs;
        values[5] = mirror->presentAgeMaxNs;
        values[6] = mirror->renderErrors;
        if (mirror->presentCount > 0) {
            int64_t target = (mirror->presentCount * 95 + 99) / 100;
            int64_t accumulated = 0;
            for (size_t i = 0; i < 26; ++i) {
                accumulated += mirror->presentAgeHistogram[i];
                if (accumulated >= target) {
        values[7] = (jlong)((i + 1) * 10000000);
                    break;
                }
            }
        }
        mirror->decodeCount = mirror->decodeLatencyTotalNs = mirror->decodeLatencyMaxNs = 0;
        mirror->presentCount = mirror->presentAgeTotalNs = mirror->presentAgeMaxNs = 0;
        std::fill(std::begin(mirror->presentAgeHistogram), std::end(mirror->presentAgeHistogram), 0);
        mirror->renderErrors = 0;
        values[8] = mirror->decodedImageCount;
        values[9] = mirror->attachAttempts;
        values[10] = mirror->attachSuccesses;
        values[11] = mirror->attachFailures;
        values[12] = mirror->drawableMisses;
        values[13] = mirror->lastAttachResult;
        values[14] = mirror->lastAttachWidth;
        values[15] = mirror->lastAttachHeight;
        values[16] = mirror->decodedPixelFormat;
        values[17] = mirror->decodedSampleMin;
        values[18] = mirror->decodedSampleMax;
        values[19] = mirror->lastLayerHasSuperlayer;
        values[20] = mirror->lastWindowLayerAvailable;
        values[21] = mirror->lastLayerHidden;
        values[22] = mirror->lastLayerOpacityMilli;
        values[23] = mirror->testPattern ? 1 : 0;
        values[24] = mirror->lastLayerDescendsFromWindow;
        values[25] = mirror->lastParentHidden;
        values[26] = mirror->lastParentOpacityMilli;
        values[27] = mirror->lastLayerZMilli;
        values[28] = mirror->lastLayerSiblingIndex;
        values[29] = mirror->lastLayerSiblingCount;
        values[30] = mirror->lastLayerFrameWidth;
        values[31] = mirror->lastLayerFrameHeight;
        values[32] = mirror->lastParentFrameWidth;
        values[33] = mirror->lastParentFrameHeight;
        values[34] = mirror->drawableReadbackStatus;
        values[35] = mirror->drawablePixelMin;
        values[36] = mirror->drawablePixelMax;
        values[37] = mirror->lastLayerFrameX;
        values[38] = mirror->lastLayerFrameY;
        values[39] = mirror->lastComponentBoundsX;
        values[40] = mirror->lastComponentBoundsY;
        values[41] = mirror->lastCanvasWindowX;
        values[42] = mirror->lastCanvasWindowY;
        values[43] = mirror->drawableNonBlackGridSamples;
        values[44] = mirror->lastLayerSiblingMaxZMilli;
        values[45] = mirror->drawableAlphaMin;
        values[46] = mirror->drawableAlphaMax;
        values[47] = mirror->decodedAlphaMin;
        values[48] = mirror->decodedAlphaMax;
        mirror->decodedImageCount = 0;
        mirror->attachAttempts = mirror->attachSuccesses = mirror->attachFailures = mirror->drawableMisses = 0;
    }
    jlongArray result = env->NewLongArray(49);
    if (result) env->SetLongArrayRegion(result, 0, 49, values);
    return result;
}

static void shutdownMirror(Mirror *mirror) {
    if (!mirror) return;
    VTDecompressionSessionRef decoder = nullptr;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        mirror->closed = true;
        mirror->decodeCompleted.notify_all();
        decoder = mirror->decoder;
        mirror->decoder = nullptr;
    }
    if (mirror->pendingGeometry) {
        std::lock_guard<std::mutex> guard(mirror->pendingGeometry->lock);
        mirror->pendingGeometry->closed = true;
        ++mirror->pendingGeometry->generation;
    }
    if (decoder) {
        VTDecompressionSessionInvalidate(decoder);
        VTDecompressionSessionWaitForAsynchronousFrames(decoder);
        CFRelease(decoder);
    }
    if (mirror->renderQueue) dispatch_sync(mirror->renderQueue, ^{});
    if (mirror->latest) { CFRelease(mirror->latest); mirror->latest = nullptr; }
    if (mirror->lastPresented) { CFRelease(mirror->lastPresented); mirror->lastPresented = nullptr; }
    if (mirror->format) { CFRelease(mirror->format); mirror->format = nullptr; }
    delete mirror->decoderContext;
    mirror->decoderContext = nullptr;
    if (mirror->textureCache) { CFRelease(mirror->textureCache); mirror->textureCache = nullptr; }
    mirror->layer = nil;
}

extern "C" JNIEXPORT void JNICALL Java_com_indagium_capture_mirror_MacVideoToolboxMirrorNative_nativeClose(JNIEnv *env, jclass, jlong handle) {
    Mirror *mirror = (Mirror *)handle;
    if (!mirror) return;
    CAMetalLayer *layer = nil;
    {
        std::lock_guard<std::mutex> guard(mirror->lock);
        mirror->closed = true;
        mirror->decodeCompleted.notify_all();
        layer = mirror->layer;
    }
    closeLayerAttachmentState(mirror->layerAttachment);
    // Compose can dispose the Canvas peer before it calls close. Never ask JAWT for a new drawing
    // surface here; that dereferences the invalid peer. The CAMetalLayer is ours, so detach the
    // retained layer directly on AppKit's main queue without touching the dead peer.
    detachLayerFromTree(layer);
    shutdownMirror(mirror);
    if (mirror->canvas) env->DeleteGlobalRef(mirror->canvas);
    delete mirror;
}
