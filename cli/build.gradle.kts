plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "io.github.klaw.cli.main"
            }
        }
    }
    linuxX64 {
        binaries {
            executable {
                entryPoint = "io.github.klaw.cli.main"
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":common"))
            implementation(libs.clikt)
        }
    }
}
