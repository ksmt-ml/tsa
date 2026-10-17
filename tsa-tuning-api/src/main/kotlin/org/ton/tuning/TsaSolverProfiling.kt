package org.ton.tuning

import io.ksmt.profiling.KSolverDescriptor
import io.ksmt.profiling.KSolverProfile
import kotlin.time.Duration

/** Input for one bounded TSA analysis launched to capture solver profiles. */
data class TsaSolverProfileLaunchRequest(
    val runId: String,
    val workload: TsaWorkloadReference,
    val solver: KSolverDescriptor,
    val timeout: Duration = Duration.INFINITE,
    val maxRecordedEventsPerProfile: Int = 1_000_000,
    val attributes: Map<String, String> = emptyMap(),
)

/** Receives terminal, structurally independent profiles as solver sessions finish. */
fun interface TsaSolverProfileSink {
    fun accept(profile: KSolverProfile)
}

/** Cooperative cancellation checked by a profile-capture implementation. */
fun interface TsaLaunchCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE: TsaLaunchCancellation = TsaLaunchCancellation { false }
    }
}

/** Final state of a bounded TSA profile-capture launch. */
enum class TsaSolverProfileLaunchStatus {
    COMPLETED,
    TIMED_OUT,
    CANCELLED,
    FAILED,
}

data class TsaSolverProfileLaunchResult(
    val runId: String,
    val status: TsaSolverProfileLaunchStatus,
    val capturedProfiles: Long,
    val failedProfiles: Long,
    val duration: Duration,
    val failureType: String? = null,
    val failureMessage: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Launches TSA analysis with observation-only KSMT profiling enabled.
 *
 * The implementation owns TSA execution and storage. It must bound retained profile events, emit
 * terminal snapshots to [sink], preserve application behavior when profiling fails, and return only
 * after the launch reaches a terminal state.
 */
fun interface TsaSolverProfileLauncher {
    fun launch(
        request: TsaSolverProfileLaunchRequest,
        sink: TsaSolverProfileSink,
        cancellation: TsaLaunchCancellation,
    ): TsaSolverProfileLaunchResult
}
