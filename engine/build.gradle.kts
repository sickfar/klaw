plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.sqldelight)
}

apply(plugin = "org.jetbrains.kotlin.kapt")

val micronautVersion =
    libs.versions.micronaut.platform
        .get()

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")

    "kapt"("io.micronaut:micronaut-inject-java:$micronautVersion")
    "kaptTest"("io.micronaut:micronaut-inject-java:$micronautVersion")

    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)
    implementation("app.cash.sqldelight:sqlite-driver:${libs.versions.sqldelight.get()}")

    implementation(libs.onnxruntime)
    implementation(libs.djl.tokenizers)
    implementation(libs.kotlin.logging)

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.wiremock)
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

application {
    mainClass.set("io.github.klaw.engine.Application")
}

micronaut {
    version(micronautVersion)
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.github.klaw.engine.*")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

sqldelight {
    databases {
        create("KlawDatabase") {
            packageName.set("io.github.klaw.engine.db")
        }
    }
}

ktlint {
    filter {
        exclude("**/build/generated/**")
    }
}
