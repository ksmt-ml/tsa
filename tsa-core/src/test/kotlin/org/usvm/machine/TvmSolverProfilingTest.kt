package org.usvm.machine

import io.ksmt.KContext
import io.ksmt.profiling.KSolverAssertEvent
import io.ksmt.profiling.KSolverCallMetadata
import io.ksmt.profiling.KSolverCallOutcome
import io.ksmt.profiling.KSolverConfigurationBase
import io.ksmt.profiling.KSolverConfigurationSnapshot
import io.ksmt.profiling.KSolverDescriptor
import io.ksmt.profiling.KSolverProfile
import io.ksmt.profiling.KSolverProfileState
import io.ksmt.solver.KSolverStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class TvmSolverProfilingTest {
    @Test
    fun `TSA solver pipeline uses cvc5 and writes its profile`() {
        val outputDirectory = Files.createTempDirectory("tsa-cvc5-profile-test")
        val options =
            TvmOptions(
                solverProfileOptions = TvmSolverProfileOptions(outputDirectory),
            )
        val components = TvmComponents(options)
        val ctx = TvmContext(options, components)

        try {
            try {
                components.mkKsmtSolver(ctx).let { solver ->
                    solver.push()
                    solver.assert(ctx.mkTrue())

                    assertEquals(KSolverStatus.SAT, solver.check(1.seconds))
                    solver.pop()
                }
            } finally {
                try {
                    components.close()
                } finally {
                    ctx.close()
                }
            }

            val profileFiles = Files.list(outputDirectory).use { files -> files.iterator().asSequence().toList() }
            assertEquals(3, profileFiles.size)

            val profiles = profileFiles.map { Json.parseToJsonElement(it.readText()).jsonObject }
            val application = profiles.single { it.attribute("layer") == "application" }
            val backends = profiles.filter { it.attribute("layer") == "backend" }

            assertEquals(setOf("integer-blasted", "regular-bv"), backends.map { it.attribute("backend-role") }.toSet())
            assertTrue(application.getValue("events").jsonArray.isNotEmpty())
            assertTrue(backends.single { it.attribute("backend-role") == "regular-bv" }.checkEvents().isNotEmpty())
            assertEquals("CLOSED", application.getValue("state").jsonPrimitive.content)
            assertTrue(profiles.all { it.getValue("runId") == application.getValue("runId") })
            assertTrue(backends.all { it.getValue("parentProfileId") == application.getValue("profileId") })

            val check = application.checkEvents().single()
            assertEquals(
                1,
                check
                    .getValue("query")
                    .jsonObject
                    .getValue("assertionCount")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertTrue(
                check
                    .getValue("query")
                    .jsonObject
                    .getValue("treeNodeCount")
                    .jsonPrimitive.content
                    .toLong() > 0,
            )
            assertEquals(
                "ksmt-runner-ast-stream-v0",
                application
                    .getValue("producer")
                    .jsonObject
                    .getValue("replayEncoding")
                    .jsonPrimitive.content,
            )
            assertTrue(
                application
                    .getValue("producer")
                    .jsonObject
                    .getValue("replayable")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertTrue(
                application
                    .getValue("producer")
                    .jsonObject
                    .getValue("captureComplete")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
        } finally {
            outputDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writes a v0 profile as a completed JSON artifact`() {
        val outputDirectory = Files.createTempDirectory("tsa-solver-profiles-test")
        try {
            KContext().use { ctx ->
                val metadata =
                    KSolverCallMetadata(
                        sequenceNumber = 0,
                        startedAfterSession = 10.nanoseconds,
                        duration = 20.nanoseconds,
                        outcome = KSolverCallOutcome.SUCCESS,
                        task = null,
                        stackDepth = 0,
                        activeAssertionCount = 1,
                        totalAssertionCount = 1,
                    )
                val profile =
                    KSolverProfile(
                        schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
                        profileId = "profile-0",
                        runId = "run-0",
                        state = KSolverProfileState.ACTIVE,
                        solver = KSolverDescriptor("cvc5", version = "test"),
                        startedAt = Instant.ofEpochMilli(100),
                        observedDuration = 30.nanoseconds,
                        initialConfiguration =
                            KSolverConfigurationSnapshot(KSolverConfigurationBase.SOLVER_DEFAULTS),
                        events = listOf(KSolverAssertEvent(ctx.mkTrue(), tracked = false, metadata)),
                        eventLimit = 100,
                        droppedEventCount = 0,
                    )

                val path = TvmSolverProfileWriter(outputDirectory).write(profile)
                val json = Json.parseToJsonElement(path.readText()).jsonObject

                assertEquals(
                    KSolverProfile.CURRENT_SCHEMA_VERSION,
                    json.getValue("schemaVersion").jsonPrimitive.content,
                )
                assertEquals(
                    TvmSolverProfileWriter.FORMAT_VERSION,
                    json
                        .getValue("producer")
                        .jsonObject
                        .getValue("format")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "assert",
                    json
                        .getValue("events")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive.content,
                )
                assertTrue(
                    json
                        .getValue("events")
                        .jsonArray
                        .single()
                        .jsonObject
                        .containsKey("expressionAst"),
                )
                assertEquals(
                    "false",
                    json
                        .getValue("producer")
                        .jsonObject
                        .getValue("captureComplete")
                        .jsonPrimitive.content,
                )
                val completedPath =
                    TvmSolverProfileWriter(
                        outputDirectory,
                    ).write(profile.copy(state = KSolverProfileState.CLOSED))
                assertEquals(path, completedPath)
                assertEquals(
                    "CLOSED",
                    Json
                        .parseToJsonElement(
                            completedPath.readText(),
                        ).jsonObject
                        .getValue("state")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "true",
                    Json
                        .parseToJsonElement(completedPath.readText())
                        .jsonObject
                        .getValue("producer")
                        .jsonObject
                        .getValue("captureComplete")
                        .jsonPrimitive.content,
                )
                assertTrue(path.fileName.toString().endsWith(".json"))
            }
        } finally {
            outputDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replay AST stream round trips in a fresh context`() {
        KContext().use { sourceContext ->
            val encoder = TvmSolverProfileAstEncoder()
            val first = encoder.encode(sourceContext.mkTrue())
            val second = encoder.encode(sourceContext.mkNot(sourceContext.mkFalse()))

            KContext().use { targetContext ->
                val decoder = TvmSolverProfileAstDecoder(targetContext)
                assertEquals(sourceContext.mkTrue().toString(), decoder.decode(first).toString())
                assertEquals(sourceContext.mkNot(sourceContext.mkFalse()).toString(), decoder.decode(second).toString())
            }
        }
    }

    @Test
    fun `application profile normalizes TSA arithmetic before replay encoding`() {
        val outputDirectory = Files.createTempDirectory("tsa-custom-expression-profile-test")
        val options = TvmOptions()
        val components = TvmComponents(options)
        val ctx = TvmContext(options, components)

        try {
            val symbolicValue = ctx.mkFreshConst("profile-value", ctx.int257sort)
            val multiplication = ctx.mkTvmMulNoSimplify(symbolicValue, ctx.twoValue)
            val expression = ctx.mkEq(multiplication, ctx.zeroValue)
            val profile =
                KSolverProfile(
                    schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
                    profileId = "custom-expression-profile",
                    runId = "custom-expression-run",
                    state = KSolverProfileState.CLOSED,
                    solver = KSolverDescriptor("tsa-bv2int"),
                    startedAt = Instant.ofEpochMilli(100),
                    observedDuration = 30.nanoseconds,
                    initialConfiguration = KSolverConfigurationSnapshot(KSolverConfigurationBase.SOLVER_DEFAULTS),
                    events = listOf(KSolverAssertEvent(expression, tracked = false, successfulCallMetadata())),
                    eventLimit = 100,
                    droppedEventCount = 0,
                    attributes =
                        mapOf(
                            TvmSolverProfileWriter.EXPRESSION_NORMALIZATION_ATTRIBUTE to
                                TvmSolverProfileWriter.TSA_BV_ARITHMETIC_NORMALIZATION,
                        ),
                )

            val json =
                Json.parseToJsonElement(TvmSolverProfileWriter(outputDirectory).write(profile).readText()).jsonObject

            assertEquals(
                "true",
                json
                    .getValue("producer")
                    .jsonObject
                    .getValue("replayable")
                    .jsonPrimitive.content,
            )
            assertTrue(
                json
                    .getValue("events")
                    .jsonArray
                    .single()
                    .jsonObject
                    .containsKey("expressionAst"),
            )
        } finally {
            try {
                components.close()
            } finally {
                ctx.close()
                outputDirectory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `unsupported replay encoding degrades to a timing profile`() {
        val outputDirectory = Files.createTempDirectory("tsa-profile-replay-fallback-test")
        try {
            KContext().use { ctx ->
                val profile =
                    KSolverProfile(
                        schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
                        profileId = "fallback-profile",
                        runId = "fallback-run",
                        state = KSolverProfileState.CLOSED,
                        solver = KSolverDescriptor("test"),
                        startedAt = Instant.ofEpochMilli(100),
                        observedDuration = 30.nanoseconds,
                        initialConfiguration = KSolverConfigurationSnapshot(KSolverConfigurationBase.SOLVER_DEFAULTS),
                        events = listOf(KSolverAssertEvent(ctx.mkTrue(), tracked = false, successfulCallMetadata())),
                        eventLimit = 100,
                        droppedEventCount = 0,
                        attributes =
                            mapOf(TvmSolverProfileWriter.EXPRESSION_NORMALIZATION_ATTRIBUTE to "unsupported-v0"),
                    )

                val json =
                    Json
                        .parseToJsonElement(
                            TvmSolverProfileWriter(outputDirectory).write(profile).readText(),
                        ).jsonObject

                assertEquals(
                    "false",
                    json
                        .getValue("producer")
                        .jsonObject
                        .getValue("replayable")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "true",
                    json
                        .getValue("producer")
                        .jsonObject
                        .getValue("captureComplete")
                        .jsonPrimitive.content,
                )
                assertTrue(
                    json
                        .getValue(
                            "producer",
                        ).jsonObject
                        .getValue("replayFailureType")
                        .jsonPrimitive.content
                        .isNotBlank(),
                )
                assertTrue(
                    !json
                        .getValue("events")
                        .jsonArray
                        .single()
                        .jsonObject
                        .containsKey("expressionAst"),
                )
            }
        } finally {
            outputDirectory.toFile().deleteRecursively()
        }
    }

    private fun successfulCallMetadata(): KSolverCallMetadata =
        KSolverCallMetadata(
            sequenceNumber = 0,
            startedAfterSession = 10.nanoseconds,
            duration = 20.nanoseconds,
            outcome = KSolverCallOutcome.SUCCESS,
            task = null,
            stackDepth = 0,
            activeAssertionCount = 1,
            totalAssertionCount = 1,
        )

    private fun JsonObject.attribute(name: String): String =
        getValue("attributes")
            .jsonObject
            .getValue(name)
            .jsonPrimitive.content

    private fun JsonObject.checkEvents(): List<JsonObject> =
        getValue("events")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("type").jsonPrimitive.content == "check" }
}
