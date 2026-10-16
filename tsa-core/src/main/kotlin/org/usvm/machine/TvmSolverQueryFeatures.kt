package org.usvm.machine

import io.ksmt.expr.KConst
import io.ksmt.expr.KExistentialQuantifier
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFunctionApp
import io.ksmt.expr.KInterpretedValue
import io.ksmt.expr.KUniversalQuantifier
import io.ksmt.expr.transformer.KExprVisitResult
import io.ksmt.expr.transformer.KNonRecursiveVisitor
import io.ksmt.sort.KArraySortBase
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KBvSort
import io.ksmt.sort.KFpSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KRealSort
import io.ksmt.sort.KSort
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class TvmSolverQueryFeatures(
    val fingerprint: String,
    val assertionCount: Int,
    val assumptionCount: Int,
    val treeNodeCount: Long,
    val uniqueNodeCount: Long,
    val uninterpretedConstantCount: Long,
    val functionApplicationCount: Long,
    val interpretedValueCount: Long,
    val quantifierCount: Long,
    val operatorHistogram: Map<String, Long>,
    val sortHistogram: Map<String, Long>,
    val bitVectorWidthHistogram: Map<String, Long>,
) {
    companion object {
        const val SCHEMA_VERSION = "ksmt-query-features-v0"
        const val FINGERPRINT_ALGORITHM = "ksmt-structural-hash-sha256-v0"

        fun extract(
            assertions: List<KExpr<*>>,
            assumptions: List<KExpr<*>>,
        ): TvmSolverQueryFeatures {
            val expressions = assertions + assumptions
            val collector = expressions.firstOrNull()?.let { AstSummaryCollector(it.ctx) }
            val treeNodeCount = expressions.sumOf { collector?.applyVisitor(it) ?: 0L }

            return TvmSolverQueryFeatures(
                fingerprint = fingerprint(assertions, assumptions),
                assertionCount = assertions.size,
                assumptionCount = assumptions.size,
                treeNodeCount = treeNodeCount,
                uniqueNodeCount = collector?.uniqueNodeCount ?: 0,
                uninterpretedConstantCount = collector?.uninterpretedConstantCount ?: 0,
                functionApplicationCount = collector?.functionApplicationCount ?: 0,
                interpretedValueCount = collector?.interpretedValueCount ?: 0,
                quantifierCount = collector?.quantifierCount ?: 0,
                operatorHistogram = collector?.operatorHistogram?.toSortedMap().orEmpty(),
                sortHistogram = collector?.sortHistogram?.toSortedMap().orEmpty(),
                bitVectorWidthHistogram = collector?.bitVectorWidthHistogram?.toSortedMap().orEmpty(),
            )
        }

        private fun fingerprint(
            assertions: List<KExpr<*>>,
            assumptions: List<KExpr<*>>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateInt(assertions.size)
            assertions.forEach { digest.updateExpression(it) }
            digest.updateInt(assumptions.size)
            assumptions.forEach { digest.updateExpression(it) }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private fun MessageDigest.updateExpression(expression: KExpr<*>) {
            updateInt(expression.hashCode())
            updateInt(expression.sort.hashCode())
            updateInt(expression::class.qualifiedName.hashCode())
        }

        private fun MessageDigest.updateInt(value: Int) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
        }
    }
}

private class AstSummaryCollector(
    context: io.ksmt.KContext,
) : KNonRecursiveVisitor<Long>(context) {
    var uniqueNodeCount = 0L
        private set
    var uninterpretedConstantCount = 0L
        private set
    var functionApplicationCount = 0L
        private set
    var interpretedValueCount = 0L
        private set
    var quantifierCount = 0L
        private set

    val operatorHistogram = mutableMapOf<String, Long>()
    val sortHistogram = mutableMapOf<String, Long>()
    val bitVectorWidthHistogram = mutableMapOf<String, Long>()

    override fun <T : KSort> defaultValue(expr: KExpr<T>): Long = 1L

    override fun mergeResults(
        left: Long,
        right: Long,
    ): Long = left + right

    override fun <T : KSort> visitExpr(expr: KExpr<T>): KExprVisitResult<Long> = saveVisitResult(expr, 1L)

    override fun <T : KSort> onNewVisitResult(
        expr: KExpr<T>,
        result: Long,
    ) {
        uniqueNodeCount++
        operatorHistogram.increment(expr::class.simpleName ?: "unknown")
        sortHistogram.increment(expr.sort.featureName())

        (expr.sort as? KBvSort)?.let { bitVectorWidthHistogram.increment(it.sizeBits.toString()) }
        if (expr is KConst<*>) uninterpretedConstantCount++
        if (expr is KFunctionApp<*>) functionApplicationCount++
        if (expr is KInterpretedValue<*>) interpretedValueCount++
        if (expr is KExistentialQuantifier || expr is KUniversalQuantifier) quantifierCount++
    }

    private fun MutableMap<String, Long>.increment(key: String) {
        this[key] = getOrDefault(key, 0) + 1
    }

    private fun KSort.featureName(): String =
        when (this) {
            is KBoolSort -> "bool"
            is KBvSort -> "bit-vector"
            is KIntSort -> "integer"
            is KRealSort -> "real"
            is KArraySortBase<*> -> "array"
            is KFpSort -> "floating-point"
            else -> this::class.simpleName ?: "unknown"
        }
}
