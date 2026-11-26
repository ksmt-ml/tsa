package org.ton.options

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import org.usvm.machine.TvmSolverFactory

abstract class TuningOptions : OptionGroup("Symbolic analysis options")

internal fun AnalysisOptions.cvc5SolverFactory(): TvmSolverFactory? = null

internal fun tuningCommands(): Array<CliktCommand> = emptyArray()
