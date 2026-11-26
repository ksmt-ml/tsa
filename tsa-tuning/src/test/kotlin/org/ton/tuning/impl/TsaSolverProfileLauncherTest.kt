package org.ton.tuning.impl

import io.ksmt.profiling.KSolverDescriptor
import org.ton.tuning.TsaLaunchCancellation
import org.ton.tuning.TsaSolverProfileLaunchRequest
import org.ton.tuning.TsaSolverProfileLaunchStatus
import org.ton.tuning.TsaSolverProfileSink
import org.ton.tuning.TsaWorkloadReference
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class TsaSolverProfileLauncherTest {
    @Test
    fun `fails closed when the workload descriptor is missing`() {
        withLauncher(writeDescriptor = false) { launcher ->
            val result = launcher.launch(request(), TsaSolverProfileSink {}, TsaLaunchCancellation.NONE)

            assertEquals(TsaSolverProfileLaunchStatus.FAILED, result.status)
            assertEquals("TsaWorkloadNotFound", result.failureType)
            assertEquals(0L, result.capturedProfiles)
            assertEquals(0L, result.failedProfiles)
        }
    }

    @Test
    fun `honours cancellation before loading the contract`() {
        withLauncher { launcher ->
            val result =
                launcher.launch(request(), TsaSolverProfileSink {}, TsaLaunchCancellation { true })

            assertEquals(TsaSolverProfileLaunchStatus.CANCELLED, result.status)
            assertEquals(0L, result.capturedProfiles)
        }
    }

    @Test
    fun `reports a timeout when the budget is already exhausted`() {
        withLauncher { launcher ->
            val result =
                launcher.launch(
                    request(timeout = 1.nanoseconds),
                    TsaSolverProfileSink {},
                    TsaLaunchCancellation.NONE,
                )

            assertEquals(TsaSolverProfileLaunchStatus.TIMED_OUT, result.status)
            assertEquals(0L, result.capturedProfiles)
        }
    }

    @Test
    fun `reports a failure when the contract cannot be loaded`() {
        withLauncher { launcher ->
            val result = launcher.launch(request(), TsaSolverProfileSink {}, TsaLaunchCancellation.NONE)

            assertEquals(TsaSolverProfileLaunchStatus.FAILED, result.status)
            assertEquals(0L, result.capturedProfiles)
        }
    }

    @Test
    fun `refuses to capture into a revision that already holds profiles`() {
        withLauncher(writeExistingProfile = true) { launcher ->
            val result = launcher.launch(request(), TsaSolverProfileSink {}, TsaLaunchCancellation.NONE)

            assertEquals(TsaSolverProfileLaunchStatus.FAILED, result.status)
            assertEquals("TsaProfileDirectoryNotEmpty", result.failureType)
            assertEquals(0L, result.capturedProfiles)
        }
    }

    private fun withLauncher(
        writeDescriptor: Boolean = true,
        writeExistingProfile: Boolean = false,
        block: (TsaSolverProfileLauncherImpl) -> Unit,
    ) {
        val root = Files.createTempDirectory("tsa-tuning-launcher-test")
        try {
            val directory = root.resolve(WORKLOAD_ID).resolve(REVISION)
            directory.createDirectories()
            if (writeDescriptor) {
                directory.resolve(DESCRIPTOR_FILE_NAME).writeText(descriptor())
            }
            if (writeExistingProfile) {
                directory.resolve(EXISTING_PROFILE_FILE_NAME).writeText("{}")
            }
            block(
                TsaSolverProfileLauncherImpl(
                    catalog = FileSystemTsaAnalysisWorkloadCatalog(root),
                    profileStoreRoot = root,
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun request(timeout: Duration = Duration.INFINITE): TsaSolverProfileLaunchRequest =
        TsaSolverProfileLaunchRequest(
            runId = "run-0",
            workload = TsaWorkloadReference(WORKLOAD_ID, REVISION),
            solver = KSolverDescriptor("cvc5"),
            timeout = timeout,
        )

    private fun descriptor(): String =
        """
        {
          "schemaVersion": "${TsaAnalysisWorkload.SCHEMA_VERSION}",
          "workloadId": "$WORKLOAD_ID",
          "revision": "$REVISION",
          "sourceKind": "boc",
          "sourcePath": "/contracts/contract.boc",
          "targetKind": "receivers",
          "methodTimeoutSeconds": 900
        }
        """.trimIndent()

    private companion object {
        const val WORKLOAD_ID = "workload-0"
        const val REVISION = "rev-0"
        const val DESCRIPTOR_FILE_NAME = "workload.json"

        /** Matches the artifact naming convention of `TvmSolverProfileWriter`. */
        const val EXISTING_PROFILE_FILE_NAME = "tsa-solver-profile-run-0-profile-0.json"
    }
}
