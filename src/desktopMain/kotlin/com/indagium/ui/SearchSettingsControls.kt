package com.indagium.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.indagium.model.CtrlFTarget
import com.indagium.model.SearchScopeBehavior

private val SEARCH_SCOPE_BEHAVIORS = listOf(
    SearchScopeBehavior.FILTERED,
    SearchScopeBehavior.UNFILTERED,
    SearchScopeBehavior.TWO_SEARCHES,
    SearchScopeBehavior.LAST_USED,
    SearchScopeBehavior.FOLLOW_VIEW,
)
private val SEARCH_SCOPE_LABELS = listOf("Filtered", "Unfiltered", "Two searches", "Last search mode", "Auto-switch by open view")

private val CTRL_F_TARGETS = listOf(CtrlFTarget.FIND_BAR, CtrlFTarget.TAGS, CtrlFTarget.KEYWORD_REGEX)
private val CTRL_F_LABELS = listOf("Find bar", "Tags", "Regex")

/**
 * Search scope choice shared by Settings and the setup assistant. [onInteraction] lets a host
 * restore its keyboard focus after the clickable selector or its popup receives a pointer event.
 */
@Composable
internal fun SearchScopeBehaviorSelector(
    selected: SearchScopeBehavior,
    onSelected: (SearchScopeBehavior) -> Unit,
    onInteraction: () -> Unit = {},
) {
    SettingsDropdown(
        options = SEARCH_SCOPE_BEHAVIORS,
        selected = selected,
        label = { SEARCH_SCOPE_LABELS.getOrElse(SEARCH_SCOPE_BEHAVIORS.indexOf(it)) { SEARCH_SCOPE_LABELS.first() } },
        onSelect = onSelected,
        testTag = "search-scope-behavior-selector",
        modifier = Modifier.fillMaxWidth(),
        minWidth = 0.dp,
        optionTag = { "search-scope-behavior-option-${it.name.lowercase()}" },
        onInteraction = onInteraction,
    )
}

/**
 * Ctrl+F destination shared by Settings and setup: "Find bar | Tags | Regex" as toggle buttons. A legacy MESSAGE_RULE (not offered, still
 * routable) highlights no segment. [onInteraction] lets a host restore its keyboard focus after a click.
 */
@Composable
internal fun CtrlFTargetSelector(
    selected: CtrlFTarget,
    onSelected: (CtrlFTarget) -> Unit,
    onInteraction: () -> Unit = {},
) {
    SettingsSegmented(
        options = CTRL_F_LABELS,
        selectedIndices = setOfNotNull(CTRL_F_TARGETS.indexOf(selected).takeIf { it >= 0 }),
        onToggle = { index ->
            onSelected(CTRL_F_TARGETS[index])
            onInteraction()
        },
        modifier = Modifier.testTag("ctrl-f-target-selector"),
        segmentModifier = { index -> Modifier.testTag("ctrl-f-target-option-${CTRL_F_TARGETS[index].name.lowercase()}") },
    )
}
