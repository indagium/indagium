#include "indagium_mirror.mm"
#include <iostream>
#include <cstdlib>

static const uint8_t kSps64x48[] = {
    0x67, 0x42, 0xc0, 0x0a, 0xd9, 0x04, 0x7b, 0x01, 0x10, 0x00, 0x00, 0x03,
    0x00, 0x10, 0x00, 0x00, 0x03, 0x00, 0x28, 0xf1, 0x22, 0x64, 0x80,
};
static const uint8_t kPps[] = {0x68, 0xcb, 0x83, 0xcb, 0x20};
static const uint8_t kSps128x72[] = {
    0x67, 0x42, 0xc0, 0x0a, 0xd9, 0x02, 0x0b, 0xf9, 0x70, 0x11, 0x00, 0x00,
    0x03, 0x00, 0x01, 0x00, 0x00, 0x03, 0x00, 0x02, 0x8f, 0x12, 0x26, 0x48,
};

static void fail(const char *message) {
    std::cerr << "native mirror test failed: " << message << std::endl;
    std::exit(1);
}

static void verifyMetalRenderPipeline() {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (!device) fail("Metal device is unavailable");
    id<MTLRenderPipelineState> pipeline = createRenderPipeline(device);
    if (!pipeline) fail("textured-quad Metal shader or pipeline failed to compile");

    MTLTextureDescriptor *sourceDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:2 height:2 mipmapped:NO];
    sourceDescriptor.storageMode = MTLStorageModeShared;
    sourceDescriptor.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> source = [device newTextureWithDescriptor:sourceDescriptor];
    const uint8_t sourcePixels[] = {
        20, 70, 190, 0, 20, 70, 190, 0,
        20, 70, 190, 0, 20, 70, 190, 0,
    };
    const uint8_t expected[] = {
        20, 70, 190, 255, 20, 70, 190, 255,
        20, 70, 190, 255, 20, 70, 190, 255,
    };
    [source replaceRegion:MTLRegionMake2D(0, 0, 2, 2) mipmapLevel:0 withBytes:sourcePixels bytesPerRow:8];

    MTLTextureDescriptor *targetDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:2 height:2 mipmapped:NO];
    targetDescriptor.storageMode = MTLStorageModeShared;
    targetDescriptor.usage = MTLTextureUsageRenderTarget;
    id<MTLTexture> target = [device newTextureWithDescriptor:targetDescriptor];
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture = target;
    pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLCommandQueue> queue = [device newCommandQueue];
    id<MTLCommandBuffer> command = [queue commandBuffer];
    id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
    if (!source || !target || !queue || !command || !encoder) fail("could not create offscreen Metal render resources");
    [encoder setRenderPipelineState:pipeline];
    [encoder setFragmentTexture:source atIndex:0];
    const uint32_t noTestPattern = 0;
    [encoder setFragmentBytes:&noTestPattern length:sizeof(noTestPattern) atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
    [encoder endEncoding];
    [command commit];
    [command waitUntilCompleted];
    if (command.status != MTLCommandBufferStatusCompleted || command.error) fail("offscreen Metal render command failed");
    uint8_t actual[16]{};
    [target getBytes:actual bytesPerRow:8 fromRegion:MTLRegionMake2D(0, 0, 2, 2) mipmapLevel:0];
    for (int i = 0; i < 16; ++i) {
        if (std::abs((int)actual[i] - (int)expected[i]) > 2) fail("textured quad did not copy the source pixels");
    }
    int64_t min = -1, max = -1, nonBlack = -1, alphaMin = -1, alphaMax = -1;
    sampleDrawableGrid(actual, 2, 2, 8, &min, &max, &nonBlack, &alphaMin, &alphaMax);
    if (min != 20 || max != 190 || nonBlack != 81 || alphaMin != 255 || alphaMax != 255) {
        fail("Metal output was not colored and opaque when source alpha was zero");
    }
}

static void verifyLayerDetachWithoutJAWT() {
    CALayer *parent = [CALayer layer];
    CAMetalLayer *child = [CAMetalLayer layer];
    [parent addSublayer:child];
    if (child.superlayer != parent) fail("test layer was not attached to its parent");
    detachLayerFromTree(child);
    if (child.superlayer != nil) fail("native close helper did not detach the retained layer without JAWT");
}

static void verifyStaleQueuedDetachCannotRemoveAReattachedLayer() {
    auto state = std::make_shared<LayerAttachmentState>();
    const uint64_t detachGeneration = nextLayerAttachmentGeneration(state);
    const uint64_t attachGeneration = nextLayerAttachmentGeneration(state);
    if (isCurrentLayerAttachmentGeneration(state, detachGeneration)) {
        fail("a queued detach stayed current after a newer host attach");
    }
    if (!isCurrentLayerAttachmentGeneration(state, attachGeneration)) {
        fail("the latest host attachment generation was not retained");
    }
    closeLayerAttachmentState(state);
    if (isCurrentLayerAttachmentGeneration(state, attachGeneration)) {
        fail("a queued detach remained current after native close");
    }
}

static void verifyJawtManagedLayerPlacement() {
    CALayer *firstWindow = [CALayer layer];
    CALayer *secondWindow = [CALayer layer];
    if (!needsInitialLayerFrame(nil, firstWindow) ||
        needsInitialLayerFrame(firstWindow, firstWindow) ||
        !needsInitialLayerFrame(firstWindow, secondWindow)) {
        fail("initial placement is not refreshed exactly when the layer moves between windows");
    }

    const CGFloat windowHeight = 872;
    const CGFloat topLevelX = 210;
    const CGFloat topLevelY = 310;
    const CGFloat insetLeft = 10;
    const CGFloat insetTop = 32;
    const CGFloat width = 336;
    const CGFloat height = 220;
    const CGRect expected = CGRectMake(
        topLevelX - insetLeft,
        windowHeight - (topLevelY - insetTop) - height,
        width,
        height);
    const CGRect actual = initialLayerFrame(
        windowHeight, topLevelX, topLevelY, insetLeft, insetTop, width, height);
    if (!CGRectEqualToRect(actual, expected)) fail("initial JAWT layer placement did not subtract insets and flip the Y origin");

    CAMetalLayer *layer = [CAMetalLayer layer];
    layer.frame = actual;
    updateDrawableGeometry(layer, width, height, 672, 440);
    if (!CGRectEqualToRect(layer.frame, expected)) fail("drawable resize overwrote the AWT-managed layer frame");
    if (!CGSizeEqualToSize(layer.drawableSize, CGSizeMake(672, 440)) || layer.contentsScale != 2) {
        fail("drawable resize did not update backing-pixel size and scale");
    }
}

static void verifyViewportClipMaskCoordinates() {
    const CGRect bounds = CGRectMake(0, 0, 405, 420);
    const CGRect partial = layerClipMaskRect(bounds, 0.1, 0.25, 0.9, 0.75);
    const CGRect expected = CGRectMake(40.5, 105, 324, 210);
    if (!CGRectEqualToRect(partial, expected)) fail("Compose viewport clip was not converted to layer mask coordinates");

    const CGRect full = layerClipMaskRect(bounds, 0, 0, 1, 1);
    if (!CGRectEqualToRect(full, bounds)) fail("unclipped mirror bounds did not retain the full Metal layer");

    const CGRect empty = layerClipMaskRect(bounds, 0.8, 0.8, 0.2, 0.2);
    if (!CGRectEqualToRect(empty, CGRectZero)) fail("empty visible intersection did not hide all mirror pixels");
}

static void verifyMirrorLayerOrderForComposeOverlays() {
    if (mirrorLayerZPosition(true, false, true, 0.0, 12.0) != -1.0) {
        fail("underlay ordering did not place the mirror below sibling layers");
    }
    if (mirrorLayerZPosition(true, false, true, -2.0, 12.0) != -3.0) {
        fail("underlay ordering did not account for negative sibling depths");
    }
    if (mirrorLayerZPosition(false, false, true, 0.0, 12.0) != 13.0) {
        fail("fallback ordering no longer keeps the mirror above its siblings");
    }
    if (mirrorLayerZPosition(true, true, true, 0.0, 12.0) != 1000.0) {
        fail("native test pattern stopped being topmost");
    }
}

static CAMetalLayer *makeSibling(CGFloat zPosition, BOOL opaque) {
    CAMetalLayer *sibling = [CAMetalLayer layer];
    sibling.zPosition = zPosition;
    sibling.opaque = opaque;
    return sibling;
}

static void verifyUnderlayStatusPredicates() {
    CALayer *parent = [CALayer layer];
    CAMetalLayer *ours = [CAMetalLayer layer];
    ours.zPosition = -5.0;
    [parent addSublayer:ours];

    // No other siblings yet: vacuously below everything, but nothing that could host a hole.
    if (!layerIsBelowAllSiblings(ours, parent.sublayers)) {
        fail("a layer with no other siblings should be trivially below all of them");
    }
    if (otherSiblingsAreTransparent(ours, parent.sublayers)) {
        fail("transparency predicate should require at least one other sibling to exist");
    }

    CAMetalLayer *opaqueAbove = makeSibling(10.0, YES);
    [parent addSublayer:opaqueAbove];
    if (!layerIsBelowAllSiblings(ours, parent.sublayers)) {
        fail("our layer should still read as below the one sibling placed above it");
    }
    if (otherSiblingsAreTransparent(ours, parent.sublayers)) {
        fail("an opaque sibling must fail the transparency predicate");
    }

    CAMetalLayer *transparentAbove = makeSibling(20.0, NO);
    [parent addSublayer:transparentAbove];
    if (otherSiblingsAreTransparent(ours, parent.sublayers)) {
        fail("mixing an opaque and a transparent sibling must still fail");
    }

    [opaqueAbove removeFromSuperlayer];
    if (!otherSiblingsAreTransparent(ours, parent.sublayers)) {
        fail("every other sibling being non-opaque should satisfy the transparency predicate");
    }

    // The fallback puts our layer ABOVE its siblings. The predicate must still hold there, or a
    // fallback taken before skiko's layer existed could never upgrade back to the underlay.
    ours.zPosition = 30.0;
    if (layerIsBelowAllSiblings(ours, parent.sublayers)) {
        fail("a layer above its sibling must not read as below all siblings");
    }
    if (!otherSiblingsAreTransparent(ours, parent.sublayers)) {
        fail("the transparency predicate must not depend on our own z-order");
    }
    ours.zPosition = -5.0;

    CAMetalLayer *below = makeSibling(-10.0, YES);
    [parent addSublayer:below];
    if (layerIsBelowAllSiblings(ours, parent.sublayers)) {
        fail("a sibling placed below us must fail the below-all-siblings predicate");
    }
    if (otherSiblingsAreTransparent(ours, parent.sublayers)) {
        fail("an opaque sibling anywhere must fail the transparency predicate");
    }
}

static void drainMainQueue() {
    __block bool drained = false;
    dispatch_async(dispatch_get_main_queue(), ^{ drained = true; });
    while (!drained) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode
                                 beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
}

static void scheduleGeometryFromExpiredParameterScope(
    std::weak_ptr<PendingGeometryUpdate> *weakPending,
    CAMetalLayer *layer,
    CAShapeLayer *mask) {
    auto pending = std::make_shared<PendingGeometryUpdate>();
    pending->width = 64;
    pending->height = 48;
    pending->pixelWidth = 128;
    pending->pixelHeight = 96;
    pending->generation = 1;
    pending->scheduled = true;
    *weakPending = pending;
    // schedulePendingGeometryUpdate takes this shared_ptr by reference. The queued block must
    // retain a value copy, because `pending` dies on return from this helper.
    schedulePendingGeometryUpdate(pending, layer, mask);
}

static void verifyQueuedGeometryOwnsPendingState() {
    CAMetalLayer *layer = [CAMetalLayer layer];
    CAShapeLayer *mask = [CAShapeLayer layer];
    std::weak_ptr<PendingGeometryUpdate> weakPending;
    scheduleGeometryFromExpiredParameterScope(&weakPending, layer, mask);
    if (weakPending.expired()) fail("queued geometry block did not retain its pending state");
    drainMainQueue();
    if (!CGSizeEqualToSize(layer.drawableSize, CGSizeMake(128, 96))) {
        fail("queued geometry update did not apply after its parameter scope expired");
    }

    auto closedPending = std::make_shared<PendingGeometryUpdate>();
    closedPending->width = 10;
    closedPending->height = 10;
    closedPending->pixelWidth = 10;
    closedPending->pixelHeight = 10;
    closedPending->scheduled = true;
    schedulePendingGeometryUpdate(closedPending, layer, mask);
    {
        std::lock_guard<std::mutex> guard(closedPending->lock);
        closedPending->closed = true;
    }
    drainMainQueue();
    if (closedPending->scheduled) fail("closed pending geometry state stayed scheduled");
}

int main() {
    const bool metalAvailable = MTLCreateSystemDefaultDevice() != nil;
    if (metalAvailable) {
        verifyMetalRenderPipeline();
    } else {
        std::cout << "skipping Metal shader test: no Metal device is available" << std::endl;
    }
    verifyLayerDetachWithoutJAWT();
    verifyStaleQueuedDetachCannotRemoveAReattachedLayer();
    verifyJawtManagedLayerPlacement();
    verifyViewportClipMaskCoordinates();
    verifyMirrorLayerOrderForComposeOverlays();
    verifyUnderlayStatusPredicates();
    verifyQueuedGeometryOwnsPendingState();
    const uint8_t annexB[] = {
        0x00, 0x00, 0x01, 0x67, 0x11,
        0x00, 0x00, 0x00, 0x01, 0x68, 0x22,
        0x00, 0x00, 0x01, 0x65, 0x33,
    };
    const auto units = parseAnnexB(annexB, sizeof(annexB));
    if (units.size() != 3 || units[0].type != 7 || units[1].type != 8 || units[2].type != 5) {
        fail("Annex-B parser did not preserve mixed three/four-byte start codes");
    }

    if (!metalAvailable) {
        std::cout << "native mirror layer lifecycle and parser checks passed; VideoToolbox checks skipped without Metal" << std::endl;
        return 0;
    }

    Mirror mirror{};
    mirror.renderQueue = dispatch_queue_create("com.indagium.mirror.test.render", DISPATCH_QUEUE_SERIAL);
    mirror.stagedSps.assign(std::begin(kSps64x48), std::end(kSps64x48));
    mirror.stagedPps.assign(std::begin(kPps), std::end(kPps));
    int failureCode = 0;
    OSStatus failureStatus = noErr;
    if (!createDecoder(&mirror, &failureCode, &failureStatus)) {
        std::cerr << "64x48 VideoToolbox session failed, stage=" << failureCode << " status=" << failureStatus << std::endl;
        return 2;
    }
    const CMVideoDimensions first = {mirror.width, mirror.height};
    if (first.width != 64 || first.height != 48) fail("initial SPS dimensions were not read");

    mirror.stagedSps.assign(std::begin(kSps128x72), std::end(kSps128x72));
    if (!createDecoder(&mirror, &failureCode, &failureStatus)) {
        std::cerr << "128x72 VideoToolbox reconfiguration failed, stage=" << failureCode << " status=" << failureStatus << std::endl;
        return 3;
    }
    if (mirror.width != 128 || mirror.height != 72) fail("reconfigured SPS dimensions were not read");
    for (int i = 0; i < 3; ++i) {
        mirror.stagedSps.assign(std::begin(kSps64x48), std::end(kSps64x48));
        if (!createDecoder(&mirror, &failureCode, &failureStatus)) fail("decoder recreation failed during shutdown cycle");
        mirror.stagedSps.assign(std::begin(kSps128x72), std::end(kSps128x72));
        if (!createDecoder(&mirror, &failureCode, &failureStatus)) fail("decoder reconfiguration failed during shutdown cycle");
    }

    shutdownMirror(&mirror);
    if (!mirror.closed || mirror.decoder || mirror.format || mirror.decoderContext || mirror.textureCache || mirror.latest || mirror.lastPresented) {
        fail("shutdown left decoder-owned resources alive");
    }
    std::cout << "native mirror parser, SPS/PPS reconfiguration, and shutdown checks passed" << std::endl;
    return 0;
}
