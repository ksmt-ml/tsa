package org.ton.tuning.mvp

import io.ksmt.ml.tuner.configuration.Cvc5ConfigurationPatch
import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpace
import io.ksmt.ml.tuner.configuration.Cvc5PatchValidation
import io.ksmt.ml.tuner.configuration.Cvc5ValidatedConfigurationPatch
import io.ksmt.ml.tuner.search.Cvc5CancellationToken
import io.ksmt.ml.tuner.search.Cvc5TrialRequest
import io.ksmt.ml.tuner.search.Cvc5TrialStatus
import org.ton.tuning.TsaCvc5TrialWorkload
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Duration

/**
 * Result of measuring one configuration over every instance of a workload.
 *
 * [costsNanos] holds only the instances that produced a correct ranked answer. Instances that timed
 * out, crashed, or answered incorrectly are counted separately and contribute no cost.
 */
data class MvpMeasurement(
    val label: String,
    val options: Map<String, String>,
    val instanceCount: Int,
    val costsNanos: Map<String, Long>,
    val wrongAnswerCount: Int,
    val timeoutCount: Int,
    val failureCount: Int,
) {
    val rankedCount: Int get() = costsNanos.size

    val totalCostNanos: Long get() = costsNanos.values.sum()

    val totalCostMillis: Double get() = totalCostNanos / 1_000_000.0

    /** A candidate is eligible only when every instance produced a correct ranked answer. */
    val isEligible: Boolean get() = rankedCount == instanceCount && wrongAnswerCount == 0
}

/**
 * Measures candidate configurations by replaying the workload through the tuner contract.
 *
 * Scoring follows the demo objective: per-instance costs are normalized by the baseline cost of the
 * same instance and combined as a geometric mean, so instances of very different size stay
 * comparable. A censored instance contributes a fixed penalty, matching the `censored-par10-v0`
 * baseline policy of the demo campaign.
 */
class MvpSearch(
    private val workload: TsaCvc5TrialWorkload,
    private val parameterSpace: Cvc5ParameterSpace,
    private val trialTimeout: Duration,
    private val seed: Long,
) {
    /** Cost attributed to an instance that produced no usable measurement. */
    val timeoutPenaltyNanos: Long = (trialTimeout * TIMEOUT_PENALTY_MULTIPLIER).inWholeNanoseconds

    fun measure(
        label: String,
        options: Map<String, String>,
    ): MvpMeasurement {
        val configuration = validate(label, options)

        val costs = mutableMapOf<String, Long>()
        var wrongAnswers = 0
        var timeouts = 0
        var failures = 0

        workload.instances.forEachIndexed { index, instance ->
            val request =
                Cvc5TrialRequest(
                    searchId = "tsa-tuning-mvp",
                    requestId = "$label#$index",
                    proposalIndex = index.toLong(),
                    solverVersion = workload.solverVersion,
                    configuration = configuration,
                    instanceId = instance.id,
                    seed = seed,
                    repetition = 0,
                    timeout = trialTimeout,
                )

            val observation = workload.evaluate(request, Cvc5CancellationToken.NONE)
            when (observation.status) {
                Cvc5TrialStatus.SUCCESS -> {
                    val cost = observation.cost
                    if (cost == null) {
                        failures++
                    } else {
                        costs[instance.id] = cost.toLong()
                    }
                }

                Cvc5TrialStatus.WRONG_ANSWER -> wrongAnswers++
                Cvc5TrialStatus.TIMEOUT -> timeouts++
                else -> failures++
            }
        }

        return MvpMeasurement(
            label = label,
            options = options,
            instanceCount = workload.instances.size,
            costsNanos = costs,
            wrongAnswerCount = wrongAnswers,
            timeoutCount = timeouts,
            failureCount = failures,
        )
    }

    /**
     * Geometric mean of the per-instance cost ratio against [baseline]; below 1.0 means faster.
     *
     * An instance missing from either measurement contributes [timeoutPenaltyNanos].
     */
    fun normalizedScore(
        baseline: MvpMeasurement,
        candidate: MvpMeasurement,
    ): Double? {
        val ratios =
            workload.instances.mapNotNull { instance ->
                val base = baseline.costsNanos[instance.id] ?: timeoutPenaltyNanos
                val value = candidate.costsNanos[instance.id] ?: timeoutPenaltyNanos
                if (base <= 0L) null else value.toDouble() / base.toDouble()
            }
        if (ratios.isEmpty()) return null
        return exp(ratios.sumOf { ln(it) } / ratios.size)
    }

    private fun validate(
        label: String,
        options: Map<String, String>,
    ): Cvc5ValidatedConfigurationPatch {
        val patch = Cvc5ConfigurationPatch(parameterSpace.parameterSpaceId, options)
        return when (val validation = parameterSpace.validate(patch)) {
            is Cvc5PatchValidation.Accepted -> validation.configuration
            is Cvc5PatchValidation.Rejected ->
                error("Candidate $label was rejected by the parameter space: ${validation.reason}")
        }
    }

    private companion object {
        /** PAR10-like multiplier applied to a censored instance. */
        const val TIMEOUT_PENALTY_MULTIPLIER = 10
    }
}
