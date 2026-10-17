package org.ton.tuning

import io.ksmt.ml.tuner.search.Cvc5SearchInstance
import io.ksmt.ml.tuner.search.Cvc5TrialEvaluator

/** Stable reference to a TSA workload that can be resolved without embedding its contents. */
data class TsaWorkloadReference(
    val workloadId: String,
    val revision: String,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * A resolved TSA workload for cvc5 configuration search.
 *
 * The evaluator must recognize exactly the advertised [instances]. Every evaluation must run in
 * isolation with a fresh solver, apply the validated configuration before the first assertion,
 * and report correctness through the tuner-owned observation contract.
 */
interface TsaCvc5TrialWorkload : Cvc5TrialEvaluator {
    val reference: TsaWorkloadReference
    val solverVersion: String
    val parameterSpaceId: String
    val instances: List<Cvc5SearchInstance>
}

/** Resolves a stable workload reference into the evaluator passed directly to ksmt-ml-tuner. */
fun interface TsaCvc5TrialWorkloadResolver {
    fun resolve(reference: TsaWorkloadReference): TsaCvc5TrialWorkload
}
