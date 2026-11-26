package org.ton.tuning.impl

import io.ksmt.KContext
import io.ksmt.ml.tuner.configuration.Cvc5ConfigurationPatch
import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpaceImpl
import io.ksmt.ml.tuner.configuration.Cvc5PatchValidation
import io.ksmt.ml.tuner.configuration.Cvc5ValidatedConfigurationPatch
import io.ksmt.ml.tuner.search.Cvc5CancellationToken
import io.ksmt.ml.tuner.search.Cvc5TrialCorrectness
import io.ksmt.ml.tuner.search.Cvc5TrialRequest
import io.ksmt.ml.tuner.search.Cvc5TrialStatus
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
import io.ksmt.solver.KSolverStatus
import org.ton.tuning.TsaCvc5TrialWorkload
import org.ton.tuning.TsaWorkloadReference
import org.usvm.machine.TvmCvc5OptionInventoryReader
import org.usvm.machine.TvmCvc5ReplayCandidate
import org.usvm.machine.TvmSolverProfileWriter
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class TsaCvc5ProfileReplayWorkloadTest {
    @Test
    fun `reports a rankable observation for a matching replay`() {
        withWorkload(KSolverStatus.SAT) { resolver ->
            val workload = resolver.resolve(reference())

            val observation =
                workload.evaluate(request(workload, instanceId = "profile-0"), Cvc5CancellationToken.NONE)

            assertEquals(Cvc5TrialStatus.SUCCESS, observation.status)
            assertEquals(Cvc5TrialCorrectness.CORRECT, observation.correctness)
            assertEquals(false, observation.censored)
            assertEquals("profile-0", observation.artifactReferences["profileId"])

            val cost = observation.cost
            assertNotNull(cost)
            assertTrue(cost >= 0.0)
        }
    }

    @Test
    fun `reports a wrong answer when the replayed status disagrees`() {
        withWorkload(KSolverStatus.UNSAT) { resolver ->
            val workload = resolver.resolve(reference())

            val observation =
                workload.evaluate(request(workload, instanceId = "profile-0"), Cvc5CancellationToken.NONE)

            assertEquals(Cvc5TrialStatus.WRONG_ANSWER, observation.status)
            assertEquals(Cvc5TrialCorrectness.INCORRECT, observation.correctness)
            assertEquals(null, observation.cost)
        }
    }

    @Test
    fun `reports cancellation without creating a solver`() {
        withWorkload(KSolverStatus.SAT) { resolver ->
            val workload = resolver.resolve(reference())

            val observation =
                workload.evaluate(request(workload, instanceId = "profile-0"), Cvc5CancellationToken { true })

            assertEquals(Cvc5TrialStatus.CANCELLED, observation.status)
            assertEquals(null, observation.cost)
        }
    }

    @Test
    fun `reports a censored timeout when the trial budget is exhausted`() {
        withWorkload(KSolverStatus.SAT) { resolver ->
            val workload = resolver.resolve(reference())

            val observation =
                workload.evaluate(
                    request(workload, instanceId = "profile-0", timeout = 1.nanoseconds),
                    Cvc5CancellationToken.NONE,
                )

            assertEquals(Cvc5TrialStatus.TIMEOUT, observation.status)
            assertEquals(true, observation.censored)
            assertEquals(null, observation.cost)
        }
    }

    @Test
    fun `known wrong answers take precedence over an exhausted trial budget`() {
        withWorkload(KSolverStatus.UNSAT) { resolver ->
            val workload = resolver.resolve(reference())
            val request = request(workload, instanceId = "profile-0", timeout = 1.nanoseconds)

            val observation = workload.evaluate(request, Cvc5CancellationToken.NONE)

            assertEquals(Cvc5TrialStatus.WRONG_ANSWER, observation.status)
            assertEquals(Cvc5TrialCorrectness.INCORRECT, observation.correctness)
            assertEquals(null, observation.cost)
        }
    }

    @Test
    fun `rejects a trial targeting a different solver version`() {
        withWorkload(KSolverStatus.SAT) { resolver ->
            val workload = resolver.resolve(reference())
            val request = request(workload, instanceId = "profile-0").copy(solverVersion = "0.0.0")

            val observation = workload.evaluate(request, Cvc5CancellationToken.NONE)

            assertEquals(Cvc5TrialStatus.INVALID_CONFIGURATION, observation.status)
            assertEquals(null, observation.cost)
        }
    }

    @Test
    fun `reports an infrastructure failure for an unknown instance`() {
        withWorkload(KSolverStatus.SAT) { resolver ->
            val workload = resolver.resolve(reference())

            val observation =
                workload.evaluate(request(workload, instanceId = "missing"), Cvc5CancellationToken.NONE)

            assertEquals(Cvc5TrialStatus.INFRASTRUCTURE_FAILURE, observation.status)
            assertEquals(Cvc5TrialCorrectness.NOT_ESTABLISHED, observation.correctness)
            assertEquals(null, observation.cost)
        }
    }

    @Test
    fun `instances expose every replayable backend profile`() {
        withWorkload(KSolverStatus.SAT) { resolver ->
            val workload = resolver.resolve(reference())

            assertEquals(1, workload.instances.size)
            assertEquals("profile-0", workload.instances.single().id)
            assertEquals("regular-bv", workload.instances.single().attributes["backendRole"])
            assertEquals(inventory.solverVersion, workload.solverVersion)
        }
    }

    @Test
    fun `rejects a workload without captured profiles`() {
        val root = Files.createTempDirectory("tsa-tuning-missing-workload-test")
        try {
            val resolver =
                TsaCvc5ProfileReplayWorkloadResolver(profilesRoot = root, parameterSpaceId = PARAMETER_SPACE_ID)

            assertFailsWith<IllegalArgumentException> { resolver.resolve(reference()) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun withWorkload(
        expectedStatus: KSolverStatus,
        block: (TsaCvc5ProfileReplayWorkloadResolver) -> Unit,
    ) {
        val root = Files.createTempDirectory("tsa-tuning-workload-test")
        try {
            val workloadDirectory = root.resolve(WORKLOAD_ID).resolve(REVISION)
            Files.createDirectories(workloadDirectory)
            KContext().use { context ->
                TvmSolverProfileWriter(workloadDirectory).write(profile(context, expectedStatus))
            }

            block(
                TsaCvc5ProfileReplayWorkloadResolver(
                    profilesRoot = root,
                    parameterSpaceId = PARAMETER_SPACE_ID,
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun profile(
        context: KContext,
        expectedStatus: KSolverStatus,
    ): KSolverProfile =
        KSolverProfile(
            schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
            profileId = "profile-0",
            runId = "run-0",
            state = KSolverProfileState.CLOSED,
            solver =
                KSolverDescriptor(
                    name = "cvc5",
                    version = inventory.solverVersion,
                    attributes = mapOf("backend-role" to "regular-bv"),
                ),
            startedAt = Instant.ofEpochMilli(1),
            observedDuration = 1_000.nanoseconds,
            initialConfiguration =
                KSolverConfigurationSnapshot(
                    base = KSolverConfigurationBase.SOLVER_DEFAULTS,
                    overrides =
                        mapOf(
                            "ksmt.optimize.theories" to
                                KSolverConfigurationValue.StringValue("UF,Array,BV"),
                            "ksmt.quantifiers-allowed" to KSolverConfigurationValue.BoolValue(false),
                        ),
                ),
            events =
                listOf(
                    KSolverAssertEvent(context.mkTrue(), tracked = false, metadata(0)),
                    KSolverCheckEvent(
                        checkId = 0,
                        timeout = 1.seconds,
                        status = expectedStatus,
                        metadata = metadata(1),
                    ),
                    KSolverLifecycleEvent(KSolverLifecycleEventKind.CLOSE, metadata(2)),
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

    private fun reference(): TsaWorkloadReference = TsaWorkloadReference(workloadId = WORKLOAD_ID, revision = REVISION)

    private fun request(
        workload: TsaCvc5TrialWorkload,
        instanceId: String,
        timeout: Duration = 10.seconds,
    ): Cvc5TrialRequest =
        Cvc5TrialRequest(
            searchId = "search-0",
            requestId = "request-0",
            proposalIndex = 0,
            solverVersion = workload.solverVersion,
            configuration = validatedPatch(),
            instanceId = instanceId,
            seed = 0,
            repetition = 0,
            timeout = timeout,
        )

    private fun validatedPatch(): Cvc5ValidatedConfigurationPatch {
        val validation =
            parameterSpace.validate(
                Cvc5ConfigurationPatch(
                    parameterSpaceId = PARAMETER_SPACE_ID,
                    options = benignOptions(),
                ),
            )
        return (validation as Cvc5PatchValidation.Accepted).configuration
    }

    /** An integer cvc5 option set to its own default, so the replay stays behavior-neutral. */
    private fun benignOptions(): Map<String, String> {
        for (entry in inventory.options) {
            val defaultValue = entry.defaultValue ?: continue
            if (entry.kind != "integer") continue
            if (entry.name in TvmCvc5ReplayCandidate.PROTECTED_OPTIONS) continue
            return mapOf(entry.name to defaultValue)
        }
        error("No benign integer cvc5 option is available")
    }

    private val parameterSpace = Cvc5ParameterSpaceImpl(parameterSpaceId = PARAMETER_SPACE_ID)

    private val inventory by lazy { TvmCvc5OptionInventoryReader.read() }

    private companion object {
        const val WORKLOAD_ID = "workload-0"
        const val REVISION = "rev-0"
        const val PARAMETER_SPACE_ID = "test-space-v0"
    }
}
