# Documentation images — recording guide

This folder holds the screenshots and animations referenced by
[USER_GUIDE.md](../USER_GUIDE.md). Each entry below is already linked from the guide, so dropping a
correctly-named file here makes it appear with no edit to the Markdown.

Until a file exists, GitHub renders a broken-image placeholder and the alt text — the guide still
reads correctly.

---

## What GitHub can actually render

| Format | Renders in a repo `.md`? |
|---|---|
| **Animated GIF** | ✅ Yes — `![alt](images/name.gif)` |
| **Animated WebP** | ✅ Yes — 40–60 % smaller than GIF at the same quality |
| **APNG** | ✅ Yes |
| **PNG / JPEG** | ✅ Yes |
| **MP4 / MOV / WebM** | ❌ **No.** GitHub only plays video attachments uploaded to issues, pull requests, discussions, and releases — never from a file committed to the repo and referenced in Markdown |

So: **record whatever you like, but ship GIF or animated WebP.**

### If you would rather not commit the files

Attach the video to a GitHub Release or an issue comment once, and paste the resulting
`user-images.githubusercontent.com` URL into the guide. It renders on github.com — but breaks in
local Markdown previews, in IDEs, and offline. Keeping the files in-repo is the recommendation.

---

## Recording rules

| Rule | Value | Why |
|---|---|---|
| Duration | ≤ 15 s (≤ 20 s for the two long ones) | Nobody watches a long loop, and size scales with frames |
| Width | ≤ 1280 px | Wider adds weight without adding legibility on a docs page |
| Size | ≤ 5 MB each, ≤ 30 MB total | Beyond that, cloning the repo gets unpleasant |
| Frame rate | 10–12 fps | Plenty for UI motion; 30 fps triples the size for no gain |
| Audio | None | GIF has none, and the guide never relies on it |
| Loop | Yes | |
| Theme | Pick one theme and use it for every clip | Mixed themes across a page look accidental |
| Window size | Capture the Indagium window at a fixed 1440×900 geometry; export at 1280 px wide or less | So the guide does not jump between sizes as you scroll |
| Data | Use a synthetic or scrubbed log | These end up on a public repo |
| Apps | Indagium and the approved recording/export tool only | Do not open another app without approval |
| Saved filters | Keep only saved-filter names containing `test`; the section may remain expanded | Prevents old or private filters appearing in a clip |

**Before recording:** hide anything personal — recent-files list, source folder paths, real package
names, API keys in Settings. Use only the approved fixture files under
`/Users/romanarnaut/IdeaProjects/UsbLogTestApp/fixtures`. Raw screen recordings may be staged in
the git-ignored `temp_visuals_not_for_git/` directory, but they must not be committed.
Use Indagium's Open action to prepare fixture data before capture; never record a native picker,
Open Recent list, desktop, or another application.

---

## Converting an existing recording

The `.mov` screen recordings in `temp_visuals_not_for_git/` convert cleanly with ffmpeg. The
two-pass palette approach is worth it — the default single-pass output looks noticeably worse at the
same size.

**To GIF:**

```bash
ffmpeg -i input.mov -vf "fps=10,scale=1280:-1:flags=lanczos,format=rgb24,split[a][b];[a]palettegen=stats_mode=full:max_colors=80:reserve_transparent=0[p];[b][p]paletteuse=dither=none:diff_mode=rectangle" -loop 0 -an output.gif
```

**Trim first, if the clip is long** (start at 4 s, keep 12 s):

```bash
ffmpeg -ss 4 -t 12 -i input.mov -vf "fps=10,scale=1280:-1:flags=lanczos,format=rgb24,split[a][b];[a]palettegen=stats_mode=full:max_colors=80:reserve_transparent=0[p];[b][p]paletteuse=dither=none:diff_mode=rectangle" -loop 0 -an output.gif
```

**To animated WebP** (smaller; same syntax in the guide):

```bash
ffmpeg -i input.mov -vf "fps=12,scale=1280:-1:flags=lanczos" -loop 0 -q:v 70 output.webp
```

**If a GIF is still too big,** in order of what costs least visually: scale to 1024 px, then trim
seconds off the clip. Reducing the palette below 80 colours is the last resort — UI screenshots have
large flat areas that dither badly. Keeping `stats_mode=full`, an opaque RGB input, and `dither=none`
prevents static teal controls from being quantized to black.

---

## Shot list

Names are exact — the guide links these paths.

| ID | Filename | Guide section | Length | What to show |
|---|---|---|---|---|
| **GIF-01** | `gif-01-quick-start.gif` | [2. Quick start](../USER_GUIDE.md#2-quick-start) | 20 s | The whole arc: drag a log onto the window → click one tag in the filter panel → right-click a noisy line and **Hide messages like this** → select a few rows → **Add annotation** → type a sentence → **Copy**. This is the guide's opening image; it should make the product obvious in one watch |
| **SHOT-02** | `shot-02-window-overview.png` | [3. The window](../USER_GUIDE.md#3-the-window) | static | Full window, one log open, filter panel and notes panel both visible, at least one sequence collapsed so group headers are visible, minimap on the right. Saved filters are expanded and contain only test filters. |
| **GIF-03** | `gif-03-bugreport-zip.gif` | [4. Opening logs](../USER_GUIDE.md#bug-report-archives) | 6.1 s | Drop a `.zip` bug report → the candidate picker lists logcat/ANR/video entries → tick a logcat entry and a video → both open, with the Video toolbar button now present |
| **GIF-04** | `gif-04-filtering.gif` | [6. Filtering](../USER_GUIDE.md#6-filtering) | 15 s | Start on a genuinely noisy log with the row count visible. Turn off **V** and **D** → click a tag → right-click a repeating line → **Hide messages like this**. Let the row count stay on screen throughout — the drop from six figures to three is the point of the clip |
| **GIF-05** | `gif-05-sequences.gif` | [8. Sequences](../USER_GUIDE.md#8-sequences) | 15 s | Right-click a repeating line → **Add as sequence** → the log folds into coloured group headers → click one header to expand it, showing the nested rows |
| **GIF-06** | `gif-06-compare.gif` | [14. Comparing two logs](../USER_GUIDE.md#14-comparing-two-logs) | 12.4 s | Two tabs open → **Compare** → turn on filter mirroring → scroll both panels, ideally to a point where one side has an entry the other lacks |
| **GIF-07** | `gif-07-notes.gif` | [15. Notes](../USER_GUIDE.md#15-notes-and-analysis-export) | 15 s | Select rows → **Add annotation** → type a sentence → add and reorder a text block → **Preview** to show rendered Markdown → **Copy** |
| **GIF-08** | `gif-08-show-in-code.gif` | [16. Show in code](../USER_GUIDE.md#16-show-in-code) | 5.9 s | Right-click a log line → **Show code** → the popup opens on the exact method, path and line range visible. The recording uses a fixture demonstration; do not expose unrelated source paths |
| **GIF-09** | `gif-09-video-sync.gif` | [20. Video sync](../USER_GUIDE.md#20-video-sync) | 15 s | Attach a recording → play to a visible failure → right-click the matching log row → **Video: Link to \<time\>** → enable **Follow log** → scroll the log and let the video track it → grab a frame into notes |
| **GIF-10** | `gif-10-ai-assistant.gif` | [22. AI assistant](../USER_GUIDE.md#22-ai-assistant) | 19 s | Right-click a crash line → **Ask AI** → **Find root cause** → watch the **Investigation** section stream tool activity while the analysis runs |
| **GIF-10 result** | `gif-10-ai-assistant-result.gif` | [22. AI assistant](../USER_GUIDE.md#22-ai-assistant) | 12.8 s | Show the completed analysis in Markdown Preview, including evidence, then click **Copy**. **Make sure no API key is visible.** |
| **GIF-11** | `gif-11-sequence-diagram-workspace.gif` | [15. Notes — sequence-diagram workspaces](../USER_GUIDE.md#sequence-diagram-workspaces) | 20 s | Open a diagram from selected rows → inspect the message queue and canvas → select linked queue/canvas content → open image/source export choices |

---

## Checklist before committing

- [ ] Filename matches the table exactly (lowercase, hyphenated).
- [ ] Under 5 MB.
- [ ] No personal paths, package names, ticket ids, or credentials anywhere in frame.
- [ ] Same theme and window size as the other clips.
- [ ] Opened `docs/USER_GUIDE.md` in a Markdown preview and confirmed it renders.
