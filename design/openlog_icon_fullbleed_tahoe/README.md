# openlog_icon_fullbleed_tahoe — macOS-only full-bleed variant

**Used by:** `icons/openlog.icns` only. Linux (`icons/openlog.png`) and Windows
(`icons/openlog.ico`) keep the margined squircle variant — those platforms render icons as-is,
and the Ubuntu app-menu rendering of the margined variant was already correct.

## Why

macOS 26 (Tahoe) re-frames every legacy (non-Icon-Composer) app icon: the icns artwork is
composited onto a system-provided backing squircle. With the previous margined artwork (white
squircle at ~80% of a transparent canvas — correct for Big Sur–Sequoia), Tahoe produced an
"icon inside an icon": our small squircle floating on the system's backing with fat borders,
which is what Launchpad showed. The fix for legacy icns on Tahoe is full-bleed artwork — the
background covers the entire canvas, so the system backing is never visible, and the motif sits
in the central safe zone so Tahoe's squircle corner mask doesn't crop it.

Trade-off: on pre-Tahoe macOS this icon renders as a nearly-square tile (small corner rounding)
instead of the standard margined squircle. Chosen deliberately — Tahoe is current.

The proper long-term fix is adopting the Icon Composer `.icon` format, which jpackage (and thus
Compose `nativeDistributions`) cannot bundle today.

## How it was derived

Composited from the margined master (`icons/openlog.png`, 1024×1024, solid squircle spanning
104..920 px) by drawing it scaled 1.30× (1331×1331) at offset (−172, −172 from the visual
top-left) onto a fresh 1024×1024 canvas:

- 1.30× makes the squircle background overshoot the canvas on all sides (full bleed);
- the offset recenters the motif (its optical center sat at ~(532, 533)) and keeps its
  bottom-right extreme (magnifier handle tip, motif bbox 196..868 × 206..860) about 8 px inside
  Tahoe's corner mask (radius ≈ 0.224 × 1024 at the corners).

Then the iconset was produced with `sips -z <n> <n>` for 16/32/64/128/256/512 (+@2x pairs) and
packed with `iconutil -c icns`. Verified by resolving the installed app's icon through
`NSWorkspace.iconForFile` before/after: before = double-framed with small motif, after =
artwork filling the system squircle.
