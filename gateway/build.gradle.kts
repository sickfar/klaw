plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.shadow)
}

apply(plugin = "org.jetbrains.kotlin.kapt")

val micronautVersion =
    libs.versions.micronaut.platform
        .get()

dependencies {
    implementation(project(":common"))
    implementation(project(":webui"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.tgbotapi)
    implementation(libs.kord.core)

    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("org.slf4j:jul-to-slf4j")
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlin.logging)

    "kapt"("io.micronaut:micronaut-inject-java:$micronautVersion")
    "kaptTest"("io.micronaut:micronaut-inject-java:$micronautVersion")

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
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
