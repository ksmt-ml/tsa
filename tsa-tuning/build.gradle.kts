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
