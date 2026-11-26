package org.usvm.machine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TsaCvc5ConfigurationDocumentTest {
    @Test
    fun `loads a valid configuration document`() {
        withDocument(validDocument()) { path ->
            val configuration = TsaCvc5ConfigurationDocument.load(path).toConfiguration()

            assertEquals("test-space-v0", configuration.parameterSpaceId)
            assertEquals(TvmCvc5Configuration.REGULAR_BV_BACKEND, configuration.backendRole)
            assertEquals(mapOf("bv-solver" to "bitblast-internal"), configuration.options)
        }
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
    fun `rejects a missing file`() {
        val directory = Files.createTempDirectory("tsa-cvc5-configuration-missing-test")
        try {
            val path = directory.resolve("absent.json")

            assertFailsWith<IllegalArgumentException> { TsaCvc5ConfigurationDocument.load(path) }
        } finally {
            directory.toFile().deleteRecursively()
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
        const val SCHEMA_VERSION = "tsa-cvc5-configuration-v0"
    }
}
