package com.indagium.ui

import com.indagium.generated.BuildInfo
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.HOLIDAY_THEMES
import com.indagium.update.compareVersions

/** One page per heading, preserving the release author's Markdown and the introductory paragraph. */
internal data class WhatsNewPage(val title: String, val markdown: String)

internal data class ReleaseAnnouncement(
    val version: String,
    val pages: List<WhatsNewPage>,
    val themes: List<HolidayPack>,
    val preview: Boolean = false,
)

internal enum class ReleaseAnnouncementStep { WHATS_NEW, HOLIDAY_THEMES }

/** Explicit development preview: example copy is never treated as a published release note. */
internal fun releaseAnnouncementPreview(): ReleaseAnnouncement = ReleaseAnnouncement(
    version = BuildInfo.APP_VERSION,
    pages = listOf(
        WhatsNewPage("Preview", "This is a preview. The next release's actual changes will appear here."),
        WhatsNewPage("Browsing changes", "Use Previous and Next to read each section, or Skip to continue."),
    ),
    themes = HOLIDAY_THEMES.keys.toList(),
    preview = true,
)

internal fun whatsNewPages(markdown: String): List<WhatsNewPage> {
    val pages = mutableListOf<WhatsNewPage>()
    var title = "Highlights"
    val body = StringBuilder()

    fun flush() {
        val content = body.toString().trim()
        if (content.isNotEmpty()) pages += WhatsNewPage(title, content)
        body.clear()
    }
    markdown.lineSequence().forEach { line ->
        when {
            line.startsWith("# ") -> Unit // the popup supplies its own title
            line.startsWith("## ") -> { flush(); title = line.removePrefix("## ").trim().ifEmpty { "Highlights" } }
            else -> body.appendLine(line)
        }
    }
    flush()
    return pages
}

/** Only numeric app versions become resource names. A missing note or theme manifest does not invent an announcement. */
internal fun loadReleaseAnnouncement(
    version: String = BuildInfo.APP_VERSION,
    readResource: (String) -> String? = { name -> ReleaseAnnouncement::class.java.getResourceAsStream("/$name")?.bufferedReader()?.use { it.readText() } },
    releaseVersions: List<String> = BuildInfo.RELEASE_VERSIONS.split(','),
): ReleaseAnnouncement {
    if (!version.matches(Regex("[0-9]+(?:\\.[0-9]+)*"))) return ReleaseAnnouncement(version, emptyList(), emptyList())
    val baseline = readResource("whats-new-start.txt")?.lineSequence()
        ?.map { it.substringBefore('#').trim() }
        ?.firstOrNull { it.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) }
    // Earlier release notes are historical: never present them as newly released features.
    val pages = if (baseline != null && compareVersions(version, baseline) > 0) {
        readResource("$version.md")?.let(::whatsNewPages).orEmpty()
    } else {
        emptyList()
    }
    val themes = releasedHolidayThemes(version, releaseVersions, readResource("seasonal-themes.txt").orEmpty())
    return ReleaseAnnouncement(version, pages, themes)
}

/** An `after` entry resolves to the first documented/build version after its baseline, never every later release. */
internal fun releasedHolidayThemes(version: String, releaseVersions: List<String>, metadata: String): List<HolidayPack> {
    val versions = (releaseVersions + version).filter { it.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) }.distinct()
    return metadata.lineSequence().map { it.substringBefore('#').trim().split(Regex("\\s+")) }
        .filter { it.size >= 3 }
        .filter { fields ->
            val (kind, declared) = fields
            if (!declared.matches(Regex("[0-9]+(?:\\.[0-9]+)*"))) false else when (kind) {
                "version" -> compareVersions(version, declared) == 0
                "after" -> versions.filter { compareVersions(it, declared) > 0 }
                    .minWithOrNull { a, b -> compareVersions(a, b) }?.let { compareVersions(version, it) == 0 } == true
                else -> false
            }
        }
        .flatMap { it.drop(2).asSequence() }
        .mapNotNull { name -> HolidayPack.entries.firstOrNull { it.name == name } }
        .filter { it in HOLIDAY_THEMES }.distinct().toList()
}

private fun announcementDue(seen: String, current: String): Boolean = seen.isBlank() || compareVersions(seen, current) < 0

internal fun initialReleaseStep(
    release: ReleaseAnnouncement,
    seenNotes: String,
    seenThemes: String,
): ReleaseAnnouncementStep? = when {
    release.pages.isNotEmpty() && announcementDue(seenNotes, release.version) -> ReleaseAnnouncementStep.WHATS_NEW
    release.themes.isNotEmpty() && announcementDue(seenThemes, release.version) -> ReleaseAnnouncementStep.HOLIDAY_THEMES
    else -> null
}

internal fun themeAnnouncementDue(release: ReleaseAnnouncement, seen: String): Boolean =
    release.themes.isNotEmpty() && announcementDue(seen, release.version)

/** Selecting a theme from the announcement applies it now, including when the previous mode was Automatic. */
internal fun HolidaySettings.applyAnnouncedTheme(pack: HolidayPack): HolidaySettings =
    copy(pack = pack, mode = com.indagium.model.HolidayMode.ALWAYS)
