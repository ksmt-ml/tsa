package org.ton.tuning.impl

import io.ksmt.ml.tuner.search.Cvc5CancellationToken
import io.ksmt.ml.tuner.search.Cvc5SearchInstance
import io.ksmt.ml.tuner.search.Cvc5TrialObservation
import io.ksmt.ml.tuner.search.Cvc5TrialRequest
import org.ton.tuning.TsaCvc5TrialWorkload
import org.ton.tuning.TsaWorkloadReference
import org.usvm.machine.TvmCvc5ProfileReplayEvaluator
import org.usvm.machine.TvmCvc5ReplayCandidate
import kotlin.time.Duration

/**
 * A TSA workload evaluated by replaying captured, replayable cvc5 backend profiles.
 *
 * Every trial replays exactly one captured profile against a fresh, isolated cvc5 solver. The
 * validated configuration is applied while that solver is constructed, before its first assertion.
 * Captured profiles are read-only, so a trial can never mutate the workload.
 *
 * The workload reports a rankable observation only when the replay resolved every recorded check
 * with the recorded status. Recorded `UNKNOWN` checks are not part of the correctness oracle.
 */
class TsaCvc5ProfileReplayWorkload(
    override val reference: TsaWorkloadReference,
    override val parameterSpaceId: String,
    private val profiles: List<TsaCvc5ReplayProfile>,
    private val evaluator: TvmCvc5ProfileReplayEvaluator = TvmCvc5ProfileReplayEvaluator(),
) : TsaCvc5TrialWorkload {
    private val profilesByInstanceId = profiles.associateBy(TsaCvc5ReplayProfile::instanceId)

    override val solverVersion: String =
        profiles
            .map(TsaCvc5ReplayProfile::solverVersion)
            .distinct()
            .singleOrNull()
            ?: error("A replay workload must contain profiles of exactly one cvc5 version")

    override val instances: List<Cvc5SearchInstance> =
        profiles.map { profile ->
            Cvc5SearchInstance(
                id = profile.instanceId,
                attributes =
                    mapOf(
                        "profileId" to profile.profileId,
                        "runId" to profile.runId,
                        "backendRole" to profile.backendRole,
                    ),
            )
        }

    override fun evaluate(
        request: Cvc5TrialRequest,
        cancellation: Cvc5CancellationToken,
    ): Cvc5TrialObservation {
        if (cancellation.isCancellationRequested()) {
            return request.cancelledObservation()
        }

        if (!request.timeout.isInfinite() && request.timeout <= Duration.ZERO) {
            return request.timedOutObservation()
        }

        val profile =
            profilesByInstanceId[request.instanceId]
                ?: return request.infrastructureFailure("Unknown workload instance: ${request.instanceId}")

        val result =
            try {
                evaluator.evaluate(
                    profile = evaluator.parseProfile(profile.source),
                    candidate = request.toReplayCandidate(),
                    repetition = request.repetition,
                )
            } catch (failure: Exception) {
                return request.infrastructureFailure(failure.message ?: failure.javaClass.name)
            }

        return result.toObservation(request)
    }
}

private const val TARGET_SOLVER = "cvc5"

private fun Cvc5TrialRequest.toReplayCandidate(): TvmCvc5ReplayCandidate =
    TvmCvc5ReplayCandidate(
        schemaVersion = TvmCvc5ReplayCandidate.SCHEMA_VERSION,
        candidateId = requestId,
        parameterSpaceId = configuration.parameterSpaceId,
        targetSolver = TARGET_SOLVER,
        options = configuration.options,
    )
