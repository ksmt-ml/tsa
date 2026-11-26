package org.ton.options

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.ton.commands.Cvc5OptionInventory
import org.ton.commands.SolverProfileReplay
import org.usvm.machine.TvmCvc5SolverFactory
import org.usvm.machine.TvmSolverFactory
import org.usvm.machine.TvmSolverProfileOptions
import java.util.UUID

abstract class TuningOptions : OptionGroup("Symbolic analysis options") {
    val cvc5Tuning by option("--cvc5-tuning")
        .flag()
        .help("Use cvc5 for both solver roles without applying a tuned configuration.")

    val solverProfileDirectory by option("--solver-profile-dir")
        .path(canBeFile = false)
        .help(
            "Directory for v0 KSMT solver workload profiles. " +
                "Profiling is disabled when this option is absent.",
        )

    val solverProfileRunId by option("--solver-profile-run-id")
        .help("Stable run id used to link all solver profiles produced by one analysis.")

    val solverProfileWorkloadId by option("--solver-profile-workload-id")
        .help("Stable workload id used for grouped dataset splits and repeated evaluations.")

    val solverProfileMaxEvents by option("--solver-profile-max-events")
        .int()
        .default(1_000_000)
        .help("Maximum number of events retained by each solver profile. Default: 1000000.")

    val solverProfileCheckpointInterval by option("--solver-profile-checkpoint-interval")
        .int()
        .default(100)
        .help("Write an atomic in-progress snapshot after this many checks. Default: 100.")
}

internal fun AnalysisOptions.cvc5SolverFactory(): TvmSolverFactory? {
    if (!cvc5Tuning && solverProfileDirectory == null) return null

    val profileOptions =
        solverProfileDirectory?.let { outputDirectory ->
            TvmSolverProfileOptions(
                outputDirectory = outputDirectory,
                runId = solverProfileRunId ?: UUID.randomUUID().toString(),
                workloadId = solverProfileWorkloadId,
                maxRecordedEvents = solverProfileMaxEvents,
                checkpointIntervalChecks = solverProfileCheckpointInterval,
                attributes =
                    buildMap {
                        put("analysis.solver-timeout-seconds", solverTimeout.toString())
                        put("analysis.int-blasting-enabled", (!noIntBlasting).toString())
                        put(
                            "analysis.iteration-limit",
                            optionsValue(noIterationLimit, iterationLimit),
                        )
                        put(
                            "analysis.recursion-depth",
                            optionsValue(
                                noRecursionDepthLimit,
                                maxRecursionDepth,
                            ),
                        )
                        timeout?.let { put("analysis.timeout-seconds", it.toString()) }
                    },
            )
        }
    return TvmCvc5SolverFactory(profileOptions = profileOptions)
}

internal fun tuningCommands(): Array<CliktCommand> = arrayOf(SolverProfileReplay(), Cvc5OptionInventory())

private fun optionsValue(
    unlimited: Boolean,
    value: Int,
): String = if (unlimited) "unlimited" else value.toString()
