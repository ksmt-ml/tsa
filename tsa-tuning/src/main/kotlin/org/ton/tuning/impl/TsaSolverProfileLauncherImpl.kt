package org.ton.tuning.impl

import io.ksmt.profiling.KSolverProfile
import io.ksmt.profiling.KSolverProfileState
import org.ton.bytecode.MethodId
import org.ton.bytecode.TsaContractCode
import org.ton.tuning.TsaLaunchCancellation
import org.ton.tuning.TsaSolverProfileLaunchRequest
import org.ton.tuning.TsaSolverProfileLaunchResult
import org.ton.tuning.TsaSolverProfileLaunchStatus
import org.ton.tuning.TsaSolverProfileLauncher
import org.ton.tuning.TsaSolverProfileSink
import org.usvm.machine.BocAnalyzer
import org.usvm.machine.TvmContext.Companion.RECEIVE_EXTERNAL_ID
import org.usvm.machine.TvmContext.Companion.RECEIVE_INTERNAL_ID
import org.usvm.machine.TvmOptions
import org.usvm.machine.TvmSolverProfileOptions
import org.usvm.machine.TvmSolverProfileWriter
import org.usvm.machine.analyzeSpecificMethod
import org.usvm.machine.getFuncContract
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Production TSA workload capture.
 *
 * Resolves a versioned [TsaAnalysisWorkload], runs its bounded analysis with KSMT profiling
 * enabled, and emits every terminal profile to the sink. TSA is never invoked as a subprocess and
 * captured profiles are never rewritten.
 *
 * TSA applies its own timeout to every method independently, so each method receives the smaller of
 * the workload's method timeout and its share of the remaining launch budget. The launch stops at
 * the first method boundary where cancellation or the deadline is reached, and reports only the
 * profiles that were fully captured.
 */
class TsaSolverProfileLauncherImpl(
    private val catalog: TsaAnalysisWorkloadCatalog,
    private val profileStoreRoot: Path,
) : TsaSolverProfileLauncher {
    override fun launch(
        request: TsaSolverProfileLaunchRequest,
        sink: TsaSolverProfileSink,
        cancellation: TsaLaunchCancellation,
    ): TsaSolverProfileLaunchResult {
        val mark = TimeSource.Monotonic.markNow()
        val deadline = request.timeout.takeUnless { it.isInfinite() }
        val captured = mutableListOf<KSolverProfile>()

        fun result(
            status: TsaSolverProfileLaunchStatus,
            failureType: String? = null,
            failureMessage: String? = null,
        ): TsaSolverProfileLaunchResult {
            val complete = captured.count { it.state == KSolverProfileState.CLOSED && !it.isTruncated }
            return TsaSolverProfileLaunchResult(
                runId = request.runId,
                status = status,
                capturedProfiles = complete.toLong(),
                failedProfiles = (captured.size - complete).toLong(),
                duration = mark.elapsedNow(),
                failureType = failureType,
                failureMessage = failureMessage,
                attributes = request.attributes + mapOf("workload-id" to request.workload.workloadId),
            )
        }

        val workload =
            catalog.resolve(request.workload)
                ?: return result(
                    TsaSolverProfileLaunchStatus.FAILED,
                    failureType = "TsaWorkloadNotFound",
                    failureMessage =
                        "No analysis workload descriptor for " +
                            "${request.workload.workloadId}@${request.workload.revision}",
                )

        if (cancellation.isCancellationRequested()) {
            return result(TsaSolverProfileLaunchStatus.CANCELLED)
        }

        if (deadline?.let { it - mark.elapsedNow() }?.let { it <= Duration.ZERO } == true) {
            return result(TsaSolverProfileLaunchStatus.TIMED_OUT)
        }

        val profilesDirectory =
            profileStoreRoot.resolve(request.workload.workloadId).resolve(request.workload.revision)
        existingProfileCount(profilesDirectory)?.let { existing ->
            return result(
                TsaSolverProfileLaunchStatus.FAILED,
                failureType = "TsaProfileDirectoryNotEmpty",
                failureMessage =
                    "Refusing to capture into a workload revision that already holds $existing " +
                        "profile(s): $profilesDirectory",
            )
        }

        val contract =
            try {
                loadContract(workload)
            } catch (failure: Exception) {
                return result(
                    TsaSolverProfileLaunchStatus.FAILED,
                    failureType = failure.javaClass.name,
                    failureMessage = failure.message,
                )
            }

        val methods = workload.methodIds(contract)
        if (methods.isEmpty()) {
            return result(
                TsaSolverProfileLaunchStatus.FAILED,
                failureType = "TsaNoSelectedMethods",
                failureMessage = "The workload selects no methods to analyze",
            )
        }

        val collectingSink =
            TsaSolverProfileSink { profile ->
                captured += profile
                sink.accept(profile)
            }

        var status = TsaSolverProfileLaunchStatus.COMPLETED
        var failureType: String? = null
        var failureMessage: String? = null

        try {
            for (index in methods.indices) {
                if (cancellation.isCancellationRequested()) {
                    status = TsaSolverProfileLaunchStatus.CANCELLED
                    break
                }

                val remaining = deadline?.let { it - mark.elapsedNow() }
                if (remaining != null && remaining <= Duration.ZERO) {
                    status = TsaSolverProfileLaunchStatus.TIMED_OUT
                    break
                }

                val remainingPerMethod = remaining?.div(methods.size - index)
                val methodTimeout =
                    if (remainingPerMethod == null) {
                        workload.methodTimeoutSeconds.seconds
                    } else {
                        minOf(workload.methodTimeoutSeconds.seconds, remainingPerMethod)
                    }

                analyzeSpecificMethod(
                    contract,
                    methods[index],
                    tvmOptions =
                        tvmOptions(
                            request = request,
                            workload = workload,
                            profilesDirectory = profilesDirectory,
                            sink = collectingSink,
                            methodTimeout = methodTimeout,
                        ),
                )
            }
        } catch (failure: Exception) {
            status = TsaSolverProfileLaunchStatus.FAILED
            failureType = failure.javaClass.name
            failureMessage = failure.message
        }

        return result(status, failureType, failureMessage)
    }
}

private const val DEFAULT_CHECKPOINT_INTERVAL_CHECKS = 500

/**
 * Number of profile artifacts already present in a workload revision, or null when there are none.
 *
 * A workload revision is immutable: profiles of different runs must never be mixed, because the
 * resolver would then measure a workload that no capture ever produced.
 */
private fun existingProfileCount(directory: Path): Int? =
    if (!directory.toFile().isDirectory) {
        null
    } else {
        directory
            .listDirectoryEntries()
            .count { it.fileName.toString().startsWith(TvmSolverProfileWriter.FILE_NAME_PREFIX) }
            .takeIf { it > 0 }
    }

private fun loadContract(workload: TsaAnalysisWorkload): TsaContractCode =
    when (workload.sourceKind) {
        TsaWorkloadSourceKind.BOC -> BocAnalyzer.convertToTvmContractCode(workload.source)
        TsaWorkloadSourceKind.FUNC -> getFuncContract(workload.source, requireNotNull(workload.fiftStdlib))
    }

private fun TsaAnalysisWorkload.methodIds(contract: TsaContractCode): List<MethodId> =
    when (targetKind) {
        TsaWorkloadTargetKind.RECEIVERS -> listOf(RECEIVE_INTERNAL_ID, RECEIVE_EXTERNAL_ID)
        TsaWorkloadTargetKind.ALL_METHODS -> contract.methods.keys.toList()
        TsaWorkloadTargetKind.METHOD -> listOf(requireNotNull(method))
    }

private fun tvmOptions(
    request: TsaSolverProfileLaunchRequest,
    workload: TsaAnalysisWorkload,
    profilesDirectory: Path,
    sink: TsaSolverProfileSink,
    methodTimeout: Duration,
): TvmOptions =
    TvmOptions(
        quietMode = true,
        timeout = methodTimeout,
        solverTimeout = workload.solverTimeoutSeconds.seconds,
        maxRecursionDepth = workload.maxRecursionDepth,
        loopIterationLimit = workload.iterationLimit,
        shortResolve = workload.shortResolve,
        ignoreLowPriorityStates = !workload.analyzeLowPriorityStates,
        solverProfileOptions =
            TvmSolverProfileOptions(
                outputDirectory = profilesDirectory,
                runId = request.runId,
                workloadId = request.workload.workloadId,
                maxRecordedEvents = request.maxRecordedEventsPerProfile,
                checkpointIntervalChecks = DEFAULT_CHECKPOINT_INTERVAL_CHECKS,
                attributes = request.attributes,
                onTerminalProfile = sink::accept,
            ),
    )
