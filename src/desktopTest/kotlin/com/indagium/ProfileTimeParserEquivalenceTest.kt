package com.indagium

import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.ParsedTime
import com.indagium.model.ProfileTimestampKind
import com.indagium.utils.ProfileTimeParser
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The timestamp parser skips formatters and attempts that cannot succeed instead of letting them throw.
 * It must return exactly what the original try-everything algorithm (kept below, unchanged) returns.
 */
class ProfileTimeParserEquivalenceTest {
    private fun profile(
        kind: ProfileTimestampKind = ProfileTimestampKind.AUTO,
        pattern: String = "",
        timezone: String = "",
    ) = LogFormatProfile(
        id = "time-test", name = "Time test", description = "", engine = LogFormatEngine.JSON_LINES,
        timestampKind = kind, timestampPattern = pattern, timezone = timezone,
    )

    private val profiles = listOf(
        profile(),
        profile(timezone = "Europe/Paris"),
        profile(ProfileTimestampKind.ISO_8601),
        profile(ProfileTimestampKind.CUSTOM, "dd/MMM/yyyy:HH:mm:ss xx"),
        profile(ProfileTimestampKind.CUSTOM, "dd/MM/yyyy:HH:mm:ss xx"),
        profile(ProfileTimestampKind.CUSTOM, "MMM d HH:mm:ss"),
        profile(ProfileTimestampKind.CUSTOM, "yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
        profile(ProfileTimestampKind.CUSTOM, "YYYY-'W'ww-e HH:mm"),
        profile(ProfileTimestampKind.CUSTOM, "yyyy-MM-dd HH:mm:ss VV"),
        profile(ProfileTimestampKind.CUSTOM, "yyyy-MM-dd HH:mm:ss", "Asia/Tokyo"),
        profile(ProfileTimestampKind.AUTO, "HH:mm:ss", "America/New_York"),
        profile(ProfileTimestampKind.TIME_OF_DAY),
    )

    private val values = listOf(
        "2026-05-04T12:30:00Z", "2026-05-04T12:30:00.123+02:00", "2026-05-04T12:30:00", "2026-05-04T12:30:00.5",
        "2026-05-04 12:30:00", "2026-05-04 12:30:00.123", "2026-05-04 12:30:00,123", "2026-02-30 12:30:00",
        "2026-02-30T12:30:00Z", "12:30:00", "12:30:00.123", "25:61:00", "Oct 10 13:55:36", "10/Oct/2000:13:55:36 -0700",
        "10/10/2000:13:55:36 -0700", "2000-10-10T13:55:36-07:00[America/Los_Angeles]", "2026-05-04T12:30:00+02:00[Europe/Paris]",
        "2026-W19-1 12:30", "2026-05-04 12:30:00 Europe/Paris", "2026-05-04T12:30:00.123+0200", "garbage", "", "2026",
        "2026-05-04", "2026-05-04T", " 2026-05-04 12:30:00", "2026-05-04 12:30:00 trailing", "1700000000",
        "2026-05-04T24:00:00Z", "2024-02-29T23:59:59Z", "2023-02-29T23:59:59Z", "2026-05-04T12:30:00.123456789Z", "2026-05-04T12:30Z",
    )

    @Test
    fun newParserReturnsExactlyWhatTheOriginalAlgorithmReturned() {
        var parsedCount = 0
        for (profile in profiles) {
            for (sourceClockId in listOf(null, "clock-a")) {
                val parser = ProfileTimeParser(profile, sourceClockId)
                for (value in values) {
                    for (override in listOf<String?>(null, "12:30:00.123")) {
                        val expected = OldTimeParser(profile, sourceClockId).parse(value, override)
                        val actual = parser.parse(value, override)
                        assertEquals(expected, actual, "profile=${profile.timestampKind}/${profile.timestampPattern} value='$value' override=$override")
                        if (actual.localTime != null) parsedCount++
                    }
                }
            }
        }
        assertTrue(parsedCount > 150, "the table must exercise many successful parses, got $parsedCount")
    }

    private class OldTimeParser(private val profile: LogFormatProfile, private val sourceClockId: String?) {
        private val zone = profile.timezone.takeIf(String::isNotBlank)?.let(ZoneId::of)
        private val clockIdentity = "${profile.timestampKind.name.lowercase()}:${sourceClockId ?: profile.id}"

        private val timeOfDay = listOf(
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT),
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT),
        )
        private val standard = listOf(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
        ) + timeOfDay
        private val candidates: List<DateTimeFormatter> = run {
            val custom = profile.timestampPattern.takeIf(String::isNotBlank)?.let { DateTimeFormatter.ofPattern(it, Locale.ROOT) }
            when (profile.timestampKind) {
                ProfileTimestampKind.CUSTOM -> listOfNotNull(custom)
                ProfileTimestampKind.TIME_OF_DAY -> timeOfDay
                else -> listOfNotNull(custom) + standard
            }
        }

        fun parse(value: String, timeOnlyOverride: String?): ParsedTime {
            val text = timeOnlyOverride ?: value
            val clock = sourceClockId ?: profile.id
            return candidates.firstNotNullOfOrNull { formatter ->
                offset(value, text, formatter) ?: localDateTime(value, text, formatter, clock) ?: localTime(value, text, formatter, clock)
            } ?: ParsedTime(value, clockIdentity = clockIdentity)
        }

        private fun offset(original: String, value: String, formatter: DateTimeFormatter): ParsedTime? = runCatching {
            val offset = OffsetDateTime.parse(value, formatter)
            val local = offset.atZoneSameInstant(zone ?: offset.offset).format(OUTPUT)
            ParsedTime(original, offset.toInstant().toEpochMilli(), local, "absolute-offset")
        }.getOrNull()

        private fun localDateTime(original: String, value: String, formatter: DateTimeFormatter, clock: String?): ParsedTime? = runCatching {
            val localDateTime = LocalDateTime.parse(value, formatter)
            val epoch = zone?.let { localDateTime.atZone(it).toInstant().toEpochMilli() }
            val wallMillis = if (zone == null) {
                localDateTime.toLocalDate().toEpochDay() * MILLIS_PER_DAY + localDateTime.toLocalTime().toNanoOfDay() / NANOS_PER_MILLI
            } else {
                null
            }
            ParsedTime(
                original, epoch, localDateTime.format(OUTPUT),
                zone?.let { "zone:${it.id}" } ?: "local:${clock ?: ""}", localWallMillis = wallMillis,
            )
        }.getOrNull()

        private fun localTime(original: String, value: String, formatter: DateTimeFormatter, clock: String?): ParsedTime? = runCatching {
            ParsedTime(original, null, LocalTime.parse(value, formatter).format(OUTPUT), "local:${clock ?: ""}")
        }.getOrNull()
    }

    private companion object {
        val OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        const val MILLIS_PER_DAY = 86_400_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
