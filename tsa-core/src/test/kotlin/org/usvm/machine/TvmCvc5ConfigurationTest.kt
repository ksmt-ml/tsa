package org.usvm.machine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TvmCvc5ConfigurationTest {
    @Test
    fun `rejects evaluator-controlled options`() {
        TvmCvc5ReplayCandidate.PROTECTED_OPTIONS.forEach { option ->
            assertFailsWith<IllegalArgumentException>("$option must be refused") {
                configuration(mapOf(option to "1"))
            }
        }
    }

    @Test
    fun `rejects KSMT configuration keys`() {
        TvmSolverProfileWriter.KSMT_CONFIGURATION_KEYS.forEach { key ->
            assertFailsWith<IllegalArgumentException>("$key must be refused") {
                configuration(mapOf(key to "1"))
            }
        }
    }

    @Test
    fun `accepts an ordinary cvc5 option`() {
        assertEquals(
            mapOf("solve-bv-as-int" to "bitwise"),
            configuration(mapOf("solve-bv-as-int" to "bitwise")).options,
        )
    }

    @Test
    fun `rejects an unknown backend role`() {
        assertFailsWith<IllegalArgumentException> {
            TvmCvc5Configuration("sat-core", "test-space-v0", mapOf("bv-solver" to "bitblast"))
        }
    }

    @Test
    fun `rejects an empty option map`() {
        assertFailsWith<IllegalArgumentException> { configuration(emptyMap()) }
    }

    @Test
    fun `reports option names the inventory does not accept`() {
        val inventory =
            TvmCvc5OptionInventory(
                solverVersion = "1.3.4",
                acceptedNameCount = 3,
                canonicalOptionCount = 2,
                options =
                    listOf(
                        inventoryEntry("bv-solver", aliases = listOf("bv-solver-alias")),
                        inventoryEntry("solve-bv-as-int"),
                    ),
            )

        assertEquals(
            listOf("definitely-not-an-option"),
            TvmCvc5Configuration.unknownOptionNames(
                mapOf(
                    "bv-solver" to "bitblast",
                    "bv-solver-alias" to "bitblast",
                    "definitely-not-an-option" to "1",
                ),
                inventory,
            ),
        )
    }

    @Test
    fun `accepts options of the loaded cvc5 build`() {
        val inventory = TvmCvc5OptionInventoryReader.read()

        assertEquals(
            emptyList(),
            TvmCvc5Configuration.unknownOptionNames(mapOf("solve-bv-as-int" to "bitwise"), inventory),
        )
        assertEquals(
            listOf("definitely-not-an-option"),
            TvmCvc5Configuration.unknownOptionNames(mapOf("definitely-not-an-option" to "1"), inventory),
        )
    }

    private fun configuration(options: Map<String, String>): TvmCvc5Configuration =
        TvmCvc5Configuration(
            backendRole = TvmCvc5Configuration.REGULAR_BV_BACKEND,
            parameterSpaceId = "test-space-v0",
            options = options,
        )

    private fun inventoryEntry(
        name: String,
        aliases: List<String> = emptyList(),
    ): TvmCvc5OptionInventoryEntry =
        TvmCvc5OptionInventoryEntry(
            name = name,
            aliases = aliases,
            category = "smt",
            kind = "categorical",
        )
}
