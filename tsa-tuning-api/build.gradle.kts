plugins {
    id("tsa.kotlin-conventions")
    id("org.jmailen.kotlinter") version Versions.kotlinterPluginVersion
}

dependencies {
    api(group = Packages.ksmt, name = "ksmt-profiling", version = Versions.ksmtTuningContracts)
    api(group = Packages.ksmt, name = "ksmt-ml-tuner", version = Versions.ksmtMlTuner)
}

tasks.register("formatAndLintAll") {
    group = "formatting"

    dependsOn(tasks.findByName("formatKotlin"))
    dependsOn(tasks.findByName("lintKotlin"))
}
