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
import io.ksmt.solver.wrapper.bv2int.KBv2IntRewriter.SignednessMode
import io.ksmt.solver.wrapper.bv2int.KBv2IntRewriterConfig
import mu.KLogging
import org.usvm.UBoolExpr
import org.usvm.UBv32SizeExprProvider
import org.usvm.UComponents
import org.usvm.UComposer
import org.usvm.UContext
import org.usvm.USizeExprProvider
import org.usvm.collections.immutable.internal.MutabilityOwnership
import org.usvm.machine.TvmContext.Companion.tctx
import org.usvm.machine.intblast.Bv2IntExprFilter
import org.usvm.machine.intblast.Bv2IntSolverWrapper
import org.usvm.machine.intblast.TvmBv2IntSolver
import org.usvm.machine.intblast.TvmBvNonRecursiveTransformer
import org.usvm.machine.intblast.TvmComposer
import org.usvm.machine.intblast.TvmTranslator
import org.usvm.machine.types.TvmType
import org.usvm.machine.types.TvmTypeSystem
import org.usvm.memory.UReadOnlyMemory
import org.usvm.model.ULazyModelDecoder
import org.usvm.solver.UExprTranslator
import org.usvm.solver.USolverBase
import org.usvm.solver.UTypeSolver
import org.usvm.types.UTypeSystem
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

class TvmComponents(
    private val options: TvmOptions,
) : UComponents<TvmType, TvmSizeSort>,
    AutoCloseable {
    private val closeableResources = mutableListOf<AutoCloseable>()
    private val solverProfilers = mutableListOf<KSolverProfiler<*>>()
    private val profileWriter = options.solverProfileOptions?.let { TvmSolverProfileWriter(it.outputDirectory) }
    override val useSolverForForks: Boolean
        get() = true

    override fun <Context : UContext<TvmSizeSort>> mkSizeExprProvider(ctx: Context): USizeExprProvider<TvmSizeSort> =
        UBv32SizeExprProvider(ctx)

    val typeSystem = TvmTypeSystem()

    override fun <Context : UContext<TvmSizeSort>> mkComposer(
        ctx: Context,
    ): (UReadOnlyMemory<TvmType>, MutabilityOwnership) -> UComposer<TvmType, TvmSizeSort> =
        { memory, ownership ->
            TvmComposer(ctx.tctx(), memory, ownership)
        }

    override fun <Context : UContext<TvmSizeSort>> buildTranslatorAndLazyDecoder(
        ctx: Context,
    ): Pair<UExprTranslator<TvmType, TvmSizeSort>, ULazyModelDecoder<TvmType>> {
        val translator = TvmTranslator(ctx.tctx())
        val decoder = ULazyModelDecoder(translator)
        return translator to decoder
    }

    override fun <Context : UContext<TvmSizeSort>> mkSolver(ctx: Context): USolverBase<TvmType> {
        val (translator, decoder) = buildTranslatorAndLazyDecoder(ctx)
        val wrappedSolver = mkKsmtSolver(ctx)

        val typeSolver = UTypeSolver(typeSystem)
        return USolverBase(ctx, wrappedSolver, typeSolver, translator, decoder, options.solverTimeout)
    }

    internal fun <Context : UContext<TvmSizeSort>> mkKsmtSolver(ctx: Context): KSolver<KSolverConfiguration> {
        val profileOptions = options.solverProfileOptions
        val pipelineInstanceId = UUID.randomUUID().toString()
        val applicationProfileId = UUID.randomUUID().toString()

        val rawBvSolver =
            KCvc5Solver(ctx).apply {
                configure {
                    optimizeForTheories(setOf(KTheory.UF, KTheory.Array, KTheory.BV))
                }
            }
        val rawIntSolver =
            KCvc5Solver(ctx).apply {
                configure {
                    optimizeForTheories(setOf(KTheory.UF, KTheory.Array, KTheory.LIA, KTheory.NIA))
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
                    backendRole = "regular-bv",
                    theories = "UF,Array,BV",
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
                    backendRole = "integer-blasted",
                    theories = "UF,Array,LIA,NIA",
                )
            } ?: rawIntSolver

        val solver =
            Bv2IntSolverWrapper(
                options = options,
                bv2intSolver =
                    TvmBv2IntSolver(
                        ctx,
                        intSolver,
                        KBv2IntRewriterConfig(signednessMode = SignednessMode.SIGNED),
                    ),
                regularSolver = bvSolver,
                exprFilter =
                    Bv2IntExprFilter(
                        ctx,
                        excludeNonConstBvand = true,
                        excludeNonConstShift = true,
                        excludeNonlinearArith = false,
                    ),
                transformer = TvmBvNonRecursiveTransformer(ctx.tctx()),
            )

        val loggingSolver: KSolver<KSolverConfiguration> =
            if (logger.isDebugEnabled) {
                LoggingSolver(solver)
            } else {
                solver
            }

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

        val checkpointingSolver =
            profileOptions?.let {
                CheckpointingSolver(wrappedSolver, it.checkpointIntervalChecks) {
                    writeProfileSnapshots()
                }
            } ?: wrappedSolver

        closeableResources += checkpointingSolver
        return checkpointingSolver
    }

    private fun <Config : KSolverConfiguration> profileBackend(
        delegate: KSolver<Config>,
        profileOptions: TvmSolverProfileOptions,
        profileId: String,
        parentProfileId: String,
        pipelineInstanceId: String,
        backendRole: String,
        theories: String,
    ): KRecordingSolverProfiler<Config> =
        KRecordingSolverProfiler(
            delegate = delegate,
            solver =
                KSolverDescriptor(
                    name = "cvc5",
                    version = "1.3.4",
                    attributes = mapOf("backend-role" to backendRole),
                ),
            profileId = profileId,
            runId = profileOptions.runId,
            parentProfileId = parentProfileId,
            initialConfiguration = cvc5Configuration(theories),
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
                    ),
            maxRecordedEvents = profileOptions.maxRecordedEvents,
        ).also(solverProfilers::add)

    private fun applicationConfiguration(): KSolverConfigurationSnapshot =
        KSolverConfigurationSnapshot(
            base = KSolverConfigurationBase.SOLVER_DEFAULTS,
            overrides =
                mapOf(
                    "tsa.pipeline" to KSolverConfigurationValue.StringValue("bv2int"),
                    "tsa.int-blasting-enabled" to KSolverConfigurationValue.BoolValue(options.useIntBlasting),
                ),
        )

    private fun cvc5Configuration(theories: String): KSolverConfigurationSnapshot =
        KSolverConfigurationSnapshot(
            base = KSolverConfigurationBase.SOLVER_DEFAULTS,
            overrides =
                mapOf(
                    "ksmt.optimize.theories" to KSolverConfigurationValue.StringValue(theories),
                    "ksmt.quantifiers-allowed" to KSolverConfigurationValue.BoolValue(false),
                ),
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

    private fun writeProfileSnapshots() {
        val writer = profileWriter ?: return
        solverProfilers.forEach { writer.write(it.snapshot()) }
    }

    override fun mkTypeSystem(ctx: UContext<TvmSizeSort>): UTypeSystem<TvmType> = typeSystem

    override fun close() {
        try {
            closeableResources.forEach(AutoCloseable::close)
        } finally {
            writeProfileSnapshots()
        }
    }

    private class CheckpointingSolver<Config : KSolverConfiguration>(
        private val delegate: KSolver<Config>,
        private val checkpointIntervalChecks: Int,
        private val checkpoint: () -> Unit,
    ) : KSolver<Config> by delegate {
        private val completedChecks = AtomicLong()

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

        private fun checkpointIfRequired() {
            if (completedChecks.incrementAndGet() % checkpointIntervalChecks == 0L) {
                checkpoint()
            }
        }
    }

    class LoggingSolver<T : KSolverConfiguration>(
        private val internalSolver: KSolver<T>,
    ) : KSolver<T> by internalSolver {
        override fun check(timeout: Duration): KSolverStatus =
            internalSolver.check(timeout).also { status ->
                logger.debug("Forked with status: {}", status)
            }

        override fun checkWithAssumptions(
            assumptions: List<UBoolExpr>,
            timeout: Duration,
        ): KSolverStatus =
            internalSolver.checkWithAssumptions(assumptions, timeout).also { status ->
                logger.debug("Forked with assumptions with status: {}", status)
            }
    }

    companion object {
        private val logger = object : KLogging() {}.logger
    }
}
