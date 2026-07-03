# openlog_icon_fullbleed_tahoe — macOS-only full-bleed variant

**Used by:** `icons/openlog.icns` only. Linux (`icons/openlog.png`) and Windows
(`icons/openlog.ico`) keep the margined squircle variant — those platforms render icons as-is,
and the Ubuntu app-menu rendering of the margined variant was already correct.

## Why

macOS 26 (Tahoe) re-frames some legacy (non-Icon-Composer) app icons: LaunchServices composites
the icns artwork onto a system-provided backing squircle, adding a visible rim/second frame
around it. With the original margined artwork (white squircle at ~80% of a transparent canvas —
correct for Big Sur–Sequoia), this produced an "icon inside an icon" in Launchpad: a small
squircle floating inside a much bigger frame with a lot of dead gray space — see the original
bug report screenshot.

This variant is full-bleed (background covers the entire 1024×1024 canvas, motif recentred to
avoid Tahoe's corner mask) — see "How it was derived" below. **This measurably shrinks the dead
space** (confirmed by rendering the installed app's resolved icon via
`NSWorkspace.iconForFile` before/after: the artwork now fills nearly the whole tile instead of
a small icon in a big frame), **but does not fully eliminate Tahoe's outer rim.**

### What we learned isolating the cause (worth recording so nobody re-litigates this)

We suspected the rim was caused by leftover transparency/rounding in the corners (an incomplete
full-bleed scale) and fixed that specifically: `fullbleed_1024.png` (v1) still had ~30px of
near-transparent pixels at the true canvas corners (measured via `NSBitmapImageRep.colorAtXY`);
`fullbleed_v2` clamps those to hard 100%-opaque, 90°-corner square (verified: min alpha 0.996
across the full canvas). **This did not remove the rim.** We then bisected further with a series
of throwaway test `.app` bundles (fresh bundle IDs each time, so LaunchServices icon caching
can't explain the results) to isolate what actually triggers Tahoe's compatibility framing:

| Icon content | Result |
|---|---|
| Solid opaque red square, no gradient | clean single frame |
| Solid opaque near-white square | clean single frame |
| Flat gray background + one flat-colored circle | clean single frame |
| Flat gray background + circle with a blurred drop shadow | clean single frame |
| Our full-bleed artwork, hard opaque corners, **flat** (non-gradient) background | **still double-framed** |
| Our full-bleed artwork, hard opaque corners, original gradient background | **still double-framed** |

So it is not: residual corner transparency, background gradient vs. flat fill, or the mere
presence of anti-aliasing/shadows — all of those were tested in isolation and rendered clean.
It appears to be some aggregate-complexity heuristic in Tahoe's legacy-icon compatibility path
(multiple color regions + gauge arc + magnifier's own radial-gradient fill + its drop shadow,
together) that we did not fully reverse-engineer in the time available. Full elimination of the
rim is only guaranteed by adopting the Icon Composer `.icon` format (see below) — jpackage (and
thus Compose `nativeDistributions`) cannot bundle that today.

**Verdict:** ship the full-bleed variant as a real, measured improvement (much less dead space)
and stop chasing a pixel-perfect single frame until `.icon`/jpackage support exists.

## How it was derived

Composited from the margined master (`icons/openlog.png`, 1024×1024, solid squircle spanning
104..920 px) by drawing it scaled 1.42× about the canvas center (512, 512) — chosen to push
most of the squircle's own rounded-corner/drop-shadow band off-canvas while keeping the
asymmetric motif (extends further toward the bottom-right, due to the magnifier handle) from
clipping — then explicitly clamping any pixel still below 0.995 alpha within a 140px box at
each of the four corners to the nearest safe interior color (sampled at a 110px diagonal
inset), guaranteeing a fully opaque, hard-90°-cornered 1024×1024 square (`fullbleed_v2_1024.png`
/ `icon_fullbleed_v2` in scratch — the shipped file here is the final composited PNG).

The iconset was produced with `sips -z <n> <n>` for 16/32/64/128/256/512 (+@2x pairs) and packed
with `iconutil -c icns`.
