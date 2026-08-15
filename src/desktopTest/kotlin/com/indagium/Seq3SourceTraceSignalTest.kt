package com.indagium

import com.indagium.diagram3.Seq3GenerateOptions
import com.indagium.diagram3.Seq3Range
import com.indagium.diagram3.generateSeq3
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.source.SourceIndex
import com.indagium.source.SourceIndexer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Source-trace as Seq3Generator's third target-inference signal (item 1 of the post-ship plan).
 * Reuses the canonical nested-call fixture from `source.SourceTraceInferenceTest` — three tags with
 * no pid/tid and no shared correlation token between them, so thread-handoff/correlation-token alone
 * can prove nothing; only a real source call graph can resolve the target.
 */
class Seq3SourceTraceSignalTest {
    private fun controllerServiceRepositoryIndex(): SourceIndex {
        val dir = createTempDirectory("indagium-seq3-source-trace").toFile()
        File(dir, "Fixture.kt").toPath().writeText(
            """
            package fixture
            class Controller(private val service: Service) {
                fun start() {
                    Log.d("Controller", "controller start")
                    val value = service.run()
                    Log.d("Controller", "controller result=${'$'}value")
                }
            }
            class Service(private val repository: Repository) {
                fun run(): String {
                    Log.d("Service", "service start")
                    val value = repository.load()
                    Log.d("Service", "service value=${'$'}value")
                    return value
                }
            }
            class Repository {
                fun load(): String {
                    Log.d("Repository", "repository load")
                    return "42"
                }
            }
            """.trimIndent(),
        )
        return SourceIndexer.build(listOf(dir))
    }

    private fun entries() = listOf(
        LogEntry(1, "10:00:00.001", LogLevel.I, "Controller", "controller start"),
        LogEntry(2, "10:00:00.002", LogLevel.I, "Service", "service start"),
        LogEntry(3, "10:00:00.003", LogLevel.I, "Repository", "repository load"),
        LogEntry(4, "10:00:00.004", LogLevel.I, "Service", "service value=42"),
        LogEntry(5, "10:00:00.005", LogLevel.I, "Controller", "controller result=42"),
    )

    @Test
    fun withNoIndexTargetInferenceFailsExactlyAsBeforeThisSignalExisted() {
        val doc = generateSeq3(entries(), Seq3Range.VisibleView)
        val controllerId = doc.lifelines.single { it.name == "Controller" }.id
        val startMessage = doc.messages.single { it.fromLifelineId == controllerId && it.match.template == "controller start" }
        assertNull(startMessage.toLifelineId, "no pid/tid and no shared token — the two original signals must find nothing")
    }

    @Test
    fun sourceTraceResolvesTargetsThreadHandoffAndCorrelationTokenAloneWouldMiss() {
        val index = controllerServiceRepositoryIndex()
        val doc = generateSeq3(entries(), Seq3Range.VisibleView, sourceIndex = index)

        val controllerId = doc.lifelines.single { it.name == "Controller" }.id
        val serviceId = doc.lifelines.single { it.name == "Service" }.id
        val repositoryId = doc.lifelines.single { it.name == "Repository" }.id

        val controllerStart = doc.messages.single { it.fromLifelineId == controllerId && it.match.template == "controller start" }
        assertEquals(serviceId, controllerStart.toLifelineId, "source trace proves Controller's call lands in Service")

        val serviceStart = doc.messages.single { it.fromLifelineId == serviceId && it.match.template == "service start" }
        assertEquals(repositoryId, serviceStart.toLifelineId, "source trace proves Service's call lands in Repository")
    }

    @Test
    fun disablingTheOptionIgnoresASuppliedIndex() {
        val index = controllerServiceRepositoryIndex()
        val doc = generateSeq3(entries(), Seq3Range.VisibleView, Seq3GenerateOptions(sourceTraceEnabled = false), sourceIndex = index)
        val controllerId = doc.lifelines.single { it.name == "Controller" }.id
        val controllerStart = doc.messages.single { it.fromLifelineId == controllerId && it.match.template == "controller start" }
        assertNull(controllerStart.toLifelineId, "sourceTraceEnabled = false must ignore even a supplied index")
    }
}
