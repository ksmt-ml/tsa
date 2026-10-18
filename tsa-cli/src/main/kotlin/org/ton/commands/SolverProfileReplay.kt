package org.ton.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.usvm.machine.TvmCvc5ProfileReplayEvaluator
import org.usvm.machine.TvmCvc5ReplayBatchReport
import org.usvm.machine.TvmCvc5ReplayCandidate
import org.usvm.machine.TvmCvc5ReplayProgress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

class SolverProfileReplay :
    CliktCommand(
        name = "profile-replay",
        help = "Replay complete TSA cvc5 backend profiles with an offline candidate configuration.",
    ) {
    private val profiles by option("--profile")
        .path(mustExist = true, canBeFile = true, canBeDir = false)
        .multiple()
        .help("Profile JSON file. May be specified more than once.")

    private val profileDirectory by option("--profile-dir")
        .path(mustExist = true, canBeFile = false, canBeDir = true)
        .help("Directory containing profile JSON files.")

    private val backendRole by option("--backend-role")
        .default("regular-bv")
        .help("cvc5 backend role to select. Default: regular-bv.")
        .validate { require(it == "regular-bv" || it == "integer-blasted") }

    private val candidatePath by option("--candidate")
        .path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
        .help("Candidate configuration using cvc5-replay-candidate-v0.")

    private val outputPath by option("--output")
        .path(mustExist = false, canBeFile = true, canBeDir = false)
        .required()
        .help("Destination for the v0 replay report.")

    private val repetitions by option("--repetitions")
        .int()
        .default(1)
        .help("Measured repetitions per profile. Default: 1.")
        .validate { require(it > 0) }

    private val warmupRepetitions by option("--warmups")
        .int()
        .default(0)
        .help("Unreported warm-up repetitions per profile. Default: 0.")
        .validate { require(it >= 0) }

    private val progressIntervalSeconds by option("--progress-interval-seconds")
        .int()
        .default(15)
        .help("Print replay heartbeat every N seconds; 0 disables heartbeat. Default: 15.")
        .validate { require(it >= 0) }

    override fun run() {
        require(profiles.isNotEmpty() || profileDirectory != null) {
            "At least one --profile or --profile-dir is required"
        }

        val evaluator = TvmCvc5ProfileReplayEvaluator()
        val candidate = evaluator.parseCandidate(candidatePath)
        val selectedProfiles = selectProfiles()
        require(selectedProfiles.isNotEmpty()) { "No $backendRole profiles were found" }
        echo(
            "Starting cvc5 profile replay: candidate=${candidate.candidateId}, " +
                "backend=$backendRole, profiles=${selectedProfiles.size}, " +
                "warmups=$warmupRepetitions, repetitions=$repetitions",
        )

        val results =
            selectedProfiles.flatMapIndexed { profileIndex, profilePath ->
                val profile = evaluator.parseProfile(profilePath)
                repeat(warmupRepetitions) { warmup ->
                    val result =
                        evaluateWithProgress(
                            evaluator = evaluator,
                            candidate = candidate,
                            profilePath = profilePath,
                            profileIndex = profileIndex,
                            profileCount = selectedProfiles.size,
                            profile = profile,
                            repetition = warmup,
                            repetitionKind = "warmup",
                            repetitionCount = warmupRepetitions,
                        )
                    require(result.outcome == "SUCCESS") {
                        "Warm-up replay failed for ${profilePath.fileName}: ${result.failureMessage}"
                    }
                }
                List(repetitions) { repetition ->
                    evaluateWithProgress(
                        evaluator = evaluator,
                        candidate = candidate,
                        profilePath = profilePath,
                        profileIndex = profileIndex,
                        profileCount = selectedProfiles.size,
                        profile = profile,
                        repetition = repetition,
                        repetitionKind = "measured",
                        repetitionCount = repetitions,
                    )
                }
            }

        val report =
            TvmCvc5ReplayBatchReport(
                createdAtEpochMillis = System.currentTimeMillis(),
                candidate = candidate,
                backendRole = backendRole,
                repetitions = repetitions,
                warmupRepetitions = warmupRepetitions,
                sourceProfiles = selectedProfiles.map { it.fileName.toString() },
                results = results,
            )
        writeAtomically(outputPath, json.encodeToString(report))

        val failures = results.count { it.outcome != "SUCCESS" }
        val wrongAnswers = results.sumOf { it.wrongAnswerCount }
        echo(
            "Replayed ${selectedProfiles.size} profiles x $repetitions repetitions; " +
                "failures=$failures, wrongAnswers=$wrongAnswers, report=$outputPath",
        )
    }

    private fun evaluateWithProgress(
        evaluator: TvmCvc5ProfileReplayEvaluator,
        candidate: TvmCvc5ReplayCandidate,
        profilePath: Path,
        profileIndex: Int,
        profileCount: Int,
        profile: kotlinx.serialization.json.JsonObject,
        repetition: Int,
        repetitionKind: String,
        repetitionCount: Int,
    ): org.usvm.machine.TvmCvc5ProfileReplayResult {
        val label =
            "profile=${profileIndex + 1}/$profileCount ${profilePath.fileName}, " +
                "$repetitionKind=${repetition + 1}/$repetitionCount"
        val startedAt = System.nanoTime()
        val latestProgress = AtomicReference<TvmCvc5ReplayProgress>()
        echo("Replay started: $label")

        val progressExecutor =
            if (progressIntervalSeconds > 0) {
                Executors
                    .newSingleThreadScheduledExecutor { runnable ->
                        Thread(runnable, "tsa-cvc5-replay-progress").apply { isDaemon = true }
                    }.also { executor ->
                        executor.scheduleAtFixedRate(
                            {
                                val progress = latestProgress.get()
                                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                                val eventProgress =
                                    progress?.let { "events=${it.processedEventCount}/${it.eventCount}" }
                                        ?: "events=0/?"
                                val checkProgress = progress?.let { ", checks=${it.checkCount}" }.orEmpty()
                                val operation =
                                    progress
                                        ?.let {
                                            buildString {
                                                append(", phase=${it.phase}")
                                                it.currentEventType?.let { type -> append(", event=$type") }
                                                it.currentCheckId?.let { checkId -> append(", checkId=$checkId") }
                                            }
                                        }.orEmpty()
                                echo(
                                    "Replay progress: $label, $eventProgress$checkProgress$operation, " +
                                        "elapsed=${String.format(Locale.ROOT, "%.1f", elapsedSeconds)}s",
                                )
                            },
                            progressIntervalSeconds.toLong(),
                            progressIntervalSeconds.toLong(),
                            TimeUnit.SECONDS,
                        )
                    }
            } else {
                null
            }

        return try {
            evaluator.evaluate(profile, candidate, repetition, latestProgress::set).also { result ->
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                echo(
                    "Replay finished: $label, outcome=${result.outcome}, " +
                        "events=${result.executedEventCount}/${result.eventCount}, " +
                        "checks=${result.checkCount}, timeouts=${result.knownStatusTimeoutCount}, " +
                        "wrongAnswers=${result.wrongAnswerCount}, " +
                        "elapsed=${String.format(Locale.ROOT, "%.1f", elapsedSeconds)}s",
                )
            }
        } finally {
            progressExecutor?.shutdownNow()
        }
    }

    private fun selectProfiles(): List<Path> {
        val directoryProfiles =
            profileDirectory
                ?.listDirectoryEntries()
                .orEmpty()
                .asSequence()
                .filter { it.extension == "json" }
                .filter { hasBackendRole(it, backendRole) }
                .toList()

        return (profiles + directoryProfiles)
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sortedBy(Path::toString)
            .onEach {
                require(hasBackendRole(it, backendRole)) {
                    "Profile ${it.fileName} does not have backend role $backendRole"
                }
            }
    }

    private fun hasBackendRole(
        path: Path,
        role: String,
    ): Boolean =
        path.bufferedReader().useLines { lines ->
            lines
                .takeWhile { line -> !line.contains("\"events\"") }
                .any { line -> line.contains("\"backend-role\": \"$role\"") }
        }

    private fun writeAtomically(
        path: Path,
        content: String,
    ) {
        val absolutePath = path.toAbsolutePath().normalize()
        absolutePath.parent?.createDirectories()
        val temporary = Files.createTempFile(absolutePath.parent, ".${absolutePath.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, content)
            try {
                Files.move(
                    temporary,
                    absolutePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, absolutePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }
    }
}
