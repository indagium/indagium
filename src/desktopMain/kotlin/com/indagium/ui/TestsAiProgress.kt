package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// The progress block of the Tests workspace's AI authoring actions (rewrite a recording, draft steps, improve steps). They take
// from seconds to minutes and have no other sign of life, so a running one shows a moving line, what is working and for how long,
// and - where the service can tell cheaply - the stage it is in.

private const val TICK_MS = 1_000L

/**
 * "Rewriting with Claude Code · opus · 1m 12s": [verb] (already in the -ing form), the provider profile and model that work
 * (either may be unknown) and how long they have been at it.
 */
internal fun aiProgressTitle(verb: String, profileName: String?, model: String?, elapsedMs: Long): String =
    listOfNotNull(
        verb + profileName?.takeIf(String::isNotBlank)?.let { " with $it" }.orEmpty(),
        model?.takeIf(String::isNotBlank),
        formatDuration(elapsedMs),
    ).joinToString(" · ")

/**
 * A moving line, the title of [aiProgressTitle] with a timer that ticks every second from the moment this block enters the
 * composition (show it only while the work runs) and, when the work reports one, its [stage]. [clock] is the time source.
 */
@Composable
internal fun AiWorkProgress(
    verb: String,
    profileName: String?,
    model: String?,
    stage: String?,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
) {
    val tc = tc()
    val startedAt = remember { clock() }
    var now by remember { mutableLongStateOf(startedAt) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = clock()
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IndeterminateLoadingLine(Modifier.fillMaxWidth())
        AppText(aiProgressTitle(verb, profileName, model, now - startedAt), color = tc.ts, fontSize = 11.sp, maxLines = 2)
        stage?.let { TestsHint(it) }
    }
}
