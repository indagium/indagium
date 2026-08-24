# Website video media

Put original screen recordings in this folder as `.mov` files. They remain
local source material and are intentionally not published with the website.

The `web/` folder contains the optimized `.mp4` versions used by the site. The
deployment workflow includes those web-ready assets and excludes `.mov` files.

The `posters/` folder contains compressed WebP stills captured at 0.5 seconds
from the cropped web clips. Homepage videos reference these stills as their
poster so the paper-colored layout has a useful fallback before playback.
