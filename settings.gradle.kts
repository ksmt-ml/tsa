rootProject.name = "tsa"

when (val useLocalTuningContracts = providers.gradleProperty("useLocalTuningContracts").orNull) {
    null, "false" -> Unit
    "true" -> {
        val localKsmt = file("../ksmt")
        val localTuner = file("../ksmt-ml-tuner")
        require(localKsmt.isDirectory && localTuner.isDirectory) {
            "Local KSMT and ksmt-ml-tuner checkouts must exist next to TSA"
        }
        includeBuild(localKsmt)
        includeBuild(localTuner)
    }
    else -> error(
        "Gradle property useLocalTuningContracts must be either true or false, " +
            "got: $useLocalTuningContracts",
    )
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include("tsa-core")
include("tsa-tuning-api")
include("tsa-cli")
include("tsa-sarif")
include("tsa-test-gen")
include("tsa-jettons")
include("tsa-test")
include("tsa-metrics")

// TODO: fix this module (https://github.com/explyt/tsa/issues/118)
// include("tsa-intellij")
include("tsa-networking")
include("tsa-safety-properties-examples")
