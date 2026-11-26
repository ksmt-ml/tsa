package org.usvm.machine

import io.ksmt.KContext
import io.ksmt.profiling.KSolverAssertEvent
import io.ksmt.profiling.KSolverCallMetadata
import io.ksmt.profiling.KSolverCallOutcome
import io.ksmt.profiling.KSolverCheckEvent
import io.ksmt.profiling.KSolverConfigurationBase
import io.ksmt.profiling.KSolverConfigurationSnapshot
import io.ksmt.profiling.KSolverConfigurationValue
import io.ksmt.profiling.KSolverDescriptor
import io.ksmt.profiling.KSolverLifecycleEvent
import io.ksmt.profiling.KSolverLifecycleEventKind
import io.ksmt.profiling.KSolverProfile
import io.ksmt.profiling.KSolverProfileState
import io.ksmt.profiling.KSolverResultAccessEvent
import io.ksmt.profiling.KSolverResultAccessKind
import io.ksmt.solver.KSolverStatus
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

class TvmCvc5ProfileReplayTest {
    @Test
    fun `default candidate replays a complete cvc5 profile`() {
        val directory = Files.createTempDirectory("tsa-profile-replay-test")
        try {
            val path =
                KContext().use { context ->
                    TvmSolverProfileWriter(directory).write(
                        profile(
                            assertion = KSolverAssertEvent(context.mkTrue(), tracked = false, metadata(0)),
                            expectedStatus = KSolverStatus.SAT,
                        ),
                    )
                }
            val evaluator = TvmCvc5ProfileReplayEvaluator()

            val result = evaluator.evaluate(evaluator.parseProfile(path), defaultCandidate(), repetition = 0)

            assertEquals("SUCCESS", result.outcome)
            assertEquals(1, result.checkCount)
            assertEquals(1, result.knownStatusMatchCount)
            assertEquals(0, result.knownStatusTimeoutCount)
            assertEquals(0, result.wrongAnswerCount)
            assertEquals(0, result.skippedResultAccessCount)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `known SAT UNSAT disagreement is a wrong answer`() {
        val directory = Files.createTempDirectory("tsa-profile-replay-mismatch-test")
        try {
            val path =
                KContext().use { context ->
                    TvmSolverProfileWriter(directory).write(
                        profile(
                            assertion = KSolverAssertEvent(context.mkTrue(), tracked = false, metadata(0)),
                            expectedStatus = KSolverStatus.UNSAT,
                        ),
                    )
                }
            val evaluator = TvmCvc5ProfileReplayEvaluator()

            val result = evaluator.evaluate(evaluator.parseProfile(path), defaultCandidate(), repetition = 0)

            assertEquals("SUCCESS", result.outcome)
            assertEquals(0, result.knownStatusMatchCount)
            assertEquals(1, result.wrongAnswerCount)
            assertEquals(1, result.skippedResultAccessCount)
            assertEquals(KSolverStatus.UNSAT.name, result.mismatches.single().expectedStatus)
            assertEquals(KSolverStatus.SAT.name, result.mismatches.single().actualStatus)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replay reports live progress and actual cvc5 version`() {
        val directory = Files.createTempDirectory("tsa-profile-replay-progress-test")
        try {
            val path =
                KContext().use { context ->
                    TvmSolverProfileWriter(directory).write(
                        profile(
                            assertion = KSolverAssertEvent(context.mkTrue(), tracked = false, metadata(0)),
                            expectedStatus = KSolverStatus.SAT,
                        ),
                    )
                }
            val evaluator = TvmCvc5ProfileReplayEvaluator()
            val progress = arrayListOf<TvmCvc5ReplayProgress>()

            val result =
                evaluator.evaluate(
                    evaluator.parseProfile(path),
                    defaultCandidate(),
                    repetition = 0,
                    onProgress = progress::add,
                )

            assertEquals("1.3.4", result.solverVersion)
            assertEquals("STARTING", progress.first().phase)
            assertEquals("COMPLETED", progress.last().phase)
            assertTrue(progress.any { it.currentEventType == "check" && it.currentCheckId == 0L })
            assertEquals(result.eventCount, progress.last().processedEventCount)
            assertEquals(result.checkCount, progress.last().checkCount)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun profile(
        assertion: KSolverAssertEvent,
        expectedStatus: KSolverStatus,
    ): KSolverProfile =
        KSolverProfile(
            schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
            profileId = "replay-profile",
            runId = "replay-run",
            state = KSolverProfileState.CLOSED,
            solver = KSolverDescriptor("cvc5", version = "1.3.4", attributes = mapOf("backend-role" to "regular-bv")),
            startedAt = Instant.ofEpochMilli(1),
            observedDuration = 1_000.nanoseconds,
            initialConfiguration =
                KSolverConfigurationSnapshot(
                    base = KSolverConfigurationBase.SOLVER_DEFAULTS,
                    overrides =
                        mapOf(
                            "ksmt.optimize.theories" to KSolverConfigurationValue.StringValue("UF,Array,BV"),
                            "ksmt.quantifiers-allowed" to KSolverConfigurationValue.BoolValue(false),
                        ),
                ),
            events =
                listOf(
                    assertion,
                    KSolverCheckEvent(
                        checkId = 0,
                        timeout = 1_000_000_000.nanoseconds,
                        status = expectedStatus,
                        metadata = metadata(1),
                    ),
                    KSolverResultAccessEvent(
                        kind =
                            if (expectedStatus == KSolverStatus.SAT) {
                                KSolverResultAccessKind.MODEL
                            } else {
                                KSolverResultAccessKind.UNSAT_CORE
                            },
                        checkId = 0,
                        metadata = metadata(2),
                    ),
                    KSolverLifecycleEvent(KSolverLifecycleEventKind.CLOSE, metadata(3)),
                ),
            eventLimit = 100,
            droppedEventCount = 0,
            attributes =
                mapOf(
                    "backend-role" to "regular-bv",
                    TvmSolverProfileWriter.EXPRESSION_NORMALIZATION_ATTRIBUTE to
                        TvmSolverProfileWriter.IDENTITY_NORMALIZATION,
                ),
        )

    private fun metadata(sequenceNumber: Long): KSolverCallMetadata =
        KSolverCallMetadata(
            sequenceNumber = sequenceNumber,
            startedAfterSession = (sequenceNumber * 10).nanoseconds,
            duration = 10.nanoseconds,
            outcome = KSolverCallOutcome.SUCCESS,
            task = null,
            stackDepth = 0,
            activeAssertionCount = 1,
            totalAssertionCount = 1,
        )

    private fun defaultCandidate(): TvmCvc5ReplayCandidate =
        TvmCvc5ReplayCandidate(
            schemaVersion = TvmCvc5ReplayCandidate.SCHEMA_VERSION,
            candidateId = "default-v0",
            parameterSpaceId = "test-space-v0",
            targetSolver = "cvc5",
        )
}
