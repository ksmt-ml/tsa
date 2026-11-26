plugins {
    id("tsa.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    id("org.jmailen.kotlinter") version Versions.kotlinterPluginVersion
}

val tuningEnabled = providers.gradleProperty("useLocalTuningContracts").orNull == "true"
if (tuningEnabled) {
    kotlin.sourceSets.named("main") { kotlin.srcDir("src/tuning/kotlin") }
    kotlin.sourceSets.named("test") { kotlin.srcDir("src/tuningTest/kotlin") }
}

dependencies {
    // these are used in TvmSymbolicTest which is then exported to Test module
    api(group = Packages.tvmDisasm, name = "tvm-disasm", version = Versions.tvmDisasm)
    api(group = Packages.tvmDisasm, name = "tvm-opcodes", version = Versions.tvmDisasm)

    implementation(group = Packages.tonKotlin, name = "ton-kotlin-crypto", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-tvm", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-tonapi-tl", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-tlb", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-tl", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-hashmap-tlb", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-contract", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-block-tlb", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-bitstring", version = Versions.tonKotlin)
    implementation(group = Packages.tonKotlin, name = "ton-kotlin-bigint", version = Versions.tonKotlin)

    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:${Versions.collections}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.kotlinx_serialization}")

    testImplementation("ch.qos.logback:logback-classic:${Versions.logback}")

    api(group = Packages.ksmt, name = "ksmt-core", version = Versions.ksmt)
    if (tuningEnabled) {
        api(group = Packages.ksmt, name = "ksmt-profiling", version = Versions.ksmtTuningContracts)
        implementation(group = Packages.ksmt, name = "ksmt-runner", version = Versions.ksmtTuningContracts)
        implementation(group = Packages.ksmt, name = "ksmt-cvc5", version = Versions.ksmtTuningContracts)
        api("io.ksmt:ksmt-ml-tuner:${Versions.ksmtMlTuner}")
    }
    implementation(group = Packages.ksmtBv2Int, name = "ksmt-bv2int", version = Versions.ksmtBv2Int)
    implementation(group = Packages.ksmt, name = "ksmt-yices", version = Versions.ksmt)
    implementation(group = Packages.ksmt, name = "ksmt-z3", version = Versions.ksmt)
    implementation("com.charleskorn.kaml:kaml:${Versions.kaml}")

    // todo: remove ksmt-core exclude after upgrading ksmt version in USVM
    api(group = Packages.usvm, name = "usvm-core", version = Versions.usvm) {
        exclude(group = "io.ksmt")
    }
}

val pathToSpec = File(rootProject.projectDir, "tvm-spec/cp0.json")

tasks.processResources {
    from(pathToSpec)
    if (tuningEnabled) {
        from("schemas") { into("org/usvm/machine/schemas") }
    }
}

tasks.register("formatAndLintAll") {
    group = "formatting"

    dependsOn(tasks.findByName("formatKotlin"))
    dependsOn(tasks.findByName("lintKotlin"))
}
