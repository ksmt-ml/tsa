package org.usvm.machine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvmCvc5OptionInventoryTest {
    @Test
    fun `inventory comes from loaded cvc5 1_3_4 runtime`() {
        val inventory = TvmCvc5OptionInventoryReader.read()

        assertEquals("tsa-cvc5-option-inventory-v0", inventory.schemaVersion)
        assertEquals("1.3.4", inventory.solverVersion)
        assertEquals(531, inventory.acceptedNameCount)
        assertEquals(518, inventory.canonicalOptionCount)
        assertEquals(
            518,
            inventory.options
                .map { it.name }
                .toSet()
                .size,
        )
        assertTrue(inventory.options.any { it.name == "arrays-eager-index" && it.kind == "boolean" })
        assertTrue(inventory.options.any { it.name == "sat-solver" && "minisat" in it.modes })
    }
}
