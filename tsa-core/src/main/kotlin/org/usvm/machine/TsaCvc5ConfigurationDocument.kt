package org.usvm.machine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Versioned description of a cvc5 configuration to apply to one backend role.
 *
 * The document is an application-side artifact. It is written by hand or produced from a validated
 * `Cvc5TuningDecision`, and TSA validates its structure only: option domains remain the
 * responsibility of the parameter space that produced the configuration.
 */
@Serializable
data class TsaCvc5ConfigurationDocument(
    val schemaVersion: String = SCHEMA_VERSION,
    val parameterSpaceId: String,
    val backendRole: String,
    val options: Map<String, String>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported cvc5 configuration schema: $schemaVersion" }
        require(parameterSpaceId.isNotBlank()) { "Parameter-space id must not be blank" }
        require(backendRole in TvmCvc5Configuration.BACKEND_ROLES) {
            "Unsupported cvc5 backend role: $backendRole"
        }
        require(options.isNotEmpty()) { "A cvc5 configuration must set at least one option" }
    }

    fun toConfiguration(): TvmCvc5Configuration =
        TvmCvc5Configuration(
            backendRole = backendRole,
            parameterSpaceId = parameterSpaceId,
            options = options,
        )

    companion object {
        const val SCHEMA_VERSION = "tsa-cvc5-configuration-v0"

        fun load(path: Path): TsaCvc5ConfigurationDocument {
            require(path.isRegularFile()) { "cvc5 configuration does not exist: $path" }
            return json.decodeFromString(serializer(), path.readText())
        }

        private val json = Json { ignoreUnknownKeys = false }
    }
}
