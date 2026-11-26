package org.ton.tuning.impl

import kotlinx.serialization.json.Json
import org.usvm.machine.TsaCvc5ConfigurationDocument
import org.usvm.machine.TvmCvc5Configuration
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the copy-pasteable examples under `tsa-tuning/examples` loadable.
 *
 * The examples are the entry point a tuning consumer copies, so a schema or validation change that
 * invalidates them has to fail here rather than in the consumer's build.
 */
class TsaTuningExamplesTest {
    @Test
    fun `the example workload descriptor is a valid tsa-analysis-workload-v0`() {
        val descriptor =
            Json.decodeFromString(TsaAnalysisWorkload.serializer(), example("workload.json").readText())

        assertEquals("integer-underflow-receivers", descriptor.workloadId)
        assertEquals("r1", descriptor.revision)
        assertEquals(TsaWorkloadSourceKind.BOC, descriptor.sourceKind)
        assertEquals(TsaWorkloadTargetKind.RECEIVERS, descriptor.targetKind)
        assertEquals(60L, descriptor.methodTimeoutSeconds)
    }

    @Test
    fun `the example cvc5 configuration applies to the regular-bv backend`() {
        val configuration = TsaCvc5ConfigurationDocument.load(example("cvc5-configuration.json")).toConfiguration()

        assertEquals(TvmCvc5Configuration.REGULAR_BV_BACKEND, configuration.backendRole)
        assertEquals(mapOf("solve-bv-as-int" to "bitwise"), configuration.options)
    }

    private fun example(name: String): Path = Path.of("examples").resolve(name)
}
