plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
