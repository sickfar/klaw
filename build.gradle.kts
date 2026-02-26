plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.micronaut.application) apply false
    alias(libs.plugins.micronaut.library) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.shadow) apply false
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

tasks.register("assembleDist") {
    group = "distribution"
    description = "Assembles all deployable artifacts into build/dist/"

    dependsOn(":gateway:shadowJar", ":engine:shadowJar")

    val os =
        org.gradle.internal.os.OperatingSystem
            .current()
    when {
        os.isLinux ->
            dependsOn(
                ":cli:linkReleaseExecutableLinuxX64",
                ":cli:linkReleaseExecutableLinuxArm64",
            )
        os.isMacOsX ->
            dependsOn(
                ":cli:linkReleaseExecutableMacosArm64",
                ":cli:linkReleaseExecutableMacosX64",
            )
    }

    doLast {
        val distDir =
            rootProject.layout.buildDirectory
                .dir("dist")
                .get()
                .asFile
        distDir.mkdirs()

        val ver = rootProject.version.toString()

        // Fat JARs
        fun copyJar(
            module: String,
            artifactName: String,
        ) {
            val jar =
                project(":$module")
                    .tasks
                    .named("shadowJar", com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class.java)
                    .get()
                    .archiveFile
                    .get()
                    .asFile
            jar.copyTo(File(distDir, "klaw-$artifactName-$ver.jar"), overwrite = true)
        }
        copyJar("gateway", "gateway")
        copyJar("engine", "engine")

        // Native CLI binaries (host-platform only)
        val cliBuildDir =
            project(":cli")
                .layout.buildDirectory
                .get()
                .asFile
        val targets =
            when {
                os.isLinux -> listOf("linuxX64", "linuxArm64")
                os.isMacOsX -> listOf("macosArm64", "macosX64")
                else -> emptyList()
            }
        for (target in targets) {
            val binary = File(cliBuildDir, "bin/$target/releaseExecutable/klaw.kexe")
            if (binary.exists()) {
                val dest = File(distDir, "klaw-$target")
                binary.copyTo(dest, overwrite = true)
                dest.setExecutable(true)
            }
        }

        println("Artifacts in ${distDir.relativeTo(rootProject.projectDir)}/")
        distDir.listFiles()?.sorted()?.forEach { println("  ${it.name}") }
    }
}

// Separate task for macOS-only CLI binaries — used by release.yml macOS job
// to avoid redundantly rebuilding JARs that the Linux job already produces.
tasks.register("assembleCliMacos") {
    group = "distribution"
    description = "Assembles macOS CLI binaries into build/dist/ (no JARs)"

    dependsOn(
        ":cli:linkReleaseExecutableMacosArm64",
        ":cli:linkReleaseExecutableMacosX64",
    )

    doLast {
        val distDir =
            rootProject.layout.buildDirectory
                .dir("dist")
                .get()
                .asFile
        distDir.mkdirs()
        val cliBuildDir =
            project(":cli")
                .layout.buildDirectory
                .get()
                .asFile
        for (target in listOf("macosArm64", "macosX64")) {
            val binary = File(cliBuildDir, "bin/$target/releaseExecutable/klaw.kexe")
            if (binary.exists()) {
                val dest = File(distDir, "klaw-$target")
                binary.copyTo(dest, overwrite = true)
                dest.setExecutable(true)
            }
        }
    }
}
