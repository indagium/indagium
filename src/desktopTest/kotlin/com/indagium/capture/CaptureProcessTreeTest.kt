package com.indagium.capture

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse

/** The shared capture cleanup must reap captured children even after their command parent exits on TERM. */
class CaptureProcessTreeTest {
    @Test
    fun forceKillsCapturedResistantDescendantAfterTheParentExits() {
        assumeFalse("this process-tree fixture uses POSIX shell", System.getProperty("os.name").lowercase().contains("win"))
        val directory = createTempDirectory("capture-process-tree").toFile()
        val pidFile = File(directory, "child.pid")
        val sentinel = File(directory, "late-sentinel")
        val child = "trap \"\" TERM; echo \$\$ > ${pidFile.absolutePath}; (sleep 0.8; echo survived > ${sentinel.absolutePath}) & wait"
        val parent = "trap 'exit 0' TERM; sh -c '$child' & wait"
        val process = ProcessBuilderCaptureRunner().start(CaptureProcessSpec(listOf("/bin/sh", "-c", parent)))
        try {
            val pid = runBlocking {
                withTimeout(5_000) {
                    while (!pidFile.isFile) kotlinx.coroutines.delay(10)
                    pidFile.readText().trim().toLong()
                }
            }

            process.terminate(Duration.ofMillis(250))
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the resistant child is killed after the grace period")
            Thread.sleep(900)
            assertFalse(sentinel.exists(), "a captured child cannot outlive the parent and write a late artifact")
        } finally {
            process.close()
            directory.deleteRecursively()
        }
    }
}
