# Website video media

Put original screen recordings in this folder as `.mov` files. They remain
local source material and are intentionally not published with the website.

The `web/` folder contains the optimized `.mp4` versions used by the site. The
deployment workflow includes those web-ready assets and excludes `.mov` files.

The `posters/` folder contains compressed WebP fallback stills for the web
videos. The existing demo posters use frames at 0.5 seconds from their cropped
clips. `demo-device-capture.webp` is a representative frame at 11 seconds from
the capture clip, and `demo-device-capture-launcher.webp` shows the New capture
launcher at 0.5 seconds. Both capture images use the same crop as the web clip.
Video pages and cards reference these stills as posters or setup illustrations.

The device-capture source frame includes a 3104x2024 desktop recording. Its web
video removes the surrounding black desktop margins with a 2880x1800 crop at
x=112, y=76, then scales to 1920x1200. The clip is H.264, silent, and 30 fps;
the launcher and video-poster WebPs are 1600x1000.
