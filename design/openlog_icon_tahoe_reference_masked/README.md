# openlog_icon_tahoe_reference_masked — the shipped macOS icon

**Used by:** `icons/openlog.icns` only. Linux (`icons/openlog.png`) and Windows
(`icons/openlog.ico`) keep the margined squircle variant. **Supersedes
`openlog_icon_fullbleed_tahoe/`** (kept for the research notes in its README).

## The problem, and what actually fixed it

On macOS 26 (Tahoe), our packaged app's icon rendered "double-framed" in Launchpad: our white
squircle floating small inside a second system-drawn squircle with fat borders. A long
bisection (see `openlog_icon_fullbleed_tahoe/README.md`) showed it was NOT corner transparency,
background gradients, anti-aliasing, or drop shadows per se — simple synthetic icons with each
of those properties rendered clean, while both our margined and full-bleed artwork kept getting
re-framed.

The breakthrough was a known-good reference specimen: Compose Desktop's default icon
(`default-compose-desktop-icon-mac.icns` inside `compose-gradle-plugin-1.7.3.jar`) — a plain
legacy icns, margined white squircle — renders perfectly clean through the same
LaunchServices path on the same machine. So Tahoe accepts *some* legacy squircles as-is;
it re-frames icons whose silhouette it doesn't recognize as a standard squircle. Our original
artwork's squircle had a soft drop-shadow band bleeding past its edge and slightly off-grid
edge alpha — enough for Tahoe to treat it as arbitrary art.

**Fix: give our icon the pixel-exact silhouette of the known-good reference.** Composite:

1. 1024×1024 canvas filled opaque white (kills the semi-transparent "glass"/shadow interior
   pixels our artwork had — the reference's interior is 100% opaque);
2. draw `icons/openlog.png` (the margined master) scaled 832/816 about center, so its squircle
   (solid span 104..920) overshoots the reference's (102..922) by ~6px per side;
3. draw the reference's 1024 rep with `NSCompositingOperationDestinationIn` — i.e. clip to the
   reference's exact anti-aliased alpha channel.

Iconset via `sips -z` (16/32/128/256/512 + @2x pairs), packed with `iconutil -c icns`.

## Verification

Rendered through `NSWorkspace.iconForFile` on a throwaway `.app` bundle with a fresh bundle id
(rules out LaunchServices icon caching): single clean squircle, no backing rim — identical
treatment to the Compose default icon. The original showed the double frame under the same
test.

Note for future icon work: any replacement artwork must keep a crisp squircle silhouette
(no shadow/glow crossing the squircle edge, fully opaque interior) or Tahoe will re-frame it
again. Masking against the reference alpha, as above, is the safe recipe.
