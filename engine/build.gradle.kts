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

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")

    "kapt"("io.micronaut:micronaut-inject-java:$micronautVersion")
    "kaptTest"("io.micronaut:micronaut-inject-java:$micronautVersion")
    "kaptIntegrationTest"("io.micronaut:micronaut-inject-java:$micronautVersion")

    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)
    implementation("app.cash.sqldelight:sqlite-driver:${libs.versions.sqldelight.get()}")
    implementation(libs.quartz)

    implementation(libs.onnxruntime)
    implementation(libs.djl.tokenizers)
    implementation(libs.jsoup)
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
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}

ktlint {
    filter {
        exclude("**/build/generated/**")
    }
}

val sqliteVecVersion = "0.1.7-alpha.10"

val downloadSqliteVec by tasks.registering {
    description = "Downloads sqlite-vec loadable extension for the current platform and linux-aarch64 (Docker)"

    val nativeDir = file("src/main/resources/native")
    outputs.dir(nativeDir)

    doLast {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val hostOs =
            when {
                "mac" in osName || "darwin" in osName -> "macos"
                "linux" in osName -> "linux"
                else -> error("Unsupported OS: $osName")
            }
        val hostArch =
            when {
                archName == "aarch64" || archName == "arm64" -> "aarch64"
                archName == "amd64" || archName == "x86_64" -> "x86_64"
                else -> error("Unsupported architecture: $archName")
            }
        val hostPlatform = "$hostOs-$hostArch"

        // Download both host platform and linux-aarch64 (Docker target)
        val platforms = setOf(hostPlatform, "linux-aarch64")
        nativeDir.mkdirs()

        for (platform in platforms) {
            val suffix = if (platform.startsWith("macos")) ".dylib" else ".so"
            val outputFile = File(nativeDir, "vec0$suffix")
            if (outputFile.exists()) {
                logger.lifecycle("sqlite-vec $platform already present: ${outputFile.name}")
                continue
            }

            val archiveName = "sqlite-vec-$sqliteVecVersion-loadable-$platform.tar.gz"
            val url = "https://github.com/asg017/sqlite-vec/releases/download/v$sqliteVecVersion/$archiveName"
            logger.lifecycle("Downloading sqlite-vec $sqliteVecVersion for $platform...")

            val tempDir = File(temporaryDir, platform)
            tempDir.mkdirs()
            val tempArchive = File(tempDir, archiveName)
            URI(url).toURL().openStream().use { input ->
                tempArchive.outputStream().use { output -> input.copyTo(output) }
            }

            val exitCode =
                ProcessBuilder("tar", "xzf", tempArchive.absolutePath, "-C", tempDir.absolutePath)
                    .start()
                    .waitFor()
            if (exitCode != 0) error("tar extraction failed with exit code $exitCode")

            val extracted =
                tempDir.listFiles()?.firstOrNull { it.name.startsWith("vec0.") }
                    ?: error("vec0 binary not found after extracting $archiveName")
            extracted.copyTo(outputFile, overwrite = true)
            outputFile.setExecutable(true)
            logger.lifecycle("Extracted ${extracted.name} -> ${outputFile.name}")
        }
    }
}

val generateSkillsIndex by tasks.registering {
    val skillsDir = file("src/main/resources/klaw-skills")
    val indexFile =
        layout.buildDirectory
            .file("generated/klaw-skills/skills-index.txt")
            .get()
            .asFile
    inputs.dir(skillsDir)
    outputs.file(indexFile)
    doLast {
        val paths =
            skillsDir
                .walkTopDown()
                .filter { it.isDirectory && it.resolve("SKILL.md").exists() }
                .map { it.relativeTo(skillsDir).path.replace(File.separatorChar, '/') }
                .sorted()
                .toList()
        indexFile.parentFile.mkdirs()
        indexFile.writeText(paths.joinToString("\n"))
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
    dependsOn(generateSkillsIndex)
    dependsOn(downloadSqliteVec)
    from(rootProject.file("doc")) { into("klaw-docs") }
    from(layout.buildDirectory.dir("generated/klaw-docs")) { into("klaw-docs") }
    from(layout.buildDirectory.dir("generated/klaw-skills")) { into("klaw-skills") }
}

// Micronaut's inspectRuntimeClasspath reads resources — needs downloadSqliteVec to run first
tasks.matching { it.name == "inspectRuntimeClasspath" }.configureEach {
    dependsOn(downloadSqliteVec)
}
