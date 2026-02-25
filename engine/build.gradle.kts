plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.micronaut.application)
}

apply(plugin = "org.jetbrains.kotlin.kapt")

val micronautVersion =
    libs.versions.micronaut.platform
        .get()

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")

    "kapt"("io.micronaut:micronaut-inject-java:$micronautVersion")
    "kaptTest"("io.micronaut:micronaut-inject-java:$micronautVersion")

    testImplementation(libs.mockk)
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
