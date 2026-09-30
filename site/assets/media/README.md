# Website video media

Put original screen recordings in this folder as `.mov` files. They remain
local source material and are intentionally not published with the website.

The `web/` folder contains the optimized `.mp4` versions used by the site. The
deployment workflow includes those web-ready assets and excludes `.mov` files.

The `posters/` folder contains compressed WebP fallback stills for the web
videos. The existing demo posters use frames at 0.5 seconds from their cropped
clips; `demo-device-capture.webp` uses a representative frame at 11 seconds
from the full capture recording. Video pages and cards reference these stills
as posters so the layout has a useful fallback before playback.
