package org.ton.tuning.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.ton.tuning.TsaCvc5TrialWorkload
import org.ton.tuning.TsaCvc5TrialWorkloadResolver
import org.ton.tuning.TsaWorkloadReference
import org.usvm.machine.TvmCvc5ProfileReplayEvaluator
import org.usvm.machine.TvmSolverProfileWriter
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

/** One replayable TSA cvc5 backend profile used as a single workload instance. */
data class TsaCvc5ReplayProfile(
    val instanceId: String,
    val profileId: String,
    val runId: String,
    val backendRole: String,
    val solverVersion: String,
    val source: Path,
)

/**
 * Resolves a TSA workload reference into a workload evaluated by replaying captured cvc5 backend
 * profiles.
 *
 * Captured profiles are read from `<profilesRoot>/<workloadId>/<revision>`. Every replayable cvc5
 * backend profile found there becomes one workload instance keyed by its profile id. Profiles that
 * are not closed, truncated, or produced by a direct cvc5 backend are ignored.
 */
class TsaCvc5ProfileReplayWorkloadResolver(
    private val profilesRoot: Path,
    private val parameterSpaceId: String,
    private val evaluator: TvmCvc5ProfileReplayEvaluator = TvmCvc5ProfileReplayEvaluator(),
) : TsaCvc5TrialWorkloadResolver {
    override fun resolve(reference: TsaWorkloadReference): TsaCvc5TrialWorkload {
        require(reference.workloadId.isNotBlank()) { "Workload id must not be blank" }
        require(reference.revision.isNotBlank()) { "Workload revision must not be blank" }

        val workloadDirectory = profilesRoot.resolve(reference.workloadId).resolve(reference.revision)
        require(workloadDirectory.toFile().isDirectory) {
            "No captured profiles for workload ${reference.workloadId}@${reference.revision}: " +
                workloadDirectory
        }

        val profiles =
            workloadDirectory
                .listDirectoryEntries()
                .asSequence()
                .filter { it.extension == JSON_EXTENSION }
                .mapNotNull(::replayProfileOrNull)
                .sortedBy(TsaCvc5ReplayProfile::instanceId)
                .toList()
        require(profiles.isNotEmpty()) {
            "No replayable cvc5 backend profiles for workload " +
                "${reference.workloadId}@${reference.revision}"
        }

        return TsaCvc5ProfileReplayWorkload(
            reference = reference,
            parameterSpaceId = parameterSpaceId,
            profiles = profiles,
            evaluator = evaluator,
        )
    }

    private fun replayProfileOrNull(source: Path): TsaCvc5ReplayProfile? {
        val profile = runCatching { evaluator.parseProfile(source) }.getOrNull() ?: return null

        val producer = profile["producer"] as? JsonObject ?: return null
        if (producer.optionalString("format") != TvmSolverProfileWriter.FORMAT_VERSION) return null
        if (producer.booleanOrNull("replayable") != true) return null
        if (producer.booleanOrNull("captureComplete") != true) return null

        val solver = profile["solver"] as? JsonObject ?: return null
        if (solver.optionalString("name") != TARGET_SOLVER) return null
        val backendRole = (solver["attributes"] as? JsonObject)?.optionalString(BACKEND_ROLE_ATTRIBUTE)
        if (backendRole == null || backendRole !in SUPPORTED_BACKEND_ROLES) return null

        val profileId = profile.optionalString("profileId") ?: return null
        val runId = profile.optionalString("runId") ?: return null
        val solverVersion = solver.optionalString("version") ?: return null

        return TsaCvc5ReplayProfile(
            instanceId = profileId,
            profileId = profileId,
            runId = runId,
            backendRole = backendRole,
            solverVersion = solverVersion,
            source = source,
        )
    }

    private companion object {
        const val JSON_EXTENSION = "json"
        const val TARGET_SOLVER = "cvc5"
        const val BACKEND_ROLE_ATTRIBUTE = "backend-role"
        val SUPPORTED_BACKEND_ROLES = setOf("regular-bv", "integer-blasted")
    }
}

private fun JsonObject.optionalString(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanOrNull(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
