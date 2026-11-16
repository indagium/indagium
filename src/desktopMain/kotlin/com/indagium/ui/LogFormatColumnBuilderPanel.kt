@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.utils.ColumnFormat
import com.indagium.utils.ColumnKind
import com.indagium.utils.ColumnSeparator
import com.indagium.utils.PieceWrap
import com.indagium.utils.SamplePiece
import com.indagium.utils.buildColumnFormat
import com.indagium.utils.guessColumnKinds
import com.indagium.utils.splitSampleColumns

private val COLUMN_CARD_WIDTH = 150.dp
private val COLUMN_CARD_SHAPE = RoundedCornerShape(6.dp)
private const val PIECE_PREVIEW_CHARS = 28
private val SEPARATOR_LABELS = listOf("Spaces", "Tab", "Other")

/**
 * "Set up from a sample line": the user pastes one line, says what each piece is, and gets the
 * record regex and field numbers filled in. It exists so that making a format needs no regex.
 * [onApply] receives the generated parts and the sample line (the caller puts it in the preview box).
 */
@Composable
internal fun LogFormatColumnBuilderPanel(
    profileId: String,
    reclaimFocus: () -> Unit,
    onApply: (ColumnFormat, String) -> Unit,
) {
    val tc = tc()
    var sampleLine by remember(profileId) { mutableStateOf("") }
    var separator by remember(profileId) { mutableStateOf(ColumnSeparator.WHITESPACE) }
    var customSeparator by remember(profileId) { mutableStateOf("|") }
    var splitChars by remember(profileId) { mutableStateOf("") }
    var applied by remember(profileId) { mutableStateOf(false) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }

    val pieces = remember(sampleLine, separator, customSeparator, splitChars) {
        splitSampleColumns(sampleLine, separator, customSeparator, splitChars)
    }
    val guessed = remember(pieces) { guessColumnKinds(pieces) }
    // The user's corrections, by column position. They start again when the line is cut differently.
    val chosen = remember(pieces) { mutableStateMapOf<Int, ColumnKind>() }
    val extraNames = remember(pieces) { mutableStateMapOf<Int, String>() }
    val kinds = pieces.indices.map { chosen[it] ?: guessed[it] }

    fun changed() {
        applied = false
        error = null
    }

    SettingsGroup("Set up from a sample line") {
        SettingsStackedRow(
            "Sample line",
            "Paste one typical line of the log. It is cut into columns below; say what each column is, and the parsing rule is written for you.",
        ) {
            SettingsTextField(
                sampleLine,
                { sampleLine = it; changed() },
                placeholder = "ERROR Connection lost 2024-03-01T10:00:00Z",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-builder-sample",
            )
        }
        SettingsStackedRow(
            "Columns are separated by",
            "Text in [square] or (round) brackets counts as one column. A level glued to a tag, as in D/MyTag, is split for you. " +
                "If other columns are glued together, type the character between them under \"Also split at\".",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsSegmented(
                    options = SEPARATOR_LABELS,
                    selectedIndices = setOf(separator.ordinal),
                    onToggle = { index ->
                        separator = ColumnSeparator.entries[index]
                        changed()
                        reclaimFocus()
                    },
                    segmentModifier = { index -> Modifier.testTag("settings-log-format-builder-separator-$index") },
                )
                if (separator == ColumnSeparator.CUSTOM) {
                    SettingsTextField(
                        customSeparator,
                        { customSeparator = it; changed() },
                        placeholder = "|",
                        monospace = true,
                        modifier = Modifier.width(80.dp),
                        testTag = "settings-log-format-builder-custom-separator",
                    )
                }
                if (separator == ColumnSeparator.WHITESPACE) {
                    AppText("Also split at", color = tc.ts, fontSize = 11.sp)
                    SettingsTextField(
                        splitChars,
                        { splitChars = it; changed() },
                        placeholder = "/",
                        monospace = true,
                        modifier = Modifier.width(70.dp),
                        testTag = "settings-log-format-builder-split-chars",
                    )
                }
            }
        }
        if (pieces.isNotEmpty()) {
            SettingsStackedRow(
                "What each column is",
                "Choose Message for every word of the text: lines with more or fewer words still match. " +
                    "A date and a time next to each other are one timestamp.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pieces.forEachIndexed { index, piece ->
                            ColumnCard(
                                index = index,
                                piece = piece,
                                kind = kinds[index],
                                extraName = extraNames[index].orEmpty(),
                                onKind = { kind ->
                                    chosen[index] = kind
                                    changed()
                                    reclaimFocus()
                                },
                                onExtraName = { name ->
                                    extraNames[index] = name
                                    changed()
                                },
                                reclaimFocus = reclaimFocus,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsButton(
                            "Use these columns",
                            {
                                buildColumnFormat(pieces, kinds, separator, customSeparator, extraNames.toMap()).fold(
                                    onSuccess = { format ->
                                        onApply(format, sampleLine)
                                        applied = true
                                        error = null
                                    },
                                    onFailure = { failure ->
                                        applied = false
                                        error = failure.message ?: "These columns cannot be turned into a format."
                                    },
                                )
                                reclaimFocus()
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.testTag("settings-log-format-builder-apply"),
                        )
                        error?.let {
                            AppText(
                                it,
                                color = DANGER_RED,
                                fontSize = 11.sp,
                                maxLines = 2,
                                modifier = Modifier.testTag("settings-log-format-builder-error"),
                            )
                        }
                        if (applied) {
                            AppText(
                                "Done. The parsing rule and field numbers below were filled in. Press Preview to check, then Save.",
                                color = tc.ts,
                                fontSize = 11.sp,
                                maxLines = 2,
                                modifier = Modifier.testTag("settings-log-format-builder-applied"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnCard(
    index: Int,
    piece: SamplePiece,
    kind: ColumnKind,
    extraName: String,
    onKind: (ColumnKind) -> Unit,
    onExtraName: (String) -> Unit,
    reclaimFocus: () -> Unit,
) {
    val tc = tc()
    val shown = when (piece.wrap) {
        PieceWrap.SQUARE -> "[${piece.text}]"
        PieceWrap.ROUND -> "(${piece.text})"
        PieceWrap.NONE -> piece.text
    } + if (piece.trailingColon) ":" else ""
    Column(
        Modifier.width(COLUMN_CARD_WIDTH).background(tc.p2, COLUMN_CARD_SHAPE).border(1.dp, tc.br, COLUMN_CARD_SHAPE).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AppText(
            "Column ${index + 1}" + piece.joinBefore?.let { " · after $it" }.orEmpty(),
            color = tc.ts,
            fontSize = 10.sp,
        )
        AppText(
            if (shown.length > PIECE_PREVIEW_CHARS) shown.take(PIECE_PREVIEW_CHARS) + "…" else shown.ifEmpty { "(empty)" },
            color = tc.tx,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag("settings-log-format-builder-piece-$index"),
        )
        SettingsDropdown(
            options = ColumnKind.entries,
            selected = kind,
            label = ColumnKind::label,
            onSelect = onKind,
            testTag = "settings-log-format-builder-kind-$index",
            modifier = Modifier.fillMaxWidth(),
            minWidth = 60.dp,
            optionTag = { "settings-log-format-builder-kind-$index-${it.name.lowercase()}" },
            onInteraction = reclaimFocus,
        )
        if (kind == ColumnKind.EXTRA) {
            SettingsTextField(
                extraName,
                onExtraName,
                placeholder = "Field name",
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-builder-extra-$index",
            )
        }
    }
}
