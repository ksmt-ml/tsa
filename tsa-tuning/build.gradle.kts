plugins {
    id("tsa.kotlin-conventions")
    id("org.jmailen.kotlinter") version Versions.kotlinterPluginVersion
}

dependencies {
    api(project(":tsa-core"))
    api(project(":tsa-tuning-api"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.kotlinx_serialization}")
}

tasks.register("formatAndLintAll") {
    group = "formatting"

    dependsOn(tasks.findByName("formatKotlin"))
    dependsOn(tasks.findByName("lintKotlin"))
}
