package org.usvm.machine

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KTheory
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.uncheckedCast
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Candidate cvc5 configuration consumed by the offline profile replay evaluator. */
@Serializable
data class TvmCvc5ReplayCandidate(
    val schemaVersion: String,
    val candidateId: String,
    val parameterSpaceId: String,
    val targetSolver: String,
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported candidate schema: $schemaVersion" }
        require(candidateId.isNotBlank()) { "Candidate id must not be blank" }
        require(parameterSpaceId.isNotBlank()) { "Parameter-space id must not be blank" }
        require(targetSolver == "cvc5") { "Replay candidate must target cvc5" }
        require(options.keys.none { it.isBlank() }) { "cvc5 option names must not be blank" }
        require(options.values.none { it.isBlank() }) { "cvc5 option values must not be blank" }
        require(options.keys.none { it in PROTECTED_OPTIONS }) {
            "Candidate changes an evaluator-controlled cvc5 option"
        }
    }

    companion object {
        const val SCHEMA_VERSION = "cvc5-replay-candidate-v0"

        val PROTECTED_OPTIONS =
            setOf(
                "logic",
                "tlimit-per",
                "produce-models",
                "produce-unsat-cores",
                "incremental",
                "arrays-exp",
                "fp-exp",
            )
    }
}

@Serializable
data class TvmCvc5ReplayCheckMismatch(
    val checkId: Long,
    val expectedStatus: String,
    val actualStatus: String,
    val timeoutNanos: Long? = null,
)

/** Live, non-persistent progress for a single profile replay. */
data class TvmCvc5ReplayProgress(
    val phase: String,
    val processedEventCount: Int,
    val eventCount: Int,
    val executedEventCount: Int,
    val checkCount: Int,
    val currentEventType: String? = null,
    val currentCheckId: Long? = null,
)

@Serializable
data class TvmCvc5ProfileReplayResult(
    val schemaVersion: String = SCHEMA_VERSION,
    val candidateId: String,
    val parameterSpaceId: String,
    val profileId: String,
    val runId: String,
    val solverVersion: String?,
    val backendRole: String,
    val repetition: Int,
    val outcome: String,
    val wallDurationNanos: Long,
    val decodeDurationNanos: Long,
    val solverOperationDurationNanos: Long,
    val nonCheckDurationNanos: Long,
    val knownCheckDurationNanos: Long,
    val unknownCheckDurationNanos: Long,
    val knownStatusTimeoutDurationNanos: Long,
    val knownStatusTimeoutBudgetNanos: Long,
    val eventCount: Int,
    val executedEventCount: Int,
    val recordedFailureEventCount: Int,
    val skippedResultAccessCount: Int,
    val checkCount: Int,
    val knownStatusMatchCount: Int,
    val knownStatusTimeoutCount: Int,
    val wrongAnswerCount: Int,
    val expectedUnknownCount: Int,
    val resolvedUnknownCount: Int,
    val actualStatusCounts: Map<String, Int>,
    val operationDurationNanos: Map<String, Long>,
    val mismatches: List<TvmCvc5ReplayCheckMismatch>,
    val failureType: String? = null,
    val failureMessage: String? = null,
) {
    companion object {
        const val SCHEMA_VERSION = "tsa-cvc5-profile-replay-result-v0"
    }
}

@Serializable
data class TvmCvc5ReplayBatchReport(
    val schemaVersion: String = SCHEMA_VERSION,
    val createdAtEpochMillis: Long,
    val candidate: TvmCvc5ReplayCandidate,
    val backendRole: String,
    val repetitions: Int,
    val warmupRepetitions: Int,
    val sourceProfiles: List<String>,
    val results: List<TvmCvc5ProfileReplayResult>,
) {
    companion object {
        const val SCHEMA_VERSION = "tsa-cvc5-profile-replay-report-v0"
    }
}

/** Replays a complete TSA backend profile against a fresh cvc5 instance. */
class TvmCvc5ProfileReplayEvaluator {
    fun parseCandidate(path: Path): TvmCvc5ReplayCandidate =
        json.decodeFromString(TvmCvc5ReplayCandidate.serializer(), path.readText())

    fun parseProfile(path: Path): JsonObject = json.parseToJsonElement(path.readText()).jsonObject

    fun evaluate(
        profile: JsonObject,
        candidate: TvmCvc5ReplayCandidate,
        repetition: Int,
        onProgress: (TvmCvc5ReplayProgress) -> Unit = {},
    ): TvmCvc5ProfileReplayResult {
        require(repetition >= 0) { "Repetition must not be negative" }
        val header = validateProfile(profile)
        var resultHeader = header
        val events = profile.requiredArray("events")
        val metrics = ReplayMetrics(events.size)
        val wallStartedAt = System.nanoTime()
        onProgress(metrics.progress(phase = "STARTING", processedEventCount = 0))

        try {
            KContext().use { context ->
                val decoder = TvmSolverProfileAstDecoder(context)
                val solver = KCvc5Solver(context)
                var solverClosed = false

                try {
                    onProgress(
                        metrics.progress(
                            phase = "CONFIGURING",
                            processedEventCount = 0,
                            currentEventType = "configure",
                        ),
                    )
                    metrics.measureNonCheck("configure") {
                        configureSolver(solver, profile.requiredObject("initialConfiguration"), candidate)
                    }
                    val runtimeSolverVersion = solver.nativeSolver().version
                    require(header.solverVersion == null || header.solverVersion == runtimeSolverVersion) {
                        "Profile cvc5 version ${header.solverVersion} does not match runtime cvc5 $runtimeSolverVersion"
                    }
                    resultHeader = header.copy(solverVersion = runtimeSolverVersion)

                    events.forEachIndexed { eventIndex, element ->
                        val event = element.jsonObject
                        val type = event.requiredString("type")
                        val metadata = event.requiredObject("metadata")
                        val recordedSuccess = metadata.requiredString("outcome") == "SUCCESS"
                        onProgress(
                            metrics.progress(
                                phase = "REPLAYING",
                                processedEventCount = eventIndex,
                                currentEventType = type,
                                currentCheckId = if (type == "check") event.requiredLong("checkId") else null,
                            ),
                        )

                        val decoded = decodeEventExpressions(type, event, decoder, metrics)
                        if (!recordedSuccess) {
                            metrics.recordedFailureEventCount++
                            onProgress(
                                metrics.progress(
                                    phase = "REPLAYING",
                                    processedEventCount = eventIndex + 1,
                                    currentEventType = type,
                                ),
                            )
                            return@forEachIndexed
                        }

                        when (type) {
                            "configure" -> replayConfigure(solver, event, metrics)
                            "assert" ->
                                metrics.measureNonCheck(type) {
                                    val expression = decoded.single().asBooleanExpression(context)
                                    if (event.requiredBoolean("tracked")) {
                                        solver.assertAndTrack(expression)
                                    } else {
                                        solver.assert(expression)
                                    }
                                }

                            "assert-batch" ->
                                metrics.measureNonCheck(type) {
                                    val expressions = decoded.map { it.asBooleanExpression(context) }
                                    if (event.requiredBoolean("tracked")) {
                                        solver.assertAndTrack(expressions)
                                    } else {
                                        solver.assert(expressions)
                                    }
                                }

                            "push" -> metrics.measureNonCheck(type, solver::push)
                            "pop" ->
                                metrics.measureNonCheck(type) {
                                    solver.pop(event.requiredLong("levels").toUInt())
                                }

                            "check" -> replayCheck(solver, event, decoded, metrics)
                            "result-access" -> replayResultAccess(solver, event, metrics)
                            "lifecycle" -> {
                                when (event.requiredString("kind")) {
                                    "INTERRUPT" -> metrics.measureNonCheck("interrupt", solver::interrupt)
                                    "CLOSE" -> {
                                        metrics.measureNonCheck("close", solver::close)
                                        solverClosed = true
                                    }

                                    else -> error("Unsupported lifecycle event kind")
                                }
                            }

                            else -> error("Unsupported profile event type: $type")
                        }
                        metrics.executedEventCount++
                        onProgress(
                            metrics.progress(
                                phase = "REPLAYING",
                                processedEventCount = eventIndex + 1,
                                currentEventType = type,
                            ),
                        )
                    }
                } finally {
                    if (!solverClosed) {
                        metrics.measureNonCheck("close", solver::close)
                    }
                }
            }

            onProgress(metrics.progress(phase = "COMPLETED", processedEventCount = events.size))

            return metrics.result(
                candidate = candidate,
                header = resultHeader,
                repetition = repetition,
                outcome = "SUCCESS",
                wallDurationNanos = elapsedSince(wallStartedAt),
            )
        } catch (failure: Exception) {
            onProgress(metrics.progress(phase = "FAILED", processedEventCount = metrics.processedEventCount))
            return metrics.result(
                candidate = candidate,
                header = resultHeader,
                repetition = repetition,
                outcome = "FAILURE",
                wallDurationNanos = elapsedSince(wallStartedAt),
                failure = failure,
            )
        }
    }

    private fun validateProfile(profile: JsonObject): ProfileHeader {
        require(profile.requiredString("schemaVersion") == "ksmt-solver-profile-v0") {
            "Unsupported solver profile schema"
        }
        require(profile.requiredString("state") == "CLOSED") { "Only closed profiles can be replayed" }

        val producer = profile.requiredObject("producer")
        require(producer.requiredString("format") == TvmSolverProfileWriter.FORMAT_VERSION) {
            "Unsupported TSA profile format"
        }
        require(producer.requiredString("replayEncoding") == TvmSolverProfileAstEncoder.FORMAT_VERSION) {
            "Unsupported AST replay encoding"
        }
        require(producer.requiredBoolean("replayable")) { "Profile is not replayable" }
        require(producer.requiredBoolean("captureComplete")) { "Profile capture is incomplete" }

        val solver = profile.requiredObject("solver")
        require(solver.requiredString("name") == "cvc5") { "Only direct cvc5 backend profiles can be replayed" }
        val backendRole = solver.requiredObject("attributes").requiredString("backend-role")
        require(backendRole == "regular-bv" || backendRole == "integer-blasted") {
            "Unsupported cvc5 backend role: $backendRole"
        }

        return ProfileHeader(
            profileId = profile.requiredString("profileId"),
            runId = profile.requiredString("runId"),
            solverVersion = solver.optionalString("version"),
            backendRole = backendRole,
        )
    }

    private fun configureSolver(
        solver: KCvc5Solver,
        initialConfiguration: JsonObject,
        candidate: TvmCvc5ReplayCandidate,
    ) {
        require(initialConfiguration.requiredString("base") == "SOLVER_DEFAULTS") {
            "Replay requires a solver-default configuration base"
        }
        val overrides = initialConfiguration.requiredObject("overrides")
        val theories =
            overrides["ksmt.optimize.theories"]
                ?.jsonPrimitive
                ?.content
                ?.split(',')
                ?.filter(String::isNotBlank)
                ?.mapTo(linkedSetOf()) { KTheory.valueOf(it) }
        val quantifiersAllowed =
            overrides["ksmt.quantifiers-allowed"]
                ?.jsonPrimitive
                ?.boolean
                ?: false
        val initialCvc5Options =
            overrides
                .filterKeys { it !in KSMT_CONFIGURATION_KEYS }
                .mapValues { (_, value) -> value.jsonPrimitive.content }
        require(initialCvc5Options.keys.none { it in TvmCvc5ReplayCandidate.PROTECTED_OPTIONS }) {
            "Initial profile changes an evaluator-controlled cvc5 option"
        }

        solver.configure {
            optimizeForTheories(theories, quantifiersAllowed)
            initialCvc5Options.toSortedMap().forEach { (option, value) ->
                setCvc5Option(option, value)
            }
            candidate.options.toSortedMap().forEach { (option, value) ->
                setCvc5Option(option, value)
            }
        }
    }

    private fun replayConfigure(
        solver: KCvc5Solver,
        event: JsonObject,
        metrics: ReplayMetrics,
    ) {
        val overrides = event["overrides"]
        require(overrides != null && overrides !is kotlinx.serialization.json.JsonNull) {
            "Opaque configure events cannot be replayed"
        }
        metrics.measureNonCheck("configure") {
            solver.configure {
                overrides.jsonObject.toSortedMap().forEach { (option, value) ->
                    require(option !in TvmCvc5ReplayCandidate.PROTECTED_OPTIONS) {
                        "Profile changes an evaluator-controlled cvc5 option: $option"
                    }
                    setCvc5Option(option, value.jsonPrimitive.content)
                }
            }
        }
    }

    private fun replayCheck(
        solver: KCvc5Solver,
        event: JsonObject,
        decoded: List<KExpr<*>>,
        metrics: ReplayMetrics,
    ) {
        val expected = KSolverStatus.valueOf(event.requiredString("status"))
        val timeoutNanos = event.optionalLong("timeoutNanos")
        val timeout = timeoutNanos?.nanoseconds ?: Duration.INFINITE
        val assumptions: List<KExpr<KBoolSort>> =
            decoded.map { expression ->
                require(expression.sort == expression.ctx.boolSort) { "Replay assumption is not Boolean" }
                expression.uncheckedCast()
            }
        val startedAt = System.nanoTime()
        val actual =
            try {
                if (assumptions.isEmpty()) {
                    solver.check(timeout)
                } else {
                    solver.checkWithAssumptions(assumptions, timeout)
                }
            } finally {
                metrics.recordOperationDuration("check", elapsedSince(startedAt))
            }
        val duration = metrics.lastOperationDurationNanos
        metrics.recordCheck(
            checkId = event.requiredLong("checkId"),
            expected = expected,
            actual = actual,
            timeoutNanos = timeoutNanos,
            durationNanos = duration,
        )
    }

    private fun replayResultAccess(
        solver: KCvc5Solver,
        event: JsonObject,
        metrics: ReplayMetrics,
    ) {
        when (event.requiredString("kind")) {
            "MODEL" ->
                if (metrics.lastCheckStatus == KSolverStatus.SAT) {
                    metrics.measureNonCheck("model") { solver.model() }
                } else {
                    metrics.skippedResultAccessCount++
                }

            "UNSAT_CORE" ->
                if (metrics.lastCheckStatus == KSolverStatus.UNSAT) {
                    metrics.measureNonCheck("unsat-core") { solver.unsatCore() }
                } else {
                    metrics.skippedResultAccessCount++
                }

            "REASON_OF_UNKNOWN" ->
                if (metrics.lastCheckStatus == KSolverStatus.UNKNOWN) {
                    metrics.measureNonCheck("reason-of-unknown") { solver.reasonOfUnknown() }
                } else {
                    metrics.skippedResultAccessCount++
                }

            else -> error("Unsupported result-access kind")
        }
    }

    private fun decodeEventExpressions(
        type: String,
        event: JsonObject,
        decoder: TvmSolverProfileAstDecoder,
        metrics: ReplayMetrics,
    ): List<KExpr<*>> =
        when (type) {
            "assert" -> listOf(metrics.decode(event.requiredObject("expressionAst"), decoder))
            "assert-batch" -> event.requiredArray("expressionAsts").map { metrics.decode(it.jsonObject, decoder) }
            "check" -> event.requiredArray("assumptionAsts").map { metrics.decode(it.jsonObject, decoder) }
            else -> emptyList()
        }

    private fun KExpr<*>.asBooleanExpression(context: KContext): KExpr<KBoolSort> {
        require(sort == context.boolSort) { "Replay assertion is not Boolean" }
        return uncheckedCast()
    }

    private fun ReplayMetrics.decode(
        encoded: JsonObject,
        decoder: TvmSolverProfileAstDecoder,
    ): KExpr<*> {
        val startedAt = System.nanoTime()
        return try {
            decoder.decode(
                TvmEncodedAst(
                    streamOrdinal = encoded.requiredLong("streamOrdinal"),
                    data = encoded.requiredString("data"),
                ),
            )
        } finally {
            decodeDurationNanos += elapsedSince(startedAt)
        }
    }

    private data class ProfileHeader(
        val profileId: String,
        val runId: String,
        val solverVersion: String?,
        val backendRole: String,
    )

    private class ReplayMetrics(
        private val eventCount: Int,
    ) {
        var executedEventCount = 0
        var recordedFailureEventCount = 0
        var skippedResultAccessCount = 0
        var decodeDurationNanos = 0L
        var lastOperationDurationNanos = 0L
        var lastCheckStatus: KSolverStatus? = null
        var processedEventCount = 0

        private var solverOperationDurationNanos = 0L
        private var nonCheckDurationNanos = 0L
        private var knownCheckDurationNanos = 0L
        private var unknownCheckDurationNanos = 0L
        private var knownStatusTimeoutDurationNanos = 0L
        private var knownStatusTimeoutBudgetNanos = 0L
        private var checkCount = 0
        private var knownStatusMatchCount = 0
        private var knownStatusTimeoutCount = 0
        private var wrongAnswerCount = 0
        private var expectedUnknownCount = 0
        private var resolvedUnknownCount = 0

        private val actualStatusCounts = linkedMapOf<String, Int>()
        private val operationDurationNanos = linkedMapOf<String, Long>()
        private val mismatches = arrayListOf<TvmCvc5ReplayCheckMismatch>()

        inline fun <T> measureNonCheck(
            operation: String,
            block: () -> T,
        ): T {
            val startedAt = System.nanoTime()
            return try {
                block()
            } finally {
                val duration = elapsedSince(startedAt)
                recordOperationDuration(operation, duration)
                nonCheckDurationNanos += duration
            }
        }

        fun recordOperationDuration(
            operation: String,
            durationNanos: Long,
        ) {
            lastOperationDurationNanos = durationNanos
            solverOperationDurationNanos += durationNanos
            operationDurationNanos.merge(operation, durationNanos, Long::plus)
        }

        fun recordCheck(
            checkId: Long,
            expected: KSolverStatus,
            actual: KSolverStatus,
            timeoutNanos: Long?,
            durationNanos: Long,
        ) {
            checkCount++
            lastCheckStatus = actual
            actualStatusCounts.merge(actual.name, 1, Int::plus)

            if (expected == KSolverStatus.UNKNOWN) {
                expectedUnknownCount++
                unknownCheckDurationNanos += durationNanos
                if (actual != KSolverStatus.UNKNOWN) resolvedUnknownCount++
                return
            }

            knownCheckDurationNanos += durationNanos
            when (actual) {
                expected -> knownStatusMatchCount++
                KSolverStatus.UNKNOWN -> {
                    knownStatusTimeoutCount++
                    knownStatusTimeoutDurationNanos += durationNanos
                    knownStatusTimeoutBudgetNanos += timeoutNanos ?: durationNanos
                    recordMismatch(checkId, expected, actual, timeoutNanos)
                }

                else -> {
                    wrongAnswerCount++
                    recordMismatch(checkId, expected, actual, timeoutNanos)
                }
            }
        }

        fun progress(
            phase: String,
            processedEventCount: Int,
            currentEventType: String? = null,
            currentCheckId: Long? = null,
        ): TvmCvc5ReplayProgress {
            this.processedEventCount = processedEventCount
            return TvmCvc5ReplayProgress(
                phase = phase,
                processedEventCount = processedEventCount,
                eventCount = eventCount,
                executedEventCount = executedEventCount,
                checkCount = checkCount,
                currentEventType = currentEventType,
                currentCheckId = currentCheckId,
            )
        }

        private fun recordMismatch(
            checkId: Long,
            expected: KSolverStatus,
            actual: KSolverStatus,
            timeoutNanos: Long?,
        ) {
            if (mismatches.size < MAX_RECORDED_MISMATCHES) {
                mismatches +=
                    TvmCvc5ReplayCheckMismatch(
                        checkId = checkId,
                        expectedStatus = expected.name,
                        actualStatus = actual.name,
                        timeoutNanos = timeoutNanos,
                    )
            }
        }

        fun result(
            candidate: TvmCvc5ReplayCandidate,
            header: ProfileHeader,
            repetition: Int,
            outcome: String,
            wallDurationNanos: Long,
            failure: Exception? = null,
        ): TvmCvc5ProfileReplayResult =
            TvmCvc5ProfileReplayResult(
                candidateId = candidate.candidateId,
                parameterSpaceId = candidate.parameterSpaceId,
                profileId = header.profileId,
                runId = header.runId,
                solverVersion = header.solverVersion,
                backendRole = header.backendRole,
                repetition = repetition,
                outcome = outcome,
                wallDurationNanos = wallDurationNanos,
                decodeDurationNanos = decodeDurationNanos,
                solverOperationDurationNanos = solverOperationDurationNanos,
                nonCheckDurationNanos = nonCheckDurationNanos,
                knownCheckDurationNanos = knownCheckDurationNanos,
                unknownCheckDurationNanos = unknownCheckDurationNanos,
                knownStatusTimeoutDurationNanos = knownStatusTimeoutDurationNanos,
                knownStatusTimeoutBudgetNanos = knownStatusTimeoutBudgetNanos,
                eventCount = eventCount,
                executedEventCount = executedEventCount,
                recordedFailureEventCount = recordedFailureEventCount,
                skippedResultAccessCount = skippedResultAccessCount,
                checkCount = checkCount,
                knownStatusMatchCount = knownStatusMatchCount,
                knownStatusTimeoutCount = knownStatusTimeoutCount,
                wrongAnswerCount = wrongAnswerCount,
                expectedUnknownCount = expectedUnknownCount,
                resolvedUnknownCount = resolvedUnknownCount,
                actualStatusCounts = actualStatusCounts,
                operationDurationNanos = operationDurationNanos,
                mismatches = mismatches,
                failureType = failure?.javaClass?.name,
                failureMessage = failure?.message?.take(MAX_FAILURE_MESSAGE_LENGTH),
            )
    }

    companion object {
        private const val MAX_RECORDED_MISMATCHES = 20
        private const val MAX_FAILURE_MESSAGE_LENGTH = 1_000
        private val KSMT_CONFIGURATION_KEYS =
            setOf(
                "ksmt.optimize.theories",
                "ksmt.quantifiers-allowed",
            )

        private val json = Json { ignoreUnknownKeys = false }

        private fun elapsedSince(startedAt: Long): Long = (System.nanoTime() - startedAt).coerceAtLeast(0)
    }
}

private fun JsonObject.requiredObject(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray = getValue(name).jsonArray

private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.optionalString(name: String): String? =
    get(name)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

private fun JsonObject.requiredBoolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

private fun JsonObject.requiredLong(name: String): Long = getValue(name).jsonPrimitive.long

private fun JsonObject.optionalLong(name: String): Long? =
    get(name)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.long
