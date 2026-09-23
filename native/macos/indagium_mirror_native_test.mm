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

int main() {
    verifyMetalRenderPipeline();
    verifyLayerDetachWithoutJAWT();
    verifyJawtManagedLayerPlacement();
    verifyViewportClipMaskCoordinates();
    const uint8_t annexB[] = {
        0x00, 0x00, 0x01, 0x67, 0x11,
        0x00, 0x00, 0x00, 0x01, 0x68, 0x22,
        0x00, 0x00, 0x01, 0x65, 0x33,
    };
    const auto units = parseAnnexB(annexB, sizeof(annexB));
    if (units.size() != 3 || units[0].type != 7 || units[1].type != 8 || units[2].type != 5) {
        fail("Annex-B parser did not preserve mixed three/four-byte start codes");
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
    if (!mirror.closed || mirror.decoder || mirror.format || mirror.decoderContext || mirror.textureCache || mirror.latest) {
        fail("shutdown left decoder-owned resources alive");
    }
    std::cout << "native mirror parser, SPS/PPS reconfiguration, and shutdown checks passed" << std::endl;
    return 0;
}
