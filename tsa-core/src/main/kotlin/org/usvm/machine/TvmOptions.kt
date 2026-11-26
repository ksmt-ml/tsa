package org.usvm.machine

import io.ksmt.profiling.KSolverProfile
import org.ton.TvmContractHandlers
import org.usvm.machine.TvmMachine.Companion.DEFAULT_LOOP_ITERATIONS_LIMIT
import org.usvm.machine.TvmMachine.Companion.DEFAULT_MAX_CELL_DEPTH_FOR_DEFAULT_CELLS_CONSISTENT_WITH_TLB
import org.usvm.machine.TvmMachine.Companion.DEFAULT_MAX_RECURSION_DEPTH
import org.usvm.machine.TvmMachine.Companion.DEFAULT_MAX_TLB_DEPTH
import org.usvm.machine.state.ContractId
import java.math.BigInteger
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class TvmOptions(
    val quietMode: Boolean = false,
    val enableExternalAddress: Boolean = false,
    val useReceiverInputs: Boolean = true,
    val enableInputValues: Boolean = true,
    val turnOnTLBParsingChecks: Boolean = true,
    val performAdditionalChecksWhileResolving: Boolean = false,
    val tlbOptions: TlbOptions = TlbOptions(),
    val maxRecursionDepth: Int? = DEFAULT_MAX_RECURSION_DEPTH,
    val timeout: Duration = Duration.INFINITE,
    val solverTimeout: Duration = 1.seconds,
    val excludeExecutionsWithFailures: Boolean = false,
    /**
     * Represents the maximum number of iterations (specifically, loop body executions) in
     * a symbolic context (that is, when forking occurs during iteration).
     */
    val loopIterationLimit: Int? = DEFAULT_LOOP_ITERATIONS_LIMIT,
    val intercontractOptions: IntercontractOptions = IntercontractOptions(),
    val useMainMethodForInitialMethodJump: Boolean = true,
    val analyzeBouncedMessaged: Boolean = false,
    val enableOutMessageAnalysis: Boolean = false,
    /**
     * Must be set to `false` when analyzing bounced messages.
     * When set to `true`, the analysis terminates at the first unhandled exception handled;
     * in this case, the `result` will contain the first thrown error code.
     * When set to false, the `result` of the state will contain the error code of the last
     * executed contract (the order of contract executions not bound by happens-before relationship
     * is unspecified).
     */
    val stopOnFirstError: Boolean = true,
    val useIntBlasting: Boolean = true,
    val useSoftConstraints: Boolean = true,
    val collectTlbMemoryStats: Boolean = false,
    val pathSelectionStrategy: TvmPathSelectionStrategy = TvmPathSelectionStrategy.DFS_BASED,
    val divideTimeBetweenOpcodes: TimeDivisionBetweenOpcodes? = null,
    val collectNonTerminatedState: Boolean = false,
    /**
     * If non-null, the analysis explores only the states whose path is a prefix of (or equal to)
     * the locations specified in this trace. Useful for replaying a previously observed execution.
     */
    val followTrace: FollowTrace? = null,
    val addTimeoutIfNotSatiated: Boolean = false,
    val shortResolve: Boolean = false,
    val groupByOutOpcodes: Boolean = false,
    val groupStatesByOutMessages: Boolean = false,
    /**
     * The limit of how many models will be extracted for any expression fetched by the `TvmState.fetchedValuesForModelEnum`
     * variable.
     */
    val enumeratingModelsCountLimit: Int = 5,
    val ignoreLowPriorityStates: Boolean = false,
    val forkOnEachPossibleElementInDictGet: Boolean = false,
    /** Optional collection of KSMT solver workload profiles. Disabled by default. */
    val solverProfileOptions: TvmSolverProfileOptions? = null,
    /**
     * Optional cvc5 configuration applied to one backend role while its solver is constructed.
     *
     * Options are applied before the first assertion, as cvc5 requires. Active solvers are never
     * reconfigured.
     */
    val cvc5Configuration: TvmCvc5Configuration? = null,
) {
    init {
        check(enableOutMessageAnalysis || !intercontractOptions.isIntercontractEnabled) {
            "Cannot perform inter-contract analysis without enabling out messages analysis"
        }
        check(!addTimeoutIfNotSatiated || timeout != Duration.INFINITE) {
            "Cannot add timeout when not satiated if no timeout is given"
        }
        check(!addTimeoutIfNotSatiated || divideTimeBetweenOpcodes == null) {
            "Cannot use strategy [addTimeoutIfNotSatiated] together with [divideTimeBetweenOpcodes]"
        }
    }
}

/**
 * A cvc5 configuration applied to one backend role while its solver is constructed.
 *
 * The tuner is expected to have validated the option domains against a versioned parameter space.
 * TSA additionally refuses the names it owns itself, and records the applied options in the captured
 * profile so that a replay reproduces exactly the configuration the profile was captured with.
 */
data class TvmCvc5Configuration(
    val backendRole: String,
    val parameterSpaceId: String,
    val options: Map<String, String>,
) {
    init {
        require(backendRole in BACKEND_ROLES) { "Unsupported cvc5 backend role: $backendRole" }
        require(parameterSpaceId.isNotBlank()) { "Parameter-space id must not be blank" }
        validateOptions(options)
    }

    companion object {
        const val REGULAR_BV_BACKEND = "regular-bv"
        const val INTEGER_BLASTED_BACKEND = "integer-blasted"

        val BACKEND_ROLES = setOf(REGULAR_BV_BACKEND, INTEGER_BLASTED_BACKEND)

        /**
         * Option names a configuration must never set.
         *
         * The evaluator owns these. A profile that records one of them cannot be replayed, because
         * the replay sets the logic and the per-check timeout itself and takes the rest from the
         * recorded initial configuration. KSMT writes its own keys into that same override map, so a
         * cvc5 option sharing one of those names would silently replace a KSMT setting.
         */
        val RESERVED_OPTIONS =
            TvmCvc5ReplayCandidate.PROTECTED_OPTIONS + TvmSolverProfileWriter.KSMT_CONFIGURATION_KEYS

        /** Checks the option map that the apply path and the document describing it both obey. */
        internal fun validateOptions(options: Map<String, String>) {
            require(options.isNotEmpty()) { "A cvc5 configuration must set at least one option" }
            require(options.keys.none { it.isBlank() }) { "cvc5 option names must not be blank" }
            require(options.values.none { it.isBlank() }) { "cvc5 option values must not be blank" }
            val reserved = options.keys.filter { it in RESERVED_OPTIONS }.sorted()
            require(reserved.isEmpty()) {
                "A cvc5 configuration must not set evaluator- or KSMT-controlled options: $reserved"
            }
        }

        /**
         * Option names of [options] that [inventory] does not accept, sorted.
         *
         * Advisory only: a parameter space owns option domains, and an inventory describes a single
         * native library build, so callers report these names instead of rejecting the configuration.
         */
        fun unknownOptionNames(
            options: Map<String, String>,
            inventory: TvmCvc5OptionInventory,
        ): List<String> {
            val accepted = inventory.options.flatMapTo(mutableSetOf()) { it.aliases + it.name }
            return options.keys.filterNot { it in accepted }.sorted()
        }
    }
}

data class TvmSolverProfileOptions(
    val outputDirectory: Path,
    val runId: String = UUID.randomUUID().toString(),
    val workloadId: String? = null,
    val maxRecordedEvents: Int = 1_000_000,
    val checkpointIntervalChecks: Int = 100,
    val attributes: Map<String, String> = emptyMap(),
    /**
     * Receives every terminal profile snapshot once its solver has been closed, in addition to the
     * profile JSON artifact written to [outputDirectory]. In-progress checkpoint snapshots are
     * written to disk only and are never emitted here.
     *
     * Profiling is an observation: a failing callback must not turn a successful analysis into a
     * failed one, so exceptions thrown by it are ignored.
     */
    val onTerminalProfile: ((KSolverProfile) -> Unit)? = null,
) {
    init {
        require(runId.isNotBlank()) { "Solver profile run id must not be blank" }
        require(workloadId?.isNotBlank() != false) { "Solver profile workload id must not be blank" }
        require(maxRecordedEvents > 0) { "Maximum recorded event count must be positive" }
        require(checkpointIntervalChecks > 0) { "Checkpoint interval must be positive" }
        require(attributes.keys.none { it.isBlank() }) { "Profile attribute names must not be blank" }
    }
}

data class TimeDivisionBetweenOpcodes(
    val inputId: Int,
    val opcodes: Set<BigInteger>,
)

data class TlbOptions(
    val performTlbChecksOnAllocatedCells: Boolean = false,
    val maxTlbDepth: Int = DEFAULT_MAX_TLB_DEPTH,
    val maxCellDepthForDefaultCellsConsistentWithTlb: Int =
        DEFAULT_MAX_CELL_DEPTH_FOR_DEFAULT_CELLS_CONSISTENT_WITH_TLB,
) {
    init {
        check(maxTlbDepth >= 0) {
            "maxTlbDepth must be non-negative, but it is $maxTlbDepth"
        }
        check(maxCellDepthForDefaultCellsConsistentWithTlb >= 0) {
            "maxCellDepthForDefaultCellsConsistentWithTlb must be non-negative, " +
                "but it is $maxCellDepthForDefaultCellsConsistentWithTlb"
        }
    }
}

data class IntercontractOptions(
    val communicationScheme: Map<ContractId, TvmContractHandlers>? = null,
) {
    val isIntercontractEnabled: Boolean
        get() = communicationScheme != null
}

enum class TvmPathSelectionStrategy {
    DFS_BASED,
    DFS_STRICT,
    BFS_BASED,
}
