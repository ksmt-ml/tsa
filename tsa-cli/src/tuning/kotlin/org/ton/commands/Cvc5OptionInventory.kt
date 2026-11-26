package org.ton.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.usvm.machine.TvmCvc5OptionInventoryReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

class Cvc5OptionInventory :
    CliktCommand(
        name = "cvc5-option-inventory",
        help = "Export option metadata from the cvc5 native library loaded by KSMT.",
    ) {
    private val outputPath by option("--output")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .required()

    override fun run() {
        val inventory = TvmCvc5OptionInventoryReader.read()
        outputPath.parent?.createDirectories()
        val temporary = outputPath.resolveSibling(".${outputPath.fileName}.tmp")
        try {
            Files.writeString(temporary, json.encodeToString(inventory) + "\n")
            try {
                Files.move(
                    temporary,
                    outputPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, outputPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        echo(
            "Exported ${inventory.canonicalOptionCount} canonical cvc5 " +
                "${inventory.solverVersion} options to $outputPath",
        )
    }

    companion object {
        private val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
    }
}
