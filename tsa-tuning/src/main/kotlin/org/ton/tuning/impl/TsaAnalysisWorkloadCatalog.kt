package org.ton.tuning.impl

import kotlinx.serialization.json.Json
import org.ton.tuning.TsaWorkloadReference
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Resolves an opaque workload reference into its versioned analysis description.
 *
 * A catalog never invents analysis parameters. It returns null when no valid descriptor exists, so
 * the launcher can fail closed instead of running an unspecified analysis.
 */
fun interface TsaAnalysisWorkloadCatalog {
    fun resolve(reference: TsaWorkloadReference): TsaAnalysisWorkload?
}

/**
 * Catalog backed by `<workloadsRoot>/<workloadId>/<revision>/workload.json`.
 *
 * Malformed or unsupported descriptors resolve to null, exactly like a missing one.
 */
class FileSystemTsaAnalysisWorkloadCatalog(
    private val workloadsRoot: Path,
) : TsaAnalysisWorkloadCatalog {
    override fun resolve(reference: TsaWorkloadReference): TsaAnalysisWorkload? {
        if (reference.workloadId.isBlank() || reference.revision.isBlank()) return null

        val descriptor = descriptorPath(reference)
        if (!descriptor.isRegularFile()) return null

        return runCatching {
            json.decodeFromString(TsaAnalysisWorkload.serializer(), descriptor.readText())
        }.getOrNull()
    }

    /** The descriptor location of [reference], whether or not it exists. */
    fun descriptorPath(reference: TsaWorkloadReference): Path =
        workloadsRoot
            .resolve(reference.workloadId)
            .resolve(reference.revision)
            .resolve(DESCRIPTOR_FILE_NAME)

    private companion object {
        const val DESCRIPTOR_FILE_NAME = "workload.json"
        val json = Json { ignoreUnknownKeys = false }
    }
}
