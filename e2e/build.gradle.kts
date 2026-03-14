plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations["implementation"])
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations["runtimeOnly"])
}

dependencies {
    // Infrastructure library (main source set)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.testcontainers)
    implementation(libs.wiremock)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlin.logging)
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // Unit tests for infra (test source set)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Integration tests (integrationTest source set) — JUnit via extends
    integrationTestImplementation(platform("org.junit:junit-bom:5.12.2"))
    integrationTestImplementation("org.junit.jupiter:junit-jupiter-api")
    integrationTestImplementation("org.awaitility:awaitility-kotlin:4.3.0")
    integrationTestRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    description = "Runs infrastructure unit tests (no Docker required)."
}

tasks.register<Test>("integrationTest") {
    description = "Runs e2e tests with Docker containers (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
}
