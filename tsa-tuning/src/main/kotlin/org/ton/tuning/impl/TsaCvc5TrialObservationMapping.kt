package org.ton.tuning.impl

import io.ksmt.ml.tuner.search.Cvc5TrialCorrectness
import io.ksmt.ml.tuner.search.Cvc5TrialObservation
import io.ksmt.ml.tuner.search.Cvc5TrialRequest
import io.ksmt.ml.tuner.search.Cvc5TrialStatus
import org.usvm.machine.TvmCvc5ProfileReplayResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

private const val REPLAY_OUTCOME_SUCCESS = "SUCCESS"

/**
 * Maps one replay result into the tuner-owned observation contract.
 *
 * A replay is rankable only when it completed, stayed inside its trial budget, and every recorded
 * check matched its recorded status. A mismatching status is a wrong answer, an unresolved recorded
 * status is a censored timeout, and a replay that failed to complete is an infrastructure failure.
 */
internal fun TvmCvc5ProfileReplayResult.toObservation(request: Cvc5TrialRequest): Cvc5TrialObservation {
    val status: Cvc5TrialStatus
    val correctness: Cvc5TrialCorrectness
    val censored: Boolean
    val cost: Double?

    when {
        wrongAnswerCount > 0 -> {
            status = Cvc5TrialStatus.WRONG_ANSWER
            correctness = Cvc5TrialCorrectness.INCORRECT
            censored = false
            cost = null
        }

        // A replay that outlived its trial budget is a censored observation, not a measurement of
        // the candidate. The tuner host additionally owns hard termination of a stuck evaluator.
        request.isBudgetExceeded(wallDurationNanos) -> {
            status = Cvc5TrialStatus.TIMEOUT
            correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED
            censored = true
            cost = null
        }

        outcome != REPLAY_OUTCOME_SUCCESS -> {
            status = Cvc5TrialStatus.INFRASTRUCTURE_FAILURE
            correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED
            censored = false
            cost = null
        }

        knownStatusTimeoutCount > 0 -> {
            status = Cvc5TrialStatus.TIMEOUT
            correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED
            censored = true
            cost = null
        }

        else -> {
            status = Cvc5TrialStatus.SUCCESS
            correctness = Cvc5TrialCorrectness.CORRECT
            censored = false
            cost = wallDurationNanos.toDouble()
        }
    }

    return Cvc5TrialObservation(
        requestId = request.requestId,
        status = status,
        cost = cost,
        duration = wallDurationNanos.nanoseconds,
        censored = censored,
        correctness = correctness,
        artifactReferences = mapOf("profileId" to profileId, "runId" to runId),
        attributes = replayAttributes(),
    )
}

internal fun Cvc5TrialRequest.cancelledObservation(): Cvc5TrialObservation =
    Cvc5TrialObservation(
        requestId = requestId,
        status = Cvc5TrialStatus.CANCELLED,
        cost = null,
        duration = Duration.ZERO,
        censored = false,
        correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED,
    )

internal fun Cvc5TrialRequest.infrastructureFailure(message: String): Cvc5TrialObservation =
    Cvc5TrialObservation(
        requestId = requestId,
        status = Cvc5TrialStatus.INFRASTRUCTURE_FAILURE,
        cost = null,
        duration = Duration.ZERO,
        censored = false,
        correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED,
        attributes = mapOf("failure" to message),
    )

internal fun Cvc5TrialRequest.invalidConfigurationObservation(): Cvc5TrialObservation =
    Cvc5TrialObservation(
        requestId = requestId,
        status = Cvc5TrialStatus.INVALID_CONFIGURATION,
        cost = null,
        duration = Duration.ZERO,
        censored = false,
        correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED,
    )

internal fun Cvc5TrialRequest.timedOutObservation(): Cvc5TrialObservation =
    Cvc5TrialObservation(
        requestId = requestId,
        status = Cvc5TrialStatus.TIMEOUT,
        cost = null,
        duration = Duration.ZERO,
        censored = true,
        correctness = Cvc5TrialCorrectness.NOT_ESTABLISHED,
    )

/** Whether a replay of [durationNanos] already exceeded the finite trial budget of this request. */
internal fun Cvc5TrialRequest.isBudgetExceeded(durationNanos: Long): Boolean =
    !timeout.isInfinite() && durationNanos.nanoseconds > timeout

private fun TvmCvc5ProfileReplayResult.replayAttributes(): Map<String, String> =
    buildMap {
        put("outcome", outcome)
        put("backendRole", backendRole)
        put("checkCount", checkCount.toString())
        put("knownStatusMatchCount", knownStatusMatchCount.toString())
        put("knownStatusTimeoutCount", knownStatusTimeoutCount.toString())
        put("wrongAnswerCount", wrongAnswerCount.toString())
        put("expectedUnknownCount", expectedUnknownCount.toString())
        put("resolvedUnknownCount", resolvedUnknownCount.toString())
        failureType?.let { put("failureType", it) }
        failureMessage?.let { put("failureMessage", it) }
    }
