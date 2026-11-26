package org.usvm.machine

import io.ksmt.profiling.KRecordingSolverProfiler
import io.ksmt.profiling.KSolverConfigurationBase
import io.ksmt.profiling.KSolverConfigurationSnapshot
import io.ksmt.profiling.KSolverConfigurationValue
import io.ksmt.profiling.KSolverDescriptor
import io.ksmt.profiling.KSolverProfiler
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KTheory
import io.ksmt.solver.cvc5.KCvc5Solver
import org.usvm.UBoolExpr
import org.usvm.UContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

class TvmCvc5SolverFactory(
    private val profileOptions: TvmSolverProfileOptions? = null,
    private val configuration: TvmCvc5Configuration? = null,
) : TvmSolverFactory {
    override fun create(
        ctx: UContext<TvmSizeSort>,
        options: TvmOptions,
    ): KSolver<KSolverConfiguration> = Cvc5SolverSession(options, profileOptions, configuration).create(ctx)

    companion object {
        const val CVC5_PARAMETER_SPACE_ATTRIBUTE = "cvc5.parameter-space-id"
    }
}

private class Cvc5SolverSession(
    private val options: TvmOptions,
    private val profileOptions: TvmSolverProfileOptions?,
    private val configuration: TvmCvc5Configuration?,
) {
    private val solverProfilers = mutableListOf<KSolverProfiler<*>>()
    private val profileWriter = profileOptions?.let { TvmSolverProfileWriter(it.outputDirectory) }

    fun create(ctx: UContext<TvmSizeSort>): KSolver<KSolverConfiguration> {
        val pipelineInstanceId = UUID.randomUUID().toString()
        val applicationProfileId = UUID.randomUUID().toString()

        validateNativeConfiguration()

        val bvConfiguration =
            configuration?.takeIf {
                it.backendRole == TvmCvc5Configuration.REGULAR_BV_BACKEND
            }
        val intConfiguration =
            configuration?.takeIf {
                it.backendRole == TvmCvc5Configuration.INTEGER_BLASTED_BACKEND
            }

        val rawBvSolver =
            KCvc5Solver(ctx).apply {
                configure {
                    optimizeForTheories(setOf(KTheory.UF, KTheory.Array, KTheory.BV))
                    bvConfiguration?.options?.toSortedMap()?.forEach { (option, value) ->
                        setCvc5Option(option, value)
                    }
                }
            }
        val rawIntSolver =
            KCvc5Solver(ctx).apply {
                configure {
                    optimizeForTheories(setOf(KTheory.UF, KTheory.Array, KTheory.LIA, KTheory.NIA))
                    intConfiguration?.options?.toSortedMap()?.forEach { (option, value) ->
                        setCvc5Option(option, value)
                    }
                }
            }

        val bvSolver =
            profileOptions?.let {
                profileBackend(
                    delegate = rawBvSolver,
                    profileOptions = it,
                    profileId = UUID.randomUUID().toString(),
                    parentProfileId = applicationProfileId,
                    pipelineInstanceId = pipelineInstanceId,
                    backendRole = TvmCvc5Configuration.REGULAR_BV_BACKEND,
                    theories = "UF,Array,BV",
                    cvc5Options = bvConfiguration?.options.orEmpty(),
                )
            } ?: rawBvSolver
        val intSolver =
            profileOptions?.let {
                profileBackend(
                    delegate = rawIntSolver,
                    profileOptions = it,
                    profileId = UUID.randomUUID().toString(),
                    parentProfileId = applicationProfileId,
                    pipelineInstanceId = pipelineInstanceId,
                    backendRole = TvmCvc5Configuration.INTEGER_BLASTED_BACKEND,
                    theories = "UF,Array,LIA,NIA",
                    cvc5Options = intConfiguration?.options.orEmpty(),
                )
            } ?: rawIntSolver

        val loggingSolver =
            TvmComponents.createTvmSolverPipeline(ctx, options, bvSolver, intSolver, retryIntSolverOnUnknown = false)

        val wrappedSolver =
            profileOptions?.let {
                KRecordingSolverProfiler(
                    delegate = loggingSolver,
                    solver =
                        KSolverDescriptor(
                            name = "tsa-bv2int",
                            attributes =
                                mapOf(
                                    "pipeline" to "tsa-bv2int",
                                    "regular-backend" to "cvc5",
                                    "integer-backend" to "cvc5",
                                ),
                        ),
                    profileId = applicationProfileId,
                    runId = it.runId,
                    initialConfiguration = applicationConfiguration(),
                    profileAttributes =
                        profileAttributes(
                            profileOptions = it,
                            pipelineInstanceId = pipelineInstanceId,
                            layer = "application",
                        ) +
                            mapOf(
                                "int-blasting-enabled" to options.useIntBlasting.toString(),
                                TvmSolverProfileWriter.EXPRESSION_NORMALIZATION_ATTRIBUTE to
                                    TvmSolverProfileWriter.TSA_BV_ARITHMETIC_NORMALIZATION,
                            ),
                    maxRecordedEvents = it.maxRecordedEvents,
                ).also(solverProfilers::add)
            } ?: loggingSolver

        return profileOptions?.let {
            CheckpointingSolver(
                delegate = wrappedSolver,
                checkpointIntervalChecks = it.checkpointIntervalChecks,
                checkpoint = ::writeProfileSnapshots,
            )
        } ?: wrappedSolver
    }

    private fun <Config : KSolverConfiguration> profileBackend(
        delegate: KSolver<Config>,
        profileOptions: TvmSolverProfileOptions,
        profileId: String,
        parentProfileId: String,
        pipelineInstanceId: String,
        backendRole: String,
        theories: String,
        cvc5Options: Map<String, String>,
    ): KRecordingSolverProfiler<Config> =
        KRecordingSolverProfiler(
            delegate = delegate,
            solver =
                KSolverDescriptor(
                    name = "cvc5",
                    version = TvmCvc5OptionInventoryReader.current.solverVersion,
                    attributes = mapOf("backend-role" to backendRole),
                ),
            profileId = profileId,
            runId = profileOptions.runId,
            parentProfileId = parentProfileId,
            initialConfiguration = cvc5Configuration(theories, cvc5Options),
            profileAttributes =
                profileAttributes(
                    profileOptions = profileOptions,
                    pipelineInstanceId = pipelineInstanceId,
                    layer = "backend",
                ) +
                    mapOf(
                        "backend-role" to backendRole,
                        TvmSolverProfileWriter.EXPRESSION_NORMALIZATION_ATTRIBUTE to
                            TvmSolverProfileWriter.IDENTITY_NORMALIZATION,
                    ) +
                    cvc5ConfigurationAttributes(backendRole),
            maxRecordedEvents = profileOptions.maxRecordedEvents,
        ).also(solverProfilers::add)

    /** Provenance of the applied configuration, so a captured workload stays explainable. */
    private fun cvc5ConfigurationAttributes(backendRole: String): Map<String, String> =
        configuration
            ?.takeIf { it.backendRole == backendRole }
            ?.let { mapOf(TvmCvc5SolverFactory.CVC5_PARAMETER_SPACE_ATTRIBUTE to it.parameterSpaceId) }
            .orEmpty()

    private fun validateNativeConfiguration() {
        val applied = configuration ?: return
        val inventory = TvmCvc5OptionInventoryReader.current
        require(applied.solverVersion == null || applied.solverVersion == inventory.solverVersion) {
            "The approved configuration is incompatible with cvc5 ${inventory.solverVersion}"
        }
        val unknown = TvmCvc5Configuration.unknownOptionNames(applied.options, inventory)
        require(unknown.isEmpty()) { "The loaded cvc5 does not support these options: $unknown" }
        val names = applied.options.keys
        val noncanonical = inventory.options.filter { entry -> entry.aliases.any(names::contains) }
        require(noncanonical.isEmpty()) { "Use canonical cvc5 option names instead of aliases" }
    }

    private fun applicationConfiguration(): KSolverConfigurationSnapshot =
        KSolverConfigurationSnapshot(
            base = KSolverConfigurationBase.SOLVER_DEFAULTS,
            overrides =
                mapOf(
                    "tsa.pipeline" to KSolverConfigurationValue.StringValue("bv2int"),
                    "tsa.int-blasting-enabled" to KSolverConfigurationValue.BoolValue(options.useIntBlasting),
                ),
        )

    /**
     * The initial configuration recorded in a backend profile.
     *
     * Applied cvc5 options are part of it, because a replay reconstructs the workload from this
     * snapshot: omitting them would make the replay measure a different workload than the capture.
     */
    private fun cvc5Configuration(
        theories: String,
        cvc5Options: Map<String, String>,
    ): KSolverConfigurationSnapshot =
        KSolverConfigurationSnapshot(
            base = KSolverConfigurationBase.SOLVER_DEFAULTS,
            overrides =
                mapOf(
                    TvmSolverProfileWriter.OPTIMIZE_THEORIES_CONFIGURATION_KEY to
                        KSolverConfigurationValue.StringValue(theories),
                    TvmSolverProfileWriter.QUANTIFIERS_ALLOWED_CONFIGURATION_KEY to
                        KSolverConfigurationValue.BoolValue(false),
                ) +
                    cvc5Options.mapValues { (_, value) -> KSolverConfigurationValue.StringValue(value) },
        )

    private fun profileAttributes(
        profileOptions: TvmSolverProfileOptions,
        pipelineInstanceId: String,
        layer: String,
    ): Map<String, String> =
        profileOptions.attributes +
            buildMap {
                put("producer", "tsa")
                put("layer", layer)
                put("pipeline-instance-id", pipelineInstanceId)
                profileOptions.workloadId?.let { put("workload-id", it) }
                put("environment.java-version", System.getProperty("java.version"))
                put("environment.os-name", System.getProperty("os.name"))
                put("environment.os-arch", System.getProperty("os.arch"))
                put("environment.available-processors", Runtime.getRuntime().availableProcessors().toString())
            }

    private fun writeProfileSnapshots(terminal: Boolean) {
        val writer = profileWriter ?: return
        val onTerminalProfile = profileOptions?.onTerminalProfile
        solverProfilers.forEach { profiler ->
            val snapshot = profiler.snapshot()
            writer.write(snapshot)
            if (terminal) {
                // Profiling must never turn a successful analysis into a failed one.
                runCatching { onTerminalProfile?.invoke(snapshot) }
            }
        }
    }

    private class CheckpointingSolver<Config : KSolverConfiguration>(
        private val delegate: KSolver<Config>,
        private val checkpointIntervalChecks: Int,
        private val checkpoint: (terminal: Boolean) -> Unit,
    ) : KSolver<Config> by delegate {
        private val completedChecks = AtomicLong()
        private var closed = false

        override fun check(timeout: Duration): KSolverStatus =
            try {
                delegate.check(timeout)
            } finally {
                checkpointIfRequired()
            }

        override fun checkWithAssumptions(
            assumptions: List<UBoolExpr>,
            timeout: Duration,
        ): KSolverStatus =
            try {
                delegate.checkWithAssumptions(assumptions, timeout)
            } finally {
                checkpointIfRequired()
            }

        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
            } finally {
                checkpoint(true)
            }
        }

        private fun checkpointIfRequired() {
            if (completedChecks.incrementAndGet() % checkpointIntervalChecks == 0L) {
                checkpoint(false)
            }
        }
    }
}
