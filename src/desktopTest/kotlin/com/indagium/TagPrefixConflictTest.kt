package com.indagium

import com.indagium.ui.AppState
import com.indagium.ui.PendingTagPrefixConflict
import com.indagium.ui.tagPrefixConflictsOnAddingPrefix
import com.indagium.ui.tagPrefixConflictsOnCheckingTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers Wave 2.2: a package prefix (Filter.pkgPrefixes) admits everything under it only while no
 * specific child tag under it is separately selected (Filter.kt's passesTagOrKeywordFilter — the
 * `scopedActiveTags.isEmpty()` condition). Adding a prefix while a child tag is already active, or
 * checking a child tag while its prefix is already active, silently narrows the OTHER selection
 * with no visible warning — AppState.toggleTag/addPkgPrefix detect this before mutating and raise
 * [PendingTagPrefixConflict] instead.
 */
class TagPrefixConflictTest {
    // ── Pure detector functions — both orderings, positive and negative ────────────────────────

    @Test
    fun addingPrefixConflictsWhenAChildTagIsAlreadyActive() {
        assertTrue(tagPrefixConflictsOnAddingPrefix("com.myapp.pkg", setOf("com.myapp.pkg.Example1")))
    }

    @Test
    fun addingPrefixDoesNotConflictWhenNoActiveTagFallsUnderIt() {
        assertFalse(tagPrefixConflictsOnAddingPrefix("com.myapp.pkg", setOf("com.other.Thing")))
        assertFalse(tagPrefixConflictsOnAddingPrefix("com.myapp.pkg", emptySet()))
        // A tag that merely starts with the same characters but isn't a dotted child must not
        // false-positive (e.g. "com.myapp.pkgOther" is not under "com.myapp.pkg").
        assertFalse(tagPrefixConflictsOnAddingPrefix("com.myapp.pkg", setOf("com.myapp.pkgOther")))
    }

    @Test
    fun checkingTagConflictsWhenItFallsUnderAnAlreadyActivePrefixStillInAdmitAllMode() {
        // No other activeTag is scoped under the prefix yet, so it's still in "admit everything"
        // mode — checking this tag is the transition that would narrow it. Real conflict.
        assertTrue(tagPrefixConflictsOnCheckingTag("com.myapp.pkg.Example1", setOf("com.myapp.pkg"), emptySet()))
    }

    @Test
    fun checkingTagDoesNotConflictWhenNoActivePrefixCoversIt() {
        assertFalse(tagPrefixConflictsOnCheckingTag("com.myapp.pkg.Example1", setOf("com.other"), emptySet()))
        assertFalse(tagPrefixConflictsOnCheckingTag("com.myapp.pkg.Example1", emptySet(), emptySet()))
    }

    @Test
    fun checkingTagDoesNotConflictWhenThePrefixIsAlreadyNarrowedBySomeOtherActiveTag() {
        // Regression for the false-positive the coordinator's probe caught: the prefix is
        // ALREADY narrowed (some other activeTag is already scoped under it), so it's no longer
        // in "admit everything" mode. Checking one more tag under it only widens the already-
        // narrowed set — nothing about the prefix's behavior changes, so this must NOT conflict.
        assertFalse(
            tagPrefixConflictsOnCheckingTag(
                "com.myapp.pkg.Example2",
                setOf("com.myapp.pkg"),
                setOf("com.myapp.pkg.Example1"),
            ),
        )
    }

    @Test
    fun theTagEqualingThePrefixItselfCounts() {
        // tagMatchesPrefix's own `tag == prefix` branch — the exact-match case, not just the
        // dotted-child case, must be treated as a real conflict in both directions.
        assertTrue(tagPrefixConflictsOnAddingPrefix("com.myapp.pkg", setOf("com.myapp.pkg")))
        assertTrue(tagPrefixConflictsOnCheckingTag("com.myapp.pkg", setOf("com.myapp.pkg"), emptySet()))
    }

    // ── AppState integration: toggleTag ─────────────────────────────────────────────────────────

    @Test
    fun toggleTagRaisesAConflictInsteadOfMutatingWhenAPrefixAlreadyCoversIt() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        state.toggleTag(tabId, "com.myapp.pkg.Example1")

        assertEquals(
            PendingTagPrefixConflict.CheckingTag(tabId, "com.myapp.pkg.Example1", setOf("com.myapp.pkg")),
            state.pendingTagPrefixConflict,
        )
        // Must NOT have mutated yet — the tag is not active until the user resolves the dialog.
        assertFalse("com.myapp.pkg.Example1" in state.tabs.single().filter.activeTags)
    }

    @Test
    fun secondTagUnderAnAlreadyNarrowedPrefixDoesNotRePrompt() {
        // Regression for the coordinator's false-positive report: once a prefix has been narrowed
        // by a RESOLVED conflict, checking a further tag under the same prefix must not re-prompt
        // — the prefix's behavior doesn't change (it only widens the already-narrowed set), and
        // the user already made this exact "keep it narrowed" decision once. Without this, every
        // tag click under an active prefix would be a modal, which the plan explicitly forbids.
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        // First tag: this is the genuine, one-time conflict — the prefix is still in "admit
        // everything" mode at this point, so it must still prompt (folds in the probe's own
        // "the FIRST tag still does" assertion alongside the fix for the second).
        state.toggleTag(tabId, "com.myapp.pkg.Example1")
        assertEquals(
            PendingTagPrefixConflict.CheckingTag(tabId, "com.myapp.pkg.Example1", setOf("com.myapp.pkg")),
            state.pendingTagPrefixConflict,
            "the first tag under a still-unnarrowed prefix must still prompt",
        )
        state.resolveTagPrefixConflict(showWholePackage = false, dontAskAgain = false)
        assertTrue("com.myapp.pkg.Example1" in state.tabs.single().filter.activeTags)

        // Second tag under the same, now-already-narrowed prefix: no semantic surprise left to
        // warn about, so this must go straight through with no dialog.
        state.toggleTag(tabId, "com.myapp.pkg.Example2")

        assertNull(state.pendingTagPrefixConflict, "re-prompted on an already-narrowed prefix")
        assertTrue("com.myapp.pkg.Example2" in state.tabs.single().filter.activeTags)
        assertEquals(setOf("com.myapp.pkg.Example1", "com.myapp.pkg.Example2"), state.tabs.single().filter.activeTags)
    }

    @Test
    fun afterChoosingWholePackageTheNextTagCheckPromptsAgainBecauseThePrefixIsBackToAdmitAllMode() {
        // Mirror of the above: "Show the whole package" clears every scoped activeTag under the
        // prefix, putting it back into "admit everything" mode — so the NEXT tag checked under it
        // is once again a genuine narrowing event and SHOULD prompt. Pins that the fix didn't
        // overcorrect into never prompting again for a given prefix.
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        state.toggleTag(tabId, "com.myapp.pkg.Example1")
        state.resolveTagPrefixConflict(showWholePackage = true, dontAskAgain = false)
        // "Whole package" clears the scoped tag rather than adding it — verify the prefix is
        // genuinely back to admitting everything before checking the re-prompt.
        assertTrue(state.tabs.single().filter.activeTags.isEmpty())

        state.toggleTag(tabId, "com.myapp.pkg.Example2")

        assertEquals(
            PendingTagPrefixConflict.CheckingTag(tabId, "com.myapp.pkg.Example2", setOf("com.myapp.pkg")),
            state.pendingTagPrefixConflict,
            "the prefix is back to admit-all mode, so the next tag check must conflict again",
        )
        assertFalse("com.myapp.pkg.Example2" in state.tabs.single().filter.activeTags)
    }

    @Test
    fun toggleTagDoesNotRaiseAConflictWhenThereIsNone() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.toggleTag(tabId, "com.other.Thing")

        assertNull(state.pendingTagPrefixConflict)
        assertTrue("com.other.Thing" in state.tabs.single().filter.activeTags)
    }

    @Test
    fun uncheckingATagNeverRaisesAConflictEvenUnderAnActivePrefix() {
        // The removing transition only ever widens (or is neutral) — never narrows — so it must
        // never trigger the dialog even when a covering prefix happens to be active.
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.myapp.pkg")
        // Force the tag active directly (bypassing toggleTag's own conflict gate) to set up the
        // "already checked, now unchecking" scenario cleanly.
        state.upFlt(tabId) { it.copy(activeTags = it.activeTags + "com.myapp.pkg.Example1") }

        state.toggleTag(tabId, "com.myapp.pkg.Example1")

        assertNull(state.pendingTagPrefixConflict)
        assertFalse("com.myapp.pkg.Example1" in state.tabs.single().filter.activeTags)
    }

    // ── AppState integration: addPkgPrefix ──────────────────────────────────────────────────────

    @Test
    fun addPkgPrefixRaisesAConflictInsteadOfMutatingWhenAChildTagIsAlreadyActive() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.toggleTag(tabId, "com.myapp.pkg.Example1")

        state.addPkgPrefix(tabId, "com.myapp.pkg")

        assertEquals(PendingTagPrefixConflict.AddingPrefix(tabId, "com.myapp.pkg"), state.pendingTagPrefixConflict)
        // Must NOT have mutated yet — the prefix is not added until the user resolves the dialog.
        assertTrue("com.myapp.pkg" !in state.tabs.single().filter.pkgPrefixes)
    }

    @Test
    fun addPkgPrefixDoesNotRaiseAConflictWhenThereIsNone() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id

        state.addPkgPrefix(tabId, "com.myapp.pkg")

        assertNull(state.pendingTagPrefixConflict)
        assertTrue("com.myapp.pkg" in state.tabs.single().filter.pkgPrefixes)
    }

    // ── Resolution ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun resolvingWithOnlySelectedClassesKeepsTodaysNarrowingBehaviorForAddingPrefix() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.toggleTag(tabId, "com.myapp.pkg.Example1")
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        state.resolveTagPrefixConflict(showWholePackage = false, dontAskAgain = false)

        val filter = state.tabs.single().filter
        assertNull(state.pendingTagPrefixConflict)
        assertTrue("com.myapp.pkg" in filter.pkgPrefixes)
        // The narrowing is preserved — the already-selected class stays selected on its own.
        assertEquals(setOf("com.myapp.pkg.Example1"), filter.activeTags)
    }

    @Test
    fun resolvingWithWholePackageClearsTheScopedActiveTagsForAddingPrefix() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.toggleTag(tabId, "com.myapp.pkg.Example1")
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        state.resolveTagPrefixConflict(showWholePackage = true, dontAskAgain = false)

        val filter = state.tabs.single().filter
        assertNull(state.pendingTagPrefixConflict)
        assertTrue("com.myapp.pkg" in filter.pkgPrefixes)
        // The scoped activeTag under the new prefix is cleared, so the prefix admits the whole
        // package again (Filter.kt's scopedActiveTags.isEmpty() rule).
        assertTrue(filter.activeTags.isEmpty())
    }

    @Test
    fun resolvingWithOnlySelectedClassesKeepsTodaysNarrowingBehaviorForCheckingTag() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.myapp.pkg")
        state.toggleTag(tabId, "com.myapp.pkg.Example1")

        state.resolveTagPrefixConflict(showWholePackage = false, dontAskAgain = false)

        val filter = state.tabs.single().filter
        assertNull(state.pendingTagPrefixConflict)
        // The tag is now active, narrowing the prefix down to just this class — today's behavior.
        assertEquals(setOf("com.myapp.pkg.Example1"), filter.activeTags)
    }

    @Test
    fun resolvingWithWholePackageDoesNotAddTheTagAndClearsAnyOtherScopedActiveTags() {
        // The fixed detector no longer raises a CheckingTag conflict whose recorded prefix already
        // has a DIFFERENT scoped activeTag — that prefix is already narrowed and excluded from the
        // conflict set by construction (see tagPrefixConflictsOnCheckingTag /
        // secondTagUnderAnAlreadyNarrowedPrefixDoesNotRePrompt). So this constructs the pending
        // conflict directly, exercising resolveTagPrefixConflict's CheckingTag branch in isolation,
        // to keep proving it clears every activeTag scoped under EVERY prefix the pending conflict
        // names — not just the one that triggered it — should more than one ever be recorded
        // together.
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.addPkgPrefix(tabId, "com.myapp.pkg")
        state.upFlt(tabId) { it.copy(activeTags = it.activeTags + "com.myapp.pkg.Existing") }
        state.pendingTagPrefixConflict =
            PendingTagPrefixConflict.CheckingTag(tabId, "com.myapp.pkg.Example1", setOf("com.myapp.pkg"))

        state.resolveTagPrefixConflict(showWholePackage = true, dontAskAgain = false)

        val filter = state.tabs.single().filter
        assertNull(state.pendingTagPrefixConflict)
        // Neither the newly-named tag nor the pre-existing scoped one remains active — the
        // prefix admits the whole package again.
        assertTrue(filter.activeTags.isEmpty())
    }

    @Test
    fun cancellingLeavesEverythingUnmutated() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.toggleTag(tabId, "com.myapp.pkg.Example1")
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        state.cancelTagPrefixConflict()

        val filter = state.tabs.single().filter
        assertNull(state.pendingTagPrefixConflict)
        assertTrue("com.myapp.pkg" !in filter.pkgPrefixes)
        assertEquals(setOf("com.myapp.pkg.Example1"), filter.activeTags)
    }

    // ── "Don't ask again" ────────────────────────────────────────────────────────────────────────

    @Test
    fun dontAskAgainPersistsTheSettingAndSuppressesFutureConflicts() {
        val state = AppState()
        state.addTab()
        val tabId = state.tabs.single().id
        state.toggleTag(tabId, "com.myapp.pkg.Example1")
        state.addPkgPrefix(tabId, "com.myapp.pkg")

        state.resolveTagPrefixConflict(showWholePackage = false, dontAskAgain = true)

        assertTrue(state.settings.suppressTagPrefixConflictPrompt)

        // A second, unrelated conflict must now apply today's narrowing behavior directly, with
        // no dialog — the frequent path stays modal-free once the user has opted out.
        state.toggleTag(tabId, "com.other.pkg.Thing")
        state.addPkgPrefix(tabId, "com.other.pkg")

        assertNull(state.pendingTagPrefixConflict)
        assertTrue("com.other.pkg" in state.tabs.single().filter.pkgPrefixes)
        assertTrue("com.other.pkg.Thing" in state.tabs.single().filter.activeTags)
    }
}
