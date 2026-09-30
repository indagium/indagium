# Android Logcat search-language research — September 2026

## Method

This is a public-search evidence review, not a monthly-volume report. It compares current search
results, competitor page titles/descriptions, and public repository topics for Android Logcat
software. It deliberately avoids claims about search volume.

## Selected intent phrases

| Phrase | Intent | Evidence | Placement |
|---|---|---|---|
| Android Logcat viewer | Open/read/filter logs | Repeated in product and repository titles | Viewer page title and GitHub topic |
| Android Logcat analyzer | Investigate crashes, ANRs, and patterns | Used by a dedicated analyzer product and Indagium's analyzer route | Analyzer page title and GitHub topic |
| Android Logcat analysis tool | Find and explain a problem in a captured log | Matches the product's analysis-first positioning, with synchronized device capture as a supporting capability | Homepage/README/GitHub description |
| Android Logcat desktop app | Find a standalone desktop workflow | Competitors describe a desktop app explicitly | Homepage and viewer copy |
| Android logcat capture | Capture a connected Android device's live logs | Current Indagium 1.8.7 supports direct USB and wireless capture | Capture page title, description and workflow |
| Record Android logs and screen video | Reproduce a problem with both streams together | Exact user task and new app behavior | Capture-page heading and video walkthrough |
| USB logcat capture | Connect a device over USB and stream logcat | Supported device transport; explain authorization/platform-tools | Setup section on capture page |
| Wireless logcat | Pair Android 11+ Wireless debugging and capture over Wi-Fi | Supported QR/code pairing; explain network discovery limits | Capture page setup and troubleshooting |
| Synchronized logs and video | Reopen a portable capture and inspect the streams together | ZIP stores a synchronization anchor and links included assets on reopen | Capture page, user guide, release summary |

## Public evidence

- [Logcat Desk](https://logcatdesk.com/en/) describes itself as a desktop app for a local Android
  Logcat workflow and uses the `Android Logcat` + desktop-app framing.
- [LogDroid on Google Play](https://play.google.com/store/apps/details?id=dev.mjandroid.logdroid)
  leads with `Android Logcat Viewer & Debugging Tool` and pairs viewer terminology with crash,
  filtering, and export tasks.
- [android-logcat-analyzer](https://github.com/OutrageousStorm/android-logcat-analyzer) uses
  `Android Logcat Analyzer` for crash/ANR analysis intent.
- [andlogview](https://github.com/mlopatkin/andlogview) uses the discovery topics `android`,
  `logcat`, `android-tools`, and `logcat-viewer` for an Android log-viewing utility.

## Copy rules

- Keep viewer and analyzer terms on the pages that satisfy those specific intents.
- Use `analysis tool` and `desktop app` as supporting descriptors, not repeated keyword lists.
- Describe DLT only as an additional supported format; the README's supported-format section is
  the detailed source for its capabilities and limitations.


## Capture-intent guidance for 1.8.7

Use capture language where the page explains the real in-app flow: USB logcat capture, Android 11+
wireless debugging, QR/code pairing, simultaneous log and screen recording, and reopening the ZIP with
the included media linked. Do not present manual video attachment as automatic capture, or imply every
saved log has video. Settings and setup details belong in the guide rather than an overloaded title.

These phrase choices describe matching page content; no search-volume estimate or ranking outcome is
claimed. The proposed effect of missing device capture on earlier download volume or Gemini Log Viewers
ratings remains an unverified hypothesis. Google says its web search systems do not use the `meta
keywords` tag, so do not add keyword lists to page metadata. `llms.txt` is maintained as a concise
machine-readable product overview, not as a guarantee of Google indexing or Gemini visibility.

## Google Search documentation

- [Special tags Google understands](https://developers.google.com/search/docs/crawling-indexing/special-tags) documents that Google ignores the `meta keywords` tag.
- [Video structured data guidance](https://developers.google.com/search/docs/appearance/structured-data/video) informs the capture page's visible video and matching `VideoObject` metadata (thumbnail, content URL, duration, and upload date).
