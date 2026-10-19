package com.indagium.ui

import com.indagium.model.AnnBlock

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
            )
        }
        AnnBlock.LogRefPresentation(options, rows, deltaAvailable = fields[5].toBoolean())
    }.getOrNull()
}
