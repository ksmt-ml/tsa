package org.ton.tuning.impl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.nio.file.Path
import java.nio.file.Paths

/** Where one TSA analysis takes its contract code from. */
@Serializable
enum class TsaWorkloadSourceKind {
    @SerialName("boc")
    BOC,

    @SerialName("func")
    FUNC,
}

/** Which methods of a contract one TSA analysis explores. */
@Serializable
enum class TsaWorkloadTargetKind {
    /** The two receiver entry points, `RECEIVE_INTERNAL_ID` and `RECEIVE_EXTERNAL_ID`. */
    @SerialName("receivers")
    RECEIVERS,

    /** Every method declared by the contract. */
    @SerialName("all-methods")
    ALL_METHODS,

    /** Exactly [TsaAnalysisWorkload.methodId]. */
    @SerialName("method")
    METHOD,
}

/**
 * Versioned description of one bounded TSA analysis, that is, of one workload capture.
 *
 * The document mirrors the inputs of the demo capture script: the contract source, the analysis
 * target, and the TSA limits that bound the captured solver sessions. It is stored next to the
 * captured profiles at `<workloadsRoot>/<workloadId>/<revision>/workload.json`.
 *
 * `tact` sources are not described yet because they need a compiled Tact project, not a single
 * contract file.
 */
@Serializable
data class TsaAnalysisWorkload(
    val schemaVersion: String = SCHEMA_VERSION,
    val workloadId: String,
    val revision: String,
    val sourceKind: TsaWorkloadSourceKind,
    val sourcePath: String,
    val fiftStdlibPath: String? = null,
    val targetKind: TsaWorkloadTargetKind = TsaWorkloadTargetKind.RECEIVERS,
    val methodId: String? = null,
    val methodTimeoutSeconds: Long,
    val solverTimeoutSeconds: Long = DEFAULT_SOLVER_TIMEOUT_SECONDS,
    val iterationLimit: Int? = null,
    val maxRecursionDepth: Int? = null,
    val shortResolve: Boolean = false,
    val analyzeLowPriorityStates: Boolean = false,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported workload schema: $schemaVersion" }
        require(workloadId.isNotBlank()) { "Workload id must not be blank" }
        require(revision.isNotBlank()) { "Workload revision must not be blank" }
        require(sourcePath.isNotBlank()) { "Workload source path must not be blank" }
        require(methodTimeoutSeconds > 0) { "Method timeout must be positive" }
        require(solverTimeoutSeconds > 0) { "Solver timeout must be positive" }
        require(iterationLimit == null || iterationLimit > 0) { "Iteration limit must be positive" }
        require(maxRecursionDepth == null || maxRecursionDepth >= 0) {
            "Maximum recursion depth must not be negative"
        }

        when (targetKind) {
            TsaWorkloadTargetKind.METHOD ->
                require(methodId?.toBigIntegerOrNull() != null) {
                    "A single-method workload must declare an integer method id"
                }

            else -> require(methodId == null) { "Only a single-method workload may declare a method id" }
        }

        if (sourceKind == TsaWorkloadSourceKind.FUNC) {
            require(!fiftStdlibPath.isNullOrBlank()) { "A FunC workload must declare the fift standard library" }
        }
    }

    val source: Path get() = Paths.get(sourcePath)

    val fiftStdlib: Path? get() = fiftStdlibPath?.let(Paths::get)

    val method: BigInteger? get() = methodId?.toBigInteger()

    companion object {
        const val SCHEMA_VERSION = "tsa-analysis-workload-v0"
        const val DEFAULT_SOLVER_TIMEOUT_SECONDS = 5L
    }
}
