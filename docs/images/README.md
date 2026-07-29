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
| Window size | Same for every clip | So the guide does not jump between sizes as you scroll |
| Data | Use a synthetic or scrubbed log | These end up on a public repo |

**Before recording:** hide anything personal — recent-files list, source folder paths, real package
names, API keys in Settings. Sample logs suitable for this live in
`temp_visuals_not_for_git/` (git-ignored).

---

## Converting an existing recording

The `.mov` screen recordings in `temp_visuals_not_for_git/` convert cleanly with ffmpeg. The
two-pass palette approach is worth it — the default single-pass output looks noticeably worse at the
same size.

**To GIF:**

```bash
ffmpeg -i input.mov -vf "fps=12,scale=1280:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" -loop 0 output.gif
```

**Trim first, if the clip is long** (start at 4 s, keep 12 s):

```bash
ffmpeg -ss 4 -t 12 -i input.mov -vf "fps=12,scale=1280:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" -loop 0 output.gif
```

**To animated WebP** (smaller; same syntax in the guide):

```bash
ffmpeg -i input.mov -vf "fps=12,scale=1280:-1:flags=lanczos" -loop 0 -q:v 70 output.webp
```

**If a GIF is still too big,** in order of what costs least visually: drop to 10 fps, then scale to
1024 px, then trim seconds off the clip. Reducing colours (`palettegen=max_colors=128`) is the last
resort — UI screenshots have large flat areas that dither badly.

---

## Shot list

Names are exact — the guide links these paths.

| ID | Filename | Guide section | Length | What to show |
|---|---|---|---|---|
| **GIF-01** | `gif-01-quick-start.gif` | [2. Quick start](../USER_GUIDE.md#2-quick-start) | 20 s | The whole arc: drag a log onto the window → click one tag in the filter panel → right-click a noisy line and **Hide messages like this** → select a few rows → **Add annotation** → type a sentence → **Copy**. This is the guide's opening image; it should make the product obvious in one watch |
| **SHOT-02** | `shot-02-window-overview.png` | [3. The window](../USER_GUIDE.md#3-the-window) | static | Full window, one log open, filter panel and notes panel both visible, at least one sequence collapsed so group headers are visible, minimap on the right. **Add labelled callouts** for: toolbar, tab strip, filter panel, log view, minimap, right sidebar |
| **GIF-03** | `gif-03-bugreport-zip.gif` | [4. Opening logs](../USER_GUIDE.md#bug-report-archives) | 12 s | Drop a `.zip` bug report → the candidate picker lists logcat/ANR/video entries → tick a logcat entry and a video → both open, with the Video toolbar button now present |
| **GIF-04** | `gif-04-filtering.gif` | [6. Filtering](../USER_GUIDE.md#6-filtering) | 15 s | Start on a genuinely noisy log with the row count visible. Turn off **V** and **D** → click a tag → right-click a repeating line → **Hide messages like this**. Let the row count stay on screen throughout — the drop from six figures to three is the point of the clip |
| **GIF-05** | `gif-05-sequences.gif` | [8. Sequences](../USER_GUIDE.md#8-sequences) | 15 s | Right-click a repeating line → **Add as sequence** → the log folds into coloured group headers → click one header to expand it, showing the nested rows |
| **GIF-06** | `gif-06-compare.gif` | [14. Comparing two logs](../USER_GUIDE.md#14-comparing-two-logs) | 12 s | Two tabs open → **Compare** → turn on filter mirroring → scroll both panels, ideally to a point where one side has an entry the other lacks |
| **GIF-07** | `gif-07-notes.gif` | [15. Notes](../USER_GUIDE.md#15-notes-and-analysis-export) | 18 s | Select rows → **Add annotation** → type a sentence → paste a screenshot from the clipboard → reorder blocks with `Alt+↑` → **Preview** to show rendered Markdown → **Copy** |
| **GIF-08** | `gif-08-show-in-code.gif` | [16. Show in code](../USER_GUIDE.md#16-show-in-code) | 12 s | Right-click a log line → **Show code** → the popup opens on the exact method, path and line range visible → click **Open in editor**. Use a scrubbed or sample project — real source paths should not ship |
| **GIF-09** | `gif-09-video-sync.gif` | [20. Video sync](../USER_GUIDE.md#20-video-sync) | 18 s | Attach a recording → play to a visible failure → right-click the matching log row → **Video: Link to \<time\>** → enable **Follow log** → scroll the log and let the video track it → grab a frame into notes. The highest-value clip in the set; worth re-recording until it reads clearly |
| **GIF-10** | `gif-10-ai-assistant.gif` | [22. AI assistant](../USER_GUIDE.md#22-ai-assistant) | 20 s | Right-click a crash line → **Ask AI** → **Find root cause** → the **Investigation** section streams tool calls → a confirmation card appears → click **Allow** → the final answer arrives with clickable evidence cards. **Make sure no API key is visible.** A local LM Studio profile avoids the risk entirely |

---

## Checklist before committing

- [ ] Filename matches the table exactly (lowercase, hyphenated).
- [ ] Under 5 MB.
- [ ] No personal paths, package names, ticket ids, or credentials anywhere in frame.
- [ ] Same theme and window size as the other clips.
- [ ] Opened `docs/USER_GUIDE.md` in a Markdown preview and confirmed it renders.
