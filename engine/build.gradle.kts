import org.gradle.language.jvm.tasks.ProcessResources
import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.micronaut.application)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.shadow)
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
    implementation(libs.quartz)

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

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
}

ktlint {
    filter {
        exclude("**/build/generated/**")
    }
}

val sqliteVecVersion = "0.1.6"

val downloadSqliteVec by tasks.registering {
    description = "Downloads sqlite-vec loadable extension for the current (or overridden) platform"

    val nativeDir = file("src/main/resources/native")
    val outputFile = File(nativeDir, "vec0")
    outputs.file(outputFile)

    // Allow platform override for cross-compilation (e.g., -PsqliteVecPlatform=linux-aarch64)
    val platformProp = providers.gradleProperty("sqliteVecPlatform")
    inputs.property("sqliteVecPlatform", platformProp.orElse("auto"))

    doLast {
        val platform =
            platformProp.orNull ?: run {
                val osName = System.getProperty("os.name").lowercase()
                val archName = System.getProperty("os.arch").lowercase()
                val os =
                    when {
                        "mac" in osName || "darwin" in osName -> "macos"
                        "linux" in osName -> "linux"
                        else -> error("Unsupported OS: $osName")
                    }
                val arch =
                    when {
                        archName == "aarch64" || archName == "arm64" -> "aarch64"
                        archName == "amd64" || archName == "x86_64" -> "x86_64"
                        else -> error("Unsupported architecture: $archName")
                    }
                "$os-$arch"
            }

        val archiveName = "sqlite-vec-$sqliteVecVersion-loadable-$platform.tar.gz"
        val url = "https://github.com/asg017/sqlite-vec/releases/download/v$sqliteVecVersion/$archiveName"

        logger.lifecycle("Downloading sqlite-vec $sqliteVecVersion for $platform...")

        nativeDir.mkdirs()
        val tempDir = temporaryDir
        val tempArchive = File(tempDir, archiveName)
        URI(url).toURL().openStream().use { input ->
            tempArchive.outputStream().use { output -> input.copyTo(output) }
        }

        // Extract the vec0 binary (vec0.so or vec0.dylib) from the tar.gz
        val exitCode =
            ProcessBuilder("tar", "xzf", tempArchive.absolutePath, "-C", tempDir.absolutePath)
                .start()
                .waitFor()
        if (exitCode != 0) error("tar extraction failed with exit code $exitCode")

        // Find the extracted vec0 file (vec0.so on Linux, vec0.dylib on macOS)
        val extracted =
            tempDir.listFiles()?.firstOrNull { it.name.startsWith("vec0.") }
                ?: error("vec0 binary not found after extracting $archiveName")
        extracted.copyTo(outputFile, overwrite = true)
        outputFile.setExecutable(true)
        logger.lifecycle("Extracted ${extracted.name} -> ${outputFile.path}")
    }
}

val generateDocsIndex by tasks.registering {
    val docDir = rootProject.file("doc")
    val indexFile =
        layout.buildDirectory
            .file("generated/klaw-docs/doc-index.txt")
            .get()
            .asFile
    inputs.dir(docDir)
    outputs.file(indexFile)
    doLast {
        val paths =
            docDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .map { it.relativeTo(docDir).path.replace(File.separatorChar, '/') }
                .sorted()
                .toList()
        indexFile.parentFile.mkdirs()
        indexFile.writeText(paths.joinToString("\n"))
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateDocsIndex)
    dependsOn(downloadSqliteVec)
    from(rootProject.file("doc")) { into("klaw-docs") }
    from(layout.buildDirectory.dir("generated/klaw-docs")) { into("klaw-docs") }
}

// Micronaut's inspectRuntimeClasspath reads resources — needs downloadSqliteVec to run first
tasks.matching { it.name == "inspectRuntimeClasspath" }.configureEach {
    dependsOn(downloadSqliteVec)
}
