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

## Homepage workspace screenshot and workflow

The homepage workspace screenshot and workflow recording were supplied as
Desktop originals:

- `Screenshot 2026-10-14 at 19.51.12.png`
- `Screen Recording 2026-10-14 at 19.48.12.mov`

The originals remain on the source machine and are not copied or published.
The screenshot uses a 3840x2188 crop at x=112, y=76. The homepage preview is
1920x1094 (`posters/homepage-workspace.webp`), and the full-resolution zoom
image is 3840x2188 (`posters/homepage-workspace-full.webp`).

The workflow recording is a 1920x1094 H.264 MP4, silent, 30 fps, with faststart
enabled; its duration is 43.6 seconds (`web/homepage-workflow.mp4`). The
1600x912 WebP poster (`posters/homepage-workflow.webp`) is from the 0.5-second
frame.

## AI tests page videos and screenshots

The AI tests walkthroughs use two Desktop originals, both 3840x2192; the full
desktop frames are used without cropping. The original `.mov` files are not
copied or published:

- `Screen Recording 2026-10-14 at 20.15.04.mov` — recording and reviewing steps
- `Screen Recording 2026-10-14 at 20.16.36.mov` — running a test case

The web videos scale the full frame to 1920x1096 and use H.264, silent, 30 fps,
faststart MP4 encoding:
`web/ai-tests-recording.mp4` (60.333008 seconds) and
`web/ai-tests-running.mp4` (101.633333 seconds). Their 1600x914 WebP posters
are `posters/ai-tests-recording.webp` (frame at 13 seconds) and
`posters/ai-tests-running.webp` (frame at 37 seconds).

The full-resolution screenshot `posters/ai-tests-authoring-full.webp` is a
3840x2192 frame at 42 seconds from the recording walkthrough; its 1920x1096
preview is `posters/ai-tests-authoring.webp`. The run results image
`posters/ai-tests-results-full.webp` is a 3840x2192 frame at 100 seconds from
the test run; its 1920x1096 preview is `posters/ai-tests-results.webp`.

## Sequence diagram workspace walkthrough

The sequence diagram feature page reuses the public guide animation
`docs/images/gif-11-sequence-diagram-workspace.gif` as its source. The 1280x726,
10 fps, 20-second GIF was converted to `web/sequence-diagram-workspace.mp4`
using H.264, silent audio, and faststart; the poster
`posters/sequence-diagram-workspace.webp` is a WebP still from 8 seconds. The
page hero and social preview use `posters/sequence-diagram-complete.webp`, a
WebP still from 14 seconds where the diagram has two lifelines, four message
groups, and no unresolved targets; it has no export popover over the canvas. The
guide GIF remains the source used by repository Markdown; no new screen
recording was added.
