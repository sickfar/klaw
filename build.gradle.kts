plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.micronaut.application) apply false
    alias(libs.plugins.micronaut.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

allprojects {
    group = "io.github.klaw"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    ktlint {
        version.set("1.8.0")
    }

    detekt {
        buildUponDefaultConfig = true
        autoCorrect = false
    }
}
