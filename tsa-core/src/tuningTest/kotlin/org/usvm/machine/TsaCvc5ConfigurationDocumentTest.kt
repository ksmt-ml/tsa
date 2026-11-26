package org.usvm.machine

import io.ksmt.ml.tuner.configuration.Cvc5ConfigurationPatch
import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpace
import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpaceImpl
import io.ksmt.ml.tuner.configuration.Cvc5PatchValidation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TsaCvc5ConfigurationDocumentTest {
    @Test
    fun `loads a valid configuration document`() {
        withDocument(validDocument()) { path ->
            val configuration = TsaCvc5ConfigurationDocument.load(path).validate(parameterSpace())

            assertEquals("test-space-v0", configuration.parameterSpaceId)
            assertEquals(TvmCvc5Configuration.REGULAR_BV_BACKEND, configuration.backendRole)
            assertEquals(mapOf("bv-solver" to "bitblast-internal"), configuration.options)
        }
    }

    @Test
    fun `does not accept configuration without domain approval`() {
        withDocument(validDocument().replace("bitblast-internal", "outside-domain")) { path ->
            val document = TsaCvc5ConfigurationDocument.load(path)
            assertFailsWith<IllegalArgumentException> { document.validate(parameterSpace()) }
        }
    }

    @Test
    fun `rejects an incompatible parameter space before applying a patch`() {
        withDocument(validDocument()) { path ->
            val document = TsaCvc5ConfigurationDocument.load(path)
            assertFailsWith<IllegalArgumentException> {
                document.validate(parameterSpace(compatibleVersions = setOf("0.0.0")))
            }
            assertFailsWith<IllegalArgumentException> {
                document.validate(parameterSpace(backendRole = TvmCvc5Configuration.INTEGER_BLASTED_BACKEND))
            }
        }
    }

    @Test
    fun `rejects the known wrong-answer option pair`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                TsaCvc5ConfigurationDocument(
                    parameterSpaceId = "test-space-v0",
                    backendRole = "regular-bv",
                    options = mapOf("ee-mode" to "central", "theoryof-mode" to "term"),
                )
            }

        assertTrue(exception.message.orEmpty().contains("change UNSAT to SAT"))
    }

    @Test
    fun `rejects an unsupported schema version`() {
        withDocument(validDocument().replace(SCHEMA_VERSION, "tsa-cvc5-configuration-v1")) { path ->
            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        }
    }

    @Test
    fun `rejects an unknown backend role`() {
        withDocument(validDocument().replace("regular-bv", "sat-core")) { path ->
            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        }
    }

    @Test
    fun `rejects an empty option map`() {
        withDocument(validDocument().replace("\"bv-solver\": \"bitblast-internal\"", "")) { path ->
            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        }
    }

    @Test
    fun `rejects an evaluator-controlled option and names the document`() {
        withDocument(validDocument().replace("bv-solver", "logic")) { path ->
            val failure = assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }

            val message = assertNotNull(failure.message)
            assertTrue(message.contains(path.toString()), "message must name the document: $message")
            assertTrue(message.contains("logic"), "message must name the option: $message")
        }
    }

    @Test
    fun `rejects a KSMT configuration key`() {
        withDocument(validDocument().replace("bv-solver", "ksmt.optimize.theories")) { path ->
            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        }
    }

    @Test
    fun `rejects an unknown property`() {
        withDocument(validDocument().replace("\"options\"", "\"unknownProperty\": \"x\",\n  \"options\"")) { path ->
            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        }
    }

    @Test
    fun `reports the document path for malformed json`() {
        withDocument("{ not json") { path ->
            val failure = assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }

            val message = assertNotNull(failure.message)
            assertTrue(message.contains(path.toString()), "message must name the document: $message")
        }
    }

    @Test
    fun `rejects a missing file`() {
        val directory = Files.createTempDirectory("tsa-cvc5-configuration-missing-test")
        try {
            val path = directory.resolve("absent.json")

            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packages its json schema`() {
        val resource = TsaCvc5ConfigurationDocument.SCHEMA_RESOURCE

        assertNotNull(javaClass.classLoader.getResource(resource), "schema resource $resource is not packaged")
    }

    private fun parameterSpace(
        compatibleVersions: Set<String> = setOf(TvmCvc5OptionInventoryReader.current.solverVersion),
        backendRole: String = TvmCvc5Configuration.REGULAR_BV_BACKEND,
    ): Cvc5ParameterSpace {
        val space =
            Cvc5ParameterSpaceImpl(
                parameterSpaceId = "test-space-v0",
                compatibleSolverVersions = compatibleVersions,
                backendRole = backendRole,
            )
        return object : Cvc5ParameterSpace by space {
            override fun validate(patch: Cvc5ConfigurationPatch): Cvc5PatchValidation =
                if (patch.options == mapOf("bv-solver" to "bitblast-internal")) {
                    space.validate(patch)
                } else {
                    Cvc5PatchValidation.Rejected("option value outside the approved domain")
                }
        }
    }

    private fun withDocument(
        document: String,
        block: (Path) -> Unit,
    ) {
        val directory = Files.createTempDirectory("tsa-cvc5-configuration-test")
        try {
            val path = directory.resolve("configuration.json")
            path.writeText(document)
            block(path)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun validDocument(): String =
        """
        {
          "schemaVersion": "$SCHEMA_VERSION",
          "parameterSpaceId": "test-space-v0",
          "backendRole": "regular-bv",
          "options": { "bv-solver": "bitblast-internal" }
        }
        """.trimIndent()

    private companion object {
        val SCHEMA_VERSION = TsaCvc5ConfigurationDocument.SCHEMA_VERSION
    }
}
