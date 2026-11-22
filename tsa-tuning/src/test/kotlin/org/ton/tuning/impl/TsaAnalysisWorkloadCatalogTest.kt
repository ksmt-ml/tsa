package org.ton.tuning.impl

import org.ton.tuning.TsaWorkloadReference
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TsaAnalysisWorkloadCatalogTest {
    @Test
    fun `resolves a versioned workload descriptor`() {
        withCatalog(descriptor()) { catalog ->
            val workload = catalog.resolve(reference())

            assertNotNull(workload)
            assertEquals(WORKLOAD_ID, workload.workloadId)
            assertEquals(REVISION, workload.revision)
            assertEquals(TsaWorkloadSourceKind.BOC, workload.sourceKind)
            assertEquals(TsaWorkloadTargetKind.RECEIVERS, workload.targetKind)
            assertEquals(900L, workload.methodTimeoutSeconds)
            assertEquals(5L, workload.solverTimeoutSeconds)
            assertEquals(6, workload.iterationLimit)
            assertEquals(false, workload.shortResolve)
        }
    }

    @Test
    fun `resolves a single-method descriptor`() {
        withCatalog(descriptor(targetKind = "method", methodId = "7")) { catalog ->
            val workload = catalog.resolve(reference())

            assertNotNull(workload)
            assertEquals(TsaWorkloadTargetKind.METHOD, workload.targetKind)
            assertEquals(7, workload.method?.toInt())
        }
    }

    @Test
    fun `resolves nothing when the descriptor is absent`() {
        val root = Files.createTempDirectory("tsa-tuning-catalog-missing")
        try {
            assertNull(FileSystemTsaAnalysisWorkloadCatalog(root).resolve(reference()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolves nothing for an unsupported schema version`() {
        withCatalog(descriptor(schemaVersion = "tsa-analysis-workload-v1")) { catalog ->
            assertNull(catalog.resolve(reference()))
        }
    }

    @Test
    fun `resolves nothing for a func workload without a fift stdlib`() {
        withCatalog(descriptor(sourceKind = "func")) { catalog ->
            assertNull(catalog.resolve(reference()))
        }
    }

    @Test
    fun `resolves nothing for a receivers workload that declares a method id`() {
        withCatalog(descriptor(methodId = "7")) { catalog ->
            assertNull(catalog.resolve(reference()))
        }
    }

    @Test
    fun `resolves nothing for a malformed document`() {
        withCatalog("{ not json") { catalog ->
            assertNull(catalog.resolve(reference()))
        }
    }

    private fun withCatalog(
        document: String,
        block: (FileSystemTsaAnalysisWorkloadCatalog) -> Unit,
    ) {
        val root = Files.createTempDirectory("tsa-tuning-catalog-test")
        try {
            val directory = root.resolve(WORKLOAD_ID).resolve(REVISION)
            directory.createDirectories()
            directory.resolve(DESCRIPTOR_FILE_NAME).writeText(document)
            block(FileSystemTsaAnalysisWorkloadCatalog(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun reference(): TsaWorkloadReference = TsaWorkloadReference(WORKLOAD_ID, REVISION)

    private fun descriptor(
        schemaVersion: String = TsaAnalysisWorkload.SCHEMA_VERSION,
        sourceKind: String = "boc",
        fiftStdlibPath: String? = null,
        targetKind: String = "receivers",
        methodId: String? = null,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": \"$schemaVersion\",")
            appendLine("  \"workloadId\": \"$WORKLOAD_ID\",")
            appendLine("  \"revision\": \"$REVISION\",")
            appendLine("  \"sourceKind\": \"$sourceKind\",")
            fiftStdlibPath?.let { appendLine("  \"fiftStdlibPath\": \"$it\",") }
            appendLine("  \"sourcePath\": \"/contracts/contract.boc\",")
            appendLine("  \"targetKind\": \"$targetKind\",")
            methodId?.let { appendLine("  \"methodId\": \"$it\",") }
            appendLine("  \"methodTimeoutSeconds\": 900,")
            appendLine("  \"solverTimeoutSeconds\": 5,")
            appendLine("  \"iterationLimit\": 6")
            appendLine("}")
        }

    private companion object {
        const val WORKLOAD_ID = "workload-0"
        const val REVISION = "rev-0"
        const val DESCRIPTOR_FILE_NAME = "workload.json"
    }
}
