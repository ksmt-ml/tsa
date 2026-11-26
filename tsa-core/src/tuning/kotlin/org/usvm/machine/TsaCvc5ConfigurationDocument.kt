package org.usvm.machine

import io.ksmt.ml.tuner.configuration.Cvc5ConfigurationPatch
import io.ksmt.ml.tuner.configuration.Cvc5ParameterSpace
import io.ksmt.ml.tuner.configuration.Cvc5PatchValidation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Versioned description of a cvc5 configuration to apply to one backend role.
 *
 * The document carries an untrusted application-side patch. An application supplies its approved
 * tuner parameter space to [validate] before constructing a configured solver.
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
        // The apply path and its document share one set of option rules, so they cannot diverge.
        TvmCvc5Configuration.validateOptions(options)
    }

    fun validate(parameterSpace: Cvc5ParameterSpace): TvmCvc5Configuration {
        require(parameterSpace.schemaVersion == Cvc5ParameterSpace.CURRENT_SCHEMA_VERSION) {
            "Unsupported cvc5 parameter space schema: ${parameterSpace.schemaVersion}"
        }
        require(
            parameterSpace.targetSolver == Cvc5ParameterSpace.TARGET_SOLVER &&
                parameterSpace.applicationLifecycle == Cvc5ParameterSpace.FRESH_SOLVER_LIFECYCLE,
        ) { "The parameter space must target a fresh cvc5 solver" }
        require(parameterSpace.parameterSpaceId == parameterSpaceId && parameterSpace.backendRole == backendRole) {
            "The configuration does not match the approved parameter space and backend role"
        }

        val version = TvmCvc5OptionInventoryReader.current.solverVersion
        require(version in parameterSpace.compatibleSolverVersions) {
            "cvc5 $version is incompatible with the approved parameter space"
        }

        val patch = Cvc5ConfigurationPatch(parameterSpaceId, options.toMap())
        return when (val validation = parameterSpace.validate(patch)) {
            is Cvc5PatchValidation.Accepted -> {
                val accepted = validation.configuration
                require(accepted.parameterSpaceId == parameterSpaceId && accepted.options == patch.options) {
                    "The approved parameter space returned a different patch"
                }
                TvmCvc5Configuration(backendRole, accepted.parameterSpaceId, accepted.options, version)
            }
            is Cvc5PatchValidation.Rejected -> throw IllegalArgumentException(validation.reason)
        }
    }

    companion object {
        const val SCHEMA_VERSION = "tsa-cvc5-configuration-v0"

        /** Classpath location of the JSON schema packaged with this document type. */
        const val SCHEMA_RESOURCE = "org/usvm/machine/schemas/tsa-cvc5-configuration-v0.schema.json"

        /**
         * Reads a document from [path].
         *
         * Every rejection is reported as an [IllegalArgumentException] naming [path], because the
         * caller is a command line option and the path is the only context an operator has.
         */
        fun load(path: Path): TsaCvc5ConfigurationDocument {
            require(path.isRegularFile()) { "cvc5 configuration does not exist: $path" }
            return try {
                Json.decodeFromString(serializer(), path.readText())
            } catch (failure: Exception) {
                throw IllegalArgumentException("Invalid cvc5 configuration $path: ${failure.message}", failure)
            }
        }
    }
}
