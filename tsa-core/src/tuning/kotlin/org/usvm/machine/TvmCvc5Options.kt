package org.usvm.machine

import io.ksmt.profiling.KSolverProfile
import java.nio.file.Path
import java.util.Collections
import java.util.UUID

/**
 * A cvc5 configuration applied to one backend role while its solver is constructed.
 *
 * The tuner is expected to have validated the option domains against a versioned parameter space.
 * TSA additionally refuses the names it owns itself, and records the applied options in the captured
 * profile so that a replay reproduces exactly the configuration the profile was captured with.
 */
class TvmCvc5Configuration internal constructor(
    val backendRole: String,
    val parameterSpaceId: String,
    options: Map<String, String>,
    internal val solverVersion: String? = null,
) {
    val options: Map<String, String> = Collections.unmodifiableMap(options.toMap())

    init {
        require(backendRole in BACKEND_ROLES) { "Unsupported cvc5 backend role: $backendRole" }
        require(parameterSpaceId.isNotBlank()) { "Parameter-space id must not be blank" }
        validateOptions(this.options)
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
            require(options["ee-mode"] != "central" || options["theoryof-mode"] != "term") {
                "ee-mode=central combined with theoryof-mode=term can change UNSAT to SAT"
            }
            val reserved = options.keys.filter { it in RESERVED_OPTIONS }.sorted()
            require(reserved.isEmpty()) {
                "A cvc5 configuration must not set evaluator- or KSMT-controlled options: $reserved"
            }
        }

        /**
         * Option names of [options] that [inventory] does not accept, sorted.
         *
         * The parameter space validates option domains. The inventory verifies that the loaded
         * native library supports the option names.
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
