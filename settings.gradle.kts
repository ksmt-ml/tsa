rootProject.name = "tsa"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// TSA's cvc5 tuning integration is built together with the sibling `ksmt` and
// `ksmt-ml-tuner` checkouts, which provide `io.ksmt:ksmt-profiling` and
// `io.ksmt:ksmt-ml-tuner`. Neither artifact is published to a public repository
// yet, so both repositories must be checked out next to the TSA repository.
val localKsmt = file("../ksmt")
val localTuner = file("../ksmt-ml-tuner")
require(localKsmt.isDirectory && localTuner.isDirectory) {
    "TSA requires local `ksmt` and `ksmt-ml-tuner` checkouts next to the TSA repository. " +
        "Expected directories: ${localKsmt.absolutePath} and ${localTuner.absolutePath}"
}
includeBuild(localKsmt)
includeBuild(localTuner)

include("tsa-core")
include("tsa-cli")
include("tsa-sarif")
include("tsa-test-gen")
include("tsa-jettons")
include("tsa-test")
include("tsa-metrics")
include("tsa-tuning-api")
include("tsa-tuning")

// TODO: fix this module (https://github.com/explyt/tsa/issues/118)
// include("tsa-intellij")
include("tsa-networking")
include("tsa-safety-properties-examples")
