package com.indagium

import com.indagium.ui.AppState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure resolution for the five save folders in Settings → General → Storage
 * (AppState.effectiveSaveRootDir/effectiveAnalysisDirForDisplay/effectiveCaptureSessionsDir/
 * effectiveCaptureSnapshotsDir/effectiveCaptureZipDir*). [AppState]'s `platformDefaultSaveRootDir`
 * constructor parameter stands in for the real `~/Documents/Indagium` default here — see that
 * parameter's own doc for why a bare `AppState()` (or one that only overrides autosaveFile/
 * notesDir, the way hundreds of pre-existing tests already do) must never resolve one of these to
 * a real user directory.
 */
class SaveFoldersEffectivePathTest {
    @Test
    fun unsetRootFallsBackToThePlatformDefaultInjectedAtConstruction() {
        val dir = createTempDirectory("openlog-save-folders-unset-root").toFile()
        val platformRoot = File(dir, "platform-root")
        val state = AppState(autosaveFile = File(dir, "state.cache"), platformDefaultSaveRootDir = platformRoot)

        assertEquals(platformRoot, state.effectiveSaveRootDir)
        assertEquals(File(platformRoot, "analysis"), state.effectiveAnalysisDirForDisplay())
        assertEquals(File(platformRoot, "captures"), state.effectiveCaptureSessionsDir())
        assertEquals(File(platformRoot, "snapshots"), state.effectiveCaptureSnapshotsDir())
        assertEquals(File(platformRoot, "saved-captures"), state.effectiveCaptureZipDirForDisplay())
    }

    @Test
    fun anExplicitSaveRootDirOverridesThePlatformDefault() {
        val dir = createTempDirectory("openlog-save-folders-set-root").toFile()
        val platformRoot = File(dir, "platform-root")
        val explicitRoot = File(dir, "explicit-root")
        val state = AppState(autosaveFile = File(dir, "state.cache"), platformDefaultSaveRootDir = platformRoot)
        state.settings = state.settings.copy(saveRootDir = explicitRoot.absolutePath)

        assertEquals(explicitRoot, state.effectiveSaveRootDir)
        assertEquals(File(explicitRoot, "analysis"), state.effectiveAnalysisDirForDisplay())
        assertEquals(File(explicitRoot, "captures"), state.effectiveCaptureSessionsDir())
    }

    @Test
    fun anExplicitChildFolderOverridesTheRootForThatFolderOnly() {
        val dir = createTempDirectory("openlog-save-folders-child-override").toFile()
        val root = File(dir, "root")
        val explicitSnapshots = File(dir, "my-snapshots")
        val state = AppState(autosaveFile = File(dir, "state.cache"), platformDefaultSaveRootDir = root)
        state.settings = state.settings.copy(captureSnapshotsDir = explicitSnapshots.absolutePath)

        assertEquals(explicitSnapshots, state.effectiveCaptureSnapshotsDir())
        // Every other folder is unaffected by the snapshots-only override.
        assertEquals(File(root, "captures"), state.effectiveCaptureSessionsDir())
        assertEquals(File(root, "analysis"), state.effectiveAnalysisDirForDisplay())
    }

    @Test
    fun changingTheRootMovesOnlyTheFoldersThatWereNeverExplicitlySet() {
        val dir = createTempDirectory("openlog-save-folders-root-change").toFile()
        val rootA = File(dir, "root-a")
        val explicitSnapshots = File(dir, "my-snapshots")
        val state = AppState(autosaveFile = File(dir, "state.cache"), platformDefaultSaveRootDir = rootA)
        state.settings = state.settings.copy(captureSnapshotsDir = explicitSnapshots.absolutePath)

        assertEquals(File(rootA, "captures"), state.effectiveCaptureSessionsDir())
        assertEquals(explicitSnapshots, state.effectiveCaptureSnapshotsDir())

        val rootB = File(dir, "root-b")
        state.settings = state.settings.copy(saveRootDir = rootB.absolutePath)

        assertEquals(File(rootB, "captures"), state.effectiveCaptureSessionsDir())
        assertEquals(File(rootB, "analysis"), state.effectiveAnalysisDirForDisplay())
        // The explicitly configured snapshots folder never moved.
        assertEquals(explicitSnapshots, state.effectiveCaptureSnapshotsDir())
    }

    @Test
    fun nothingConfiguredFallsBackToEveryPreSection3Default() {
        // No platformDefaultSaveRootDir injected — the class's own default, same as a bare
        // AppState(). This is the regression guard for the test-isolation requirement: it must
        // resolve to exactly what each folder computed before save folders existed, never to a
        // real ~/Documents path.
        val dir = createTempDirectory("openlog-save-folders-nothing-configured").toFile()
        val state = AppState(autosaveFile = File(dir, "state.cache"))

        assertEquals(File(dir, "captures"), state.effectiveCaptureSessionsDir())
        assertEquals(File("."), state.effectiveCaptureSnapshotsDir())
        val sessionParent = File(dir, "some-session-dir")
        assertEquals(sessionParent, state.effectiveCaptureZipDir(fallback = sessionParent))
    }
}
