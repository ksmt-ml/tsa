package org.usvm.machine

import io.ksmt.expr.KExpr
import io.ksmt.profiling.KSolverAssertBatchEvent
import io.ksmt.profiling.KSolverAssertEvent
import io.ksmt.profiling.KSolverCallMetadata
import io.ksmt.profiling.KSolverCallOutcome
import io.ksmt.profiling.KSolverCheckEvent
import io.ksmt.profiling.KSolverConfigurationValue
import io.ksmt.profiling.KSolverConfigureEvent
import io.ksmt.profiling.KSolverLifecycleEvent
import io.ksmt.profiling.KSolverPopEvent
import io.ksmt.profiling.KSolverProfile
import io.ksmt.profiling.KSolverProfileEvent
import io.ksmt.profiling.KSolverProfileState
import io.ksmt.profiling.KSolverPushEvent
import io.ksmt.profiling.KSolverResultAccessEvent
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KSort
import io.ksmt.utils.uncheckedCast
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.usvm.machine.intblast.TvmBvNonRecursiveTransformer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import kotlin.time.Duration

/** Writes TSA-produced solver profiles without introducing persistence into the KSMT contract. */
class TvmSolverProfileWriter(
    private val outputDirectory: Path,
) {
    /** Rewrites the same artifact for successive snapshots of one profile. */
    @Synchronized
    fun write(profile: KSolverProfile): Path {
        Files.createDirectories(outputDirectory)

        val fileName = "tsa-solver-profile-${profile.runId.fileNamePart()}-${profile.profileId.fileNamePart()}.json"
        val target = outputDirectory.resolve(fileName)
        val temporary = Files.createTempFile(outputDirectory, ".$fileName-", ".tmp")

        try {
            Files.newBufferedWriter(temporary).use { writer ->
                writer.write(json.encodeToString(JsonObject.serializer(), profile.toJson()))
            }
            moveCompletedProfile(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }

        return target
    }

    private fun moveCompletedProfile(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun KSolverProfile.toJson(): JsonObject =
        try {
            toJson(replayEnabled = true, replayFailureType = null)
        } catch (failure: Exception) {
            // Profiling must never turn a successful analysis into a failed one. Keep the complete
            // event/timing trace even when a TSA-specific expression cannot be made replayable.
            toJson(replayEnabled = false, replayFailureType = failure.javaClass.name)
        }

    private fun KSolverProfile.toJson(
        replayEnabled: Boolean,
        replayFailureType: String?,
    ): JsonObject {
        val astEncoder = replayEnabled.takeIf { it }?.let { TvmSolverProfileAstEncoder() }
        val queryState = replayEnabled.takeIf { it }?.let { QueryState() }
        val expressionNormalizer =
            replayEnabled.takeIf { it }?.let {
                ReplayExpressionNormalizer(attributes[EXPRESSION_NORMALIZATION_ATTRIBUTE] ?: IDENTITY_NORMALIZATION)
            }
        val truncated = isTruncated
        val encodedEvents =
            events.map { event ->
                event.toJson(astEncoder, queryState, expressionNormalizer).also {
                    if (queryState != null && expressionNormalizer != null) {
                        queryState.applySuccessful(event, expressionNormalizer)
                    }
                }
            }

        return buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("profileId", profileId)
            put("runId", runId)
            putNullable("parentProfileId", parentProfileId)
            put("state", state.name)
            put(
                "producer",
                buildJsonObject {
                    put("name", "tsa")
                    put("format", FORMAT_VERSION)
                    put("expressionSummary", EXPRESSION_SUMMARY_VERSION)
                    put(
                        "expressionNormalization",
                        attributes[EXPRESSION_NORMALIZATION_ATTRIBUTE] ?: IDENTITY_NORMALIZATION,
                    )
                    put("replayEncoding", TvmSolverProfileAstEncoder.FORMAT_VERSION)
                    put("featureSchema", TvmSolverQueryFeatures.SCHEMA_VERSION)
                    put("fingerprintAlgorithm", TvmSolverQueryFeatures.FINGERPRINT_ALGORITHM)
                    put("replayable", replayEnabled && !truncated)
                    put("captureComplete", state == KSolverProfileState.CLOSED && !truncated)
                    putNullable("replayFailureType", replayFailureType)
                },
            )
            put(
                "solver",
                buildJsonObject {
                    put("name", solver.name)
                    putNullable("version", solver.version)
                    put("attributes", solver.attributes.toJson())
                },
            )
            put("startedAtEpochMillis", startedAt.toEpochMilli())
            put("observedDurationNanos", observedDuration.inWholeNanoseconds)
            put("eventLimit", eventLimit)
            put("droppedEventCount", droppedEventCount)
            put(
                "initialConfiguration",
                buildJsonObject {
                    put("base", initialConfiguration.base.name)
                    put(
                        "overrides",
                        JsonObject(initialConfiguration.overrides.mapValues { (_, value) -> value.toJson() }),
                    )
                },
            )
            put("attributes", attributes.toJson())
            put("events", JsonArray(encodedEvents))
        }
    }

    private fun KSolverProfileEvent.toJson(
        astEncoder: TvmSolverProfileAstEncoder?,
        queryState: QueryState?,
        expressionNormalizer: ReplayExpressionNormalizer?,
    ): JsonObject =
        buildJsonObject {
            when (this@toJson) {
                is KSolverConfigureEvent -> {
                    put("type", "configure")
                    put(
                        "overrides",
                        overrides?.let { values ->
                            JsonObject(values.mapValues { (_, value) -> value.toJson() })
                        } ?: JsonNull,
                    )
                }

                is KSolverAssertEvent -> {
                    put("type", "assert")
                    put("expressionSummary", expression.toSummaryJson())
                    if (astEncoder != null && expressionNormalizer != null) {
                        put("expressionAst", astEncoder.encode(expressionNormalizer.normalize(expression)).toJson())
                    }
                    put("tracked", tracked)
                }

                is KSolverAssertBatchEvent -> {
                    put("type", "assert-batch")
                    put("expressionSummaries", buildJsonArray { expressions.forEach { add(it.toSummaryJson()) } })
                    if (astEncoder != null && expressionNormalizer != null) {
                        put(
                            "expressionAsts",
                            buildJsonArray {
                                expressions.forEach { expression ->
                                    add(astEncoder.encode(expressionNormalizer.normalize(expression)).toJson())
                                }
                            },
                        )
                    }
                    put("tracked", tracked)
                }

                is KSolverPushEvent -> put("type", "push")

                is KSolverPopEvent -> {
                    put("type", "pop")
                    put("levels", levels.toLong())
                }

                is KSolverCheckEvent -> {
                    put("type", "check")
                    put("checkId", checkId)
                    put("assumptionSummaries", buildJsonArray { assumptions.forEach { add(it.toSummaryJson()) } })
                    if (astEncoder != null && queryState != null && expressionNormalizer != null) {
                        val normalizedAssumptions = assumptions.map(expressionNormalizer::normalize)
                        put(
                            "assumptionAsts",
                            buildJsonArray {
                                normalizedAssumptions.forEach { add(astEncoder.encode(it).toJson()) }
                            },
                        )
                        put(
                            "query",
                            TvmSolverQueryFeatures.extract(queryState.assertions(), normalizedAssumptions).toJson(),
                        )
                    }
                    putNullable(
                        "timeoutNanos",
                        timeout.takeUnless { it == Duration.INFINITE }?.inWholeNanoseconds,
                    )
                    putNullable("status", status?.name)
                }

                is KSolverResultAccessEvent -> {
                    put("type", "result-access")
                    put("kind", kind.name)
                    putNullable("checkId", checkId)
                }

                is KSolverLifecycleEvent -> {
                    put("type", "lifecycle")
                    put("kind", kind.name)
                }
            }
            put("metadata", metadata.toJson())
        }

    private fun KExpr<*>.toSummaryJson(): JsonObject =
        buildJsonObject {
            put("node", this@toSummaryJson::class.qualifiedName ?: "unknown")
            put("sort", sort::class.qualifiedName ?: "unknown")
        }

    private fun TvmEncodedAst.toJson(): JsonObject =
        buildJsonObject {
            put("streamOrdinal", streamOrdinal)
            put("data", data)
        }

    private fun TvmSolverQueryFeatures.toJson(): JsonObject =
        buildJsonObject {
            put("schemaVersion", TvmSolverQueryFeatures.SCHEMA_VERSION)
            put("fingerprintAlgorithm", TvmSolverQueryFeatures.FINGERPRINT_ALGORITHM)
            put("fingerprint", fingerprint)
            put("assertionCount", assertionCount)
            put("assumptionCount", assumptionCount)
            put("treeNodeCount", treeNodeCount)
            put("uniqueNodeCount", uniqueNodeCount)
            put("uninterpretedConstantCount", uninterpretedConstantCount)
            put("functionApplicationCount", functionApplicationCount)
            put("interpretedValueCount", interpretedValueCount)
            put("quantifierCount", quantifierCount)
            put("operatorHistogram", operatorHistogram.toJsonNumbers())
            put("sortHistogram", sortHistogram.toJsonNumbers())
            put("bitVectorWidthHistogram", bitVectorWidthHistogram.toJsonNumbers())
        }

    private fun KSolverCallMetadata.toJson(): JsonObject =
        buildJsonObject {
            put("sequenceNumber", sequenceNumber)
            put("startedAfterSessionNanos", startedAfterSession.inWholeNanoseconds)
            put("durationNanos", duration.inWholeNanoseconds)
            put("outcome", outcome.name)
            put("stackDepth", stackDepth)
            put("activeAssertionCount", activeAssertionCount)
            put("totalAssertionCount", totalAssertionCount)
            putNullable("failureType", failureType)
            putNullable("failureMessage", failureMessage)
            put(
                "task",
                task?.let { task ->
                    buildJsonObject {
                        put("id", task.id)
                        putNullable("kind", task.kind)
                        put("attributes", task.attributes.toJson())
                    }
                } ?: JsonNull,
            )
        }

    private fun KSolverConfigurationValue.toJson(): JsonPrimitive =
        when (this) {
            is KSolverConfigurationValue.BoolValue -> JsonPrimitive(value)
            is KSolverConfigurationValue.IntValue -> JsonPrimitive(value)
            is KSolverConfigurationValue.StringValue -> JsonPrimitive(value)
            is KSolverConfigurationValue.DoubleValue -> JsonPrimitive(value)
        }

    private fun Map<String, String>.toJson(): JsonObject = JsonObject(mapValues { (_, value) -> JsonPrimitive(value) })

    private fun Map<String, Long>.toJsonNumbers(): JsonObject =
        JsonObject(mapValues { (_, value) -> JsonPrimitive(value) })

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        key: String,
        value: String?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        key: String,
        value: Long?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun String.fileNamePart(): String {
        val sanitized = map { character -> if (character.isLetterOrDigit() || character in "-_") character else '-' }
        return sanitized.joinToString(separator = "").take(MAX_FILE_NAME_ID_LENGTH).ifBlank { "unknown" }
    }

    private class QueryState {
        private val scopes = arrayListOf<MutableList<KExpr<KBoolSort>>>(arrayListOf())

        fun assertions(): List<KExpr<KBoolSort>> = scopes.flatten()

        fun applySuccessful(
            event: KSolverProfileEvent,
            expressionNormalizer: ReplayExpressionNormalizer,
        ) {
            if (event.metadata.outcome != KSolverCallOutcome.SUCCESS) return

            when (event) {
                is KSolverAssertEvent -> scopes.last() += expressionNormalizer.normalize(event.expression)
                is KSolverAssertBatchEvent -> scopes.last() += event.expressions.map(expressionNormalizer::normalize)
                is KSolverPushEvent -> scopes += arrayListOf<KExpr<KBoolSort>>()
                is KSolverPopEvent -> {
                    val removedScopes = minOf(event.levels.toLong(), scopes.lastIndex.toLong()).toInt()
                    repeat(removedScopes) { scopes.removeLast() }
                }

                else -> Unit
            }
        }
    }

    private class ReplayExpressionNormalizer(
        private val mode: String,
    ) {
        private val normalizedExpressions = IdentityHashMap<KExpr<*>, KExpr<*>>()
        private var tvmTransformer: TvmBvNonRecursiveTransformer? = null

        @Suppress("UNCHECKED_CAST")
        fun <Sort : KSort> normalize(expression: KExpr<Sort>): KExpr<Sort> {
            if (mode == IDENTITY_NORMALIZATION) return expression
            require(mode == TSA_BV_ARITHMETIC_NORMALIZATION) { "Unknown expression normalization: $mode" }

            return normalizedExpressions
                .getOrPut(expression) {
                    val tvmContext =
                        expression.ctx as? TvmContext
                            ?: error("TSA expression normalization requires TvmContext")
                    val transformer =
                        tvmTransformer ?: TvmBvNonRecursiveTransformer(tvmContext).also { tvmTransformer = it }
                    transformer.apply(expression)
                }.uncheckedCast()
        }
    }

    companion object {
        const val FORMAT_VERSION = "tsa-solver-profile-json-v0"
        const val EXPRESSION_SUMMARY_VERSION = "ksmt-root-kind-v0"
        const val EXPRESSION_NORMALIZATION_ATTRIBUTE = "expression-normalization"
        const val IDENTITY_NORMALIZATION = "identity-v0"
        const val TSA_BV_ARITHMETIC_NORMALIZATION = "tsa-bv-arithmetic-v0"
        private const val MAX_FILE_NAME_ID_LENGTH = 80

        private val json =
            Json {
                prettyPrint = true
            }
    }
}
