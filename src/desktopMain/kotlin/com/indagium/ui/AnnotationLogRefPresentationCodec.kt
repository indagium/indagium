package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.model.LogDisplayField

/** Positional codec for the presentation field appended to LogRef block tokens. */
internal object AnnotationLogRefPresentationCodec {
    fun encode(presentation: AnnBlock.LogRefPresentation): String = tokenFields(
        presentation.options.timeDelta.toString(),
        presentation.options.rowNumber.toString(),
        presentation.options.pidTid.toString(),
        presentation.options.processName.toString(),
        presentation.options.allProcessNameCandidates.toString(),
        presentation.deltaAvailable.toString(),
        presentation.rows.joinToString(";") { row ->
            tokenFields(
                row.logId.toString(),
                row.rowNumberText,
                row.timeDeltaText.orEmpty(),
                row.displayedProcessName.orEmpty(),
                row.processNameCandidate.orEmpty(),
                row.displayColumns.joinToString(";") { column ->
                    tokenFields(column.id, column.label, column.value).b64()
                },
                row.profileProjectionPresent.toString(),
            ).b64()
        },
    )

    fun decode(token: String): AnnBlock.LogRefPresentation? = runCatching {
        val fields = token.tokenFields()
        if (fields.size < 7) return@runCatching null
        val options = AnnBlock.LogRefPresentationOptions(
            timeDelta = fields[0].toBoolean(),
            rowNumber = fields[1].toBoolean(),
            pidTid = fields[2].toBoolean(),
            processName = fields[3].toBoolean(),
            allProcessNameCandidates = fields[4].toBoolean(),
        )
        val rows = fields[6].takeIf { it.isNotBlank() }?.split(";").orEmpty().mapNotNull { encodedRow ->
            val rowFields = runCatching { encodedRow.unb64().tokenFields() }.getOrNull() ?: return@mapNotNull null
            val logId = rowFields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            AnnBlock.LogRefRowPresentation(
                logId = logId,
                rowNumberText = rowFields.getOrElse(1) { logId.toString() },
                timeDeltaText = rowFields.getOrElse(2) { "" }.takeIf { it.isNotEmpty() },
                displayedProcessName = rowFields.getOrElse(3) { "" }.takeIf { it.isNotEmpty() },
                processNameCandidate = rowFields.getOrElse(4) { "" }.takeIf { it.isNotEmpty() },
                displayColumns = rowFields.getOrNull(5)?.takeIf { it.isNotBlank() }
                    ?.split(";")
                    ?.mapNotNull { encodedColumn ->
                        runCatching {
                            val column = encodedColumn.unb64().tokenFields()
                            LogDisplayField(column[0], column[1], column[2])
                        }.getOrNull()
                    }.orEmpty(),
                profileProjectionPresent = rowFields.getOrNull(6)?.toBoolean() ?: false,
            )
        }
        AnnBlock.LogRefPresentation(options, rows, deltaAvailable = fields[5].toBoolean())
    }.getOrNull()
}
