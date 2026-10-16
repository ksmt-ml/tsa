package org.usvm.machine

import io.github.cvc5.OptionInfo
import io.ksmt.KContext
import io.ksmt.solver.cvc5.KCvc5Solver
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
data class TvmCvc5OptionInventoryEntry(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val kind: String,
    val defaultValue: String? = null,
    val minimum: String? = null,
    val maximum: String? = null,
    val modes: List<String> = emptyList(),
)

@Serializable
data class TvmCvc5OptionInventory(
    val schemaVersion: String = SCHEMA_VERSION,
    val solver: String = "cvc5",
    val solverVersion: String,
    val acceptedNameCount: Int,
    val canonicalOptionCount: Int,
    val options: List<TvmCvc5OptionInventoryEntry>,
) {
    companion object {
        const val SCHEMA_VERSION = "tsa-cvc5-option-inventory-v0"
    }
}

/** Reads option metadata from the exact cvc5 native library loaded by KSMT. */
object TvmCvc5OptionInventoryReader {
    fun read(): TvmCvc5OptionInventory =
        KContext().use { context ->
            KCvc5Solver(context).use { ksmtSolver ->
                val solver = ksmtSolver.nativeSolver()
                val acceptedNames = solver.optionNames.toList()
                val options =
                    acceptedNames
                        .map(solver::getOptionInfo)
                        .distinctBy(OptionInfo::getName)
                        .sortedBy(OptionInfo::getName)
                        .map(::inventoryEntry)

                TvmCvc5OptionInventory(
                    solverVersion = solver.version,
                    acceptedNameCount = acceptedNames.size,
                    canonicalOptionCount = options.size,
                    options = options,
                )
            }
        }

    private fun inventoryEntry(info: OptionInfo): TvmCvc5OptionInventoryEntry {
        val baseInfo = info.baseInfo
        val kind: String
        val defaultValue: String?
        val minimum: String?
        val maximum: String?
        val modes: List<String>

        when (baseInfo) {
            is OptionInfo.ModeInfo -> {
                kind = "categorical"
                defaultValue = baseInfo.defaultValue
                minimum = null
                maximum = null
                modes = baseInfo.modes.toList()
            }
            is OptionInfo.NumberInfo<*> -> {
                kind =
                    when (baseInfo.defaultValue) {
                        is BigInteger -> "integer"
                        is Double -> "number"
                        else -> error("Unsupported numeric cvc5 option type: ${baseInfo.defaultValue?.javaClass}")
                    }
                defaultValue = baseInfo.defaultValue.toString()
                minimum = baseInfo.minimum?.toString()
                maximum = baseInfo.maximum?.toString()
                modes = emptyList()
            }
            is OptionInfo.ValueInfo<*> -> {
                kind =
                    when (baseInfo.defaultValue) {
                        is Boolean -> "boolean"
                        is String -> "string"
                        else -> error("Unsupported cvc5 option type: ${baseInfo.defaultValue?.javaClass}")
                    }
                defaultValue = baseInfo.defaultValue.toString()
                minimum = null
                maximum = null
                modes = emptyList()
            }
            is OptionInfo.VoidInfo -> {
                kind = "void"
                defaultValue = null
                minimum = null
                maximum = null
                modes = emptyList()
            }
            else -> error("Unsupported cvc5 option metadata: ${baseInfo.javaClass}")
        }

        return TvmCvc5OptionInventoryEntry(
            name = info.name,
            aliases = info.aliases.toList().sorted(),
            category = info.category.name.lowercase(),
            kind = kind,
            defaultValue = defaultValue,
            minimum = minimum,
            maximum = maximum,
            modes = modes.sorted(),
        )
    }
}
