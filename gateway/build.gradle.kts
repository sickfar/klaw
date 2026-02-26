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
    implementation(libs.kotlinx.datetime)
    implementation(libs.tgbotapi)
    implementation(libs.kaml)

    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation(libs.kotlin.logging)

    "kapt"("io.micronaut:micronaut-inject-java:$micronautVersion")
    "kaptTest"("io.micronaut:micronaut-inject-java:$micronautVersion")

    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

application {
    mainClass.set("io.github.klaw.gateway.Application")
}

micronaut {
    version(micronautVersion)
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.github.klaw.gateway.*")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}
