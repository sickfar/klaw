plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    linuxArm64()
    linuxX64()
    macosArm64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.jtokkit)
            }
        }
    }
}

val generateSchemas by tasks.registering(JavaExec::class) {
    description = "Regenerate schema JSON files and GeneratedSchemas.kt from config data classes"
    group = "generation"
    dependsOn("compileKotlinJvm")
    val jvmMain = kotlin.jvm().compilations["main"]
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles!!
    mainClass.set("io.github.klaw.common.config.schema.SchemaGeneratorMainKt")
    val schemaDir = rootProject.file("doc/config")
    val kotlinDir = file("src/commonMain/kotlin/io/github/klaw/common/config/schema")
    args = listOf(schemaDir.absolutePath, kotlinDir.absolutePath)
}
