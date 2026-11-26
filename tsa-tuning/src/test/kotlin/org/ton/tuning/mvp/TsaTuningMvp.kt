package org.ton.tuning.mvp

import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpace
import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpaceImpl
import io.ksmt.profiling.KSolverDescriptor
import io.ksmt.profiling.KSolverProfile
import kotlinx.serialization.json.Json
import org.ton.tuning.TsaLaunchCancellation
import org.ton.tuning.TsaSolverProfileLaunchRequest
import org.ton.tuning.TsaSolverProfileLaunchStatus
import org.ton.tuning.TsaSolverProfileSink
import org.ton.tuning.TsaWorkloadReference
import org.ton.tuning.impl.FileSystemTsaAnalysisWorkloadCatalog
import org.ton.tuning.impl.TsaAnalysisWorkload
import org.ton.tuning.impl.TsaCvc5ProfileReplayWorkloadResolver
import org.ton.tuning.impl.TsaSolverProfileLauncherImpl
import org.ton.tuning.impl.TsaWorkloadSourceKind
import org.ton.tuning.impl.TsaWorkloadTargetKind
import org.usvm.machine.TvmCvc5OptionInventoryReader
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end MVP of the TSA cvc5 tuning path.
 *
 * It captures solver profiles from a real BoC contract, resolves them back into a workload, and
 * measures the default cvc5 configuration against a few randomly proposed ones. The point is to
 * show that the workload is capturable, replayable, and actually responds to cvc5 options.
 *
 * Run it with `./gradlew :tsa-tuning:runTuningMvp -Pcontract=<path-to.boc>`.
 */
fun main() {
    val config = MvpConfig.fromSystemProperties()

    println("=== TSA cvc5 tuning MVP ===")
    println("contract       : ${config.contract}")
    println("work dir       : ${config.workDir}")
    println("target         : ${config.targetKind}")
    println("candidates     : ${config.candidates}")
    println("method timeout : ${config.methodTimeoutSeconds}s")
    println("solver timeout : ${config.solverTimeoutSeconds}s")
    println("trial timeout  : ${config.trialTimeoutSeconds}s")
    println("seed           : ${config.seed}")

    val reference = referenceFor(config)
    writeDescriptor(config, reference)

    println()
    println("[1/4] capture solver profiles")
    val captured = mutableListOf<KSolverProfile>()
    val launch =
        TsaSolverProfileLauncherImpl(
            catalog = FileSystemTsaAnalysisWorkloadCatalog(config.workDir),
            profileStoreRoot = config.workDir,
        ).launch(
            request =
                TsaSolverProfileLaunchRequest(
                    runId = "mvp-run",
                    workload = reference,
                    solver = KSolverDescriptor("cvc5"),
                    timeout = Duration.INFINITE,
                ),
            sink = TsaSolverProfileSink { captured += it },
            cancellation = TsaLaunchCancellation.NONE,
        )

    println("  profiles dir   : ${profileDirectory(config, reference)}")
    println("  status         : ${launch.status}")
    println("  captured       : ${launch.capturedProfiles} complete, ${launch.failedProfiles} incomplete")
    println("  duration       : ${formatSeconds(launch.duration)}")

    if (launch.status != TsaSolverProfileLaunchStatus.COMPLETED) {
        println("  failure        : ${launch.failureType}: ${launch.failureMessage}")
        println()
        println("Capture did not complete, so there is nothing to tune.")
        return
    }

    println()
    println("[2/4] resolve the workload")
    val workload =
        TsaCvc5ProfileReplayWorkloadResolver(
            profilesRoot = config.workDir,
            parameterSpaceId = PARAMETER_SPACE_ID,
        ).resolve(reference)

    println("  solver version : ${workload.solverVersion}")
    println("  instances      : ${workload.instances.size}")
    workload.instances.forEach { instance ->
        println("    - ${instance.id} [${instance.attributes["backendRole"]}]")
    }

    println()
    println("[3/4] propose cvc5 configurations")
    val inventory = TvmCvc5OptionInventoryReader.read()
    val factory = MvpCandidateFactory(inventory, DESIRED_OPTIONS, Random(config.seed))
    println("  cvc5 version   : ${inventory.solverVersion}")
    println("  tunable options: ${factory.options.size} of ${DESIRED_OPTIONS.size} requested")
    factory.options.forEach { option ->
        println("    - ${option.name} (${option.kind}, default=${option.defaultValue})")
    }

    println()
    println("[4/4] measure configurations")
    val parameterSpace: Cvc5ParameterSpace = Cvc5ParameterSpaceImpl(parameterSpaceId = PARAMETER_SPACE_ID)
    val search = MvpSearch(workload, parameterSpace, config.trialTimeoutSeconds.seconds, config.seed)

    val measurements = mutableListOf<MvpMeasurement>()

    print("  baseline     : ")
    val baseline = search.measure("baseline", factory.baseline())
    measurements += baseline
    println(format(baseline) + "  [reference]")

    factory.candidates(config.candidates).forEachIndexed { index, options ->
        val label = "candidate-${index + 1}"
        print("  $label : ")
        val measurement = search.measure(label, options)
        measurements += measurement
        val score = search.normalizedScore(baseline, measurement)
        println(format(measurement) + (score?.let { "  score=${"%.4f".format(it)}" } ?: ""))
    }

    report(measurements, baseline, search)
}

private const val PARAMETER_SPACE_ID = "cvc5-1.3.4-regular-bv-wide-v0"

/** cvc5 options that change how bitvector and array reasoning is performed. */
private val DESIRED_OPTIONS =
    listOf(
        "bv-solver",
        "bitwise-eq",
        "bv-propagate",
        "bv-eager-eval",
        "solve-bv-as-int",
        "simplification",
        "sat-solver",
        "arrays-eager-index",
        "arrays-eager-lemmas",
        "static-learning",
    )

private const val MAX_ID_LENGTH = 60

private val json = Json { prettyPrint = true }

private class MvpConfig(
    val contract: Path,
    val workDir: Path,
    val targetKind: TsaWorkloadTargetKind,
    val candidates: Int,
    val methodTimeoutSeconds: Long,
    val solverTimeoutSeconds: Long,
    val trialTimeoutSeconds: Long,
    val seed: Long,
    val iterationLimit: Int?,
    val maxRecursionDepth: Int?,
) {
    companion object {
        fun fromSystemProperties(): MvpConfig =
            MvpConfig(
                contract =
                    Paths
                        .get(property("tsa.mvp.contract") ?: error("Pass -Pcontract=<path-to.boc>"))
                        .toAbsolutePath()
                        .normalize(),
                workDir =
                    Paths
                        .get(property("tsa.mvp.workDir") ?: "build/mvp-tuning")
                        .toAbsolutePath()
                        .normalize(),
                targetKind = targetKind(property("tsa.mvp.targetKind")),
                candidates = intProperty("tsa.mvp.candidates", 6),
                methodTimeoutSeconds = longProperty("tsa.mvp.methodTimeoutSeconds", 60),
                solverTimeoutSeconds = longProperty("tsa.mvp.solverTimeoutSeconds", 5),
                trialTimeoutSeconds = longProperty("tsa.mvp.trialTimeoutSeconds", 120),
                seed = longProperty("tsa.mvp.seed", 17),
                iterationLimit = optionalIntProperty("tsa.mvp.iterationLimit", 6),
                maxRecursionDepth = optionalIntProperty("tsa.mvp.maxRecursionDepth", 5),
            )

        private fun targetKind(value: String?): TsaWorkloadTargetKind =
            when (value ?: "receivers") {
                "receivers" -> TsaWorkloadTargetKind.RECEIVERS
                "all-methods" -> TsaWorkloadTargetKind.ALL_METHODS
                else -> error("Unsupported -PtargetKind=$value, expected receivers or all-methods")
            }

        private fun property(name: String): String? = System.getProperty(name)?.takeIf(String::isNotBlank)

        private fun intProperty(
            name: String,
            default: Int,
        ): Int = property(name)?.toIntOrNull() ?: default

        private fun longProperty(
            name: String,
            default: Long,
        ): Long = property(name)?.toLongOrNull() ?: default

        /** `unlimited` removes the limit; an absent property uses [default]. */
        private fun optionalIntProperty(
            name: String,
            default: Int,
        ): Int? =
            when (val value = property(name)) {
                null -> default
                "unlimited" -> null
                else -> value.toIntOrNull() ?: error("$name must be an integer or 'unlimited', got $value")
            }
    }
}

private fun referenceFor(config: MvpConfig): TsaWorkloadReference =
    TsaWorkloadReference(
        workloadId = "mvp-${config.contract.fileName.toString().substringBeforeLast('.').sanitizeId()}",
        revision = "rev-0",
    )

private fun String.sanitizeId(): String =
    map { character -> if (character.isLetterOrDigit() || character in "-_") character else '-' }
        .joinToString(separator = "")
        .take(MAX_ID_LENGTH)

private fun profileDirectory(
    config: MvpConfig,
    reference: TsaWorkloadReference,
): Path = config.workDir.resolve(reference.workloadId).resolve(reference.revision)

private fun writeDescriptor(
    config: MvpConfig,
    reference: TsaWorkloadReference,
) {
    val directory = profileDirectory(config, reference)

    // A workload revision is immutable, so the harness always starts from a clean directory.
    if (directory.toFile().exists() && !directory.toFile().deleteRecursively()) {
        error("Could not clean the previous workload directory: $directory")
    }
    directory.createDirectories()

    val descriptor =
        TsaAnalysisWorkload(
            workloadId = reference.workloadId,
            revision = reference.revision,
            sourceKind = TsaWorkloadSourceKind.BOC,
            sourcePath = config.contract.toString(),
            targetKind = config.targetKind,
            methodTimeoutSeconds = config.methodTimeoutSeconds,
            solverTimeoutSeconds = config.solverTimeoutSeconds,
            iterationLimit = config.iterationLimit,
            maxRecursionDepth = config.maxRecursionDepth,
        )
    directory
        .resolve("workload.json")
        .writeText(json.encodeToString(TsaAnalysisWorkload.serializer(), descriptor) + "\n")
}

private fun formatSeconds(duration: Duration): String = "%.1f s".format(duration.inWholeMilliseconds / 1000.0)

private fun millis(measurement: MvpMeasurement): String = "%.1f ms".format(measurement.totalCostMillis)

private fun describe(measurement: MvpMeasurement): String =
    "ranked ${measurement.rankedCount}/${measurement.instanceCount}, " +
        "wrong=${measurement.wrongAnswerCount}, timeouts=${measurement.timeoutCount}, " +
        "failures=${measurement.failureCount}"

private fun format(measurement: MvpMeasurement): String =
    if (measurement.isEligible) {
        "${millis(measurement)} (${measurement.rankedCount}/${measurement.instanceCount} ranked)"
    } else {
        "INELIGIBLE (${describe(measurement)})"
    }

private fun report(
    measurements: List<MvpMeasurement>,
    baseline: MvpMeasurement,
    search: MvpSearch,
) {
    println()
    println("=== summary ===")
    println("baseline       : ${describe(baseline)}")

    val scored =
        measurements
            .filter { it.label != baseline.label }
            .mapNotNull { measurement -> search.normalizedScore(baseline, measurement)?.let { measurement to it } }
    val eligible = scored.filter { (measurement, _) -> measurement.isEligible }
    val best = eligible.minByOrNull { (_, score) -> score }

    if (best == null) {
        println("No eligible candidate was produced, so the default configuration stays unchanged.")
    } else {
        val (measurement, score) = best
        println("best candidate : ${measurement.label}")
        measurement.options.toSortedMap().forEach { (name, value) -> println("  $name = $value") }
        println("  normalized   : ${"%.4f".format(score)} (1.0 = baseline)")
        println("  improvement  : ${"%.2f".format(100.0 * (1.0 - score))}%")
        println(
            "  measured     : ${millis(measurement)} over " +
                "${measurement.rankedCount}/${measurement.instanceCount} instances",
        )
    }

    println()
    println(
        "correctness    : wrong answers=${measurements.sumOf(MvpMeasurement::wrongAnswerCount)}, " +
            "timeouts=${measurements.sumOf(MvpMeasurement::timeoutCount)}, " +
            "failures=${measurements.sumOf(MvpMeasurement::failureCount)}",
    )
    println("configurations : ${measurements.size} measured, ${eligible.size} eligible")
    println()
    println("Scores are per-instance costs normalized by the baseline and combined as a geometric mean.")
    println(
        "A censored instance contributes a " +
            "${"%.1f".format(search.timeoutPenaltyNanos / 1_000_000_000.0)}s penalty.",
    )
    println()
    println("This harness exercises only the TSA side of the loop. The production search host, a real")
    println("parameter-space document, and the model path belong to ksmt-ml-tuner and ksmt-ml.")
}
