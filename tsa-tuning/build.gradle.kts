plugins {
    id("tsa.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("org.jmailen.kotlinter") version Versions.kotlinterPluginVersion
}

dependencies {
    api(project(":tsa-core"))
    api(project(":tsa-tuning-api"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.kotlinx_serialization}")
}

tasks.processResources {
    from("schemas") {
        into("org/ton/tuning/schemas")
    }
}

tasks.register("formatAndLintAll") {
    group = "formatting"

    dependsOn(tasks.findByName("formatKotlin"))
    dependsOn(tasks.findByName("lintKotlin"))
}

// End-to-end MVP harness: captures profiles from a real BoC contract, replays them, and tunes
// cvc5 options. It lives in the test source set because it is a verification tool, not production
// code, and it is opt-in through this task.
val mvpTestRuntimeClasspath = extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath

tasks.register<JavaExec>("runTuningMvp") {
    group = "verification"
    description = "Captures profiles from a real BoC contract and tunes cvc5 options end to end."

    classpath = mvpTestRuntimeClasspath
    mainClass.set("org.ton.tuning.mvp.TsaTuningMvpKt")

    listOf(
        "contract" to "tsa.mvp.contract",
        "workDir" to "tsa.mvp.workDir",
        "candidates" to "tsa.mvp.candidates",
        "methodTimeoutSeconds" to "tsa.mvp.methodTimeoutSeconds",
        "solverTimeoutSeconds" to "tsa.mvp.solverTimeoutSeconds",
        "trialTimeoutSeconds" to "tsa.mvp.trialTimeoutSeconds",
        "seed" to "tsa.mvp.seed",
        "targetKind" to "tsa.mvp.targetKind",
        "iterationLimit" to "tsa.mvp.iterationLimit",
        "maxRecursionDepth" to "tsa.mvp.maxRecursionDepth",
    ).forEach { (propertyName, systemKey) ->
        providers.gradleProperty(propertyName).orNull?.let { systemProperty(systemKey, it) }
    }
}
