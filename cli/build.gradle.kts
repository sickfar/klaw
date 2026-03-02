plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val generateBuildConfig by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig/nativeMain/io/github/klaw/cli")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val version = rootProject.version.toString()
        dir.resolve("BuildConfig.kt").writeText(
            """
            |package io.github.klaw.cli
            |
            |object BuildConfig {
            |    const val VERSION: String = "$version"
            |    const val GITHUB_OWNER: String = "sickfar"
            |    const val GITHUB_REPO: String = "klaw"
            |}
            """.trimMargin() + "\n",
        )
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
    dependsOn(generateBuildConfig)
}

kotlin {
    linuxArm64 {
        binaries {
            executable {
                baseName = "klaw"
                entryPoint = "io.github.klaw.cli.main"
            }
        }
    }
    linuxX64 {
        binaries {
            executable {
                baseName = "klaw"
                entryPoint = "io.github.klaw.cli.main"
            }
        }
    }
    macosArm64 {
        binaries {
            executable {
                baseName = "klaw"
                entryPoint = "io.github.klaw.cli.main"
            }
        }
    }
    macosX64 {
        binaries {
            executable {
                baseName = "klaw"
                entryPoint = "io.github.klaw.cli.main"
            }
        }
    }

    sourceSets {
        nativeMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/buildconfig/nativeMain"))
        }
        nativeMain.dependencies {
            implementation(project(":common"))
            implementation(libs.clikt)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        nativeTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
