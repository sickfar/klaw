plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val generateModelRegistryNative by tasks.registering {
    description = "Generate ModelRegistryNative.kt from model-registry.json for native targets"
    group = "generation"
    val jsonFile = file("src/commonMain/resources/model-registry.json")
    val outputDir = layout.buildDirectory.dir("generated/native-registry")
    inputs.file(jsonFile)
    outputs.dir(outputDir)
    doLast {
        val json =
            jsonFile
                .readText()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        val dir = outputDir.get().asFile.resolve("io/github/klaw/common/registry")
        dir.mkdirs()
        dir.resolve("ModelRegistryNative.kt").writeText(
            """
            |package io.github.klaw.common.registry
            |
            |internal actual fun loadRegistryJson(): String =
            |    "$json"
            |
            """.trimMargin(),
        )
    }
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
        nativeMain {
            kotlin.srcDir(generateModelRegistryNative.map { it.outputs.files.singleFile })
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
    val generatedDir =
        layout.buildDirectory
            .dir("generated/schemas")
            .get()
            .asFile
    val kotlinDir = file("src/commonMain/kotlin/io/github/klaw/common/config/schema")
    args = listOf(generatedDir.absolutePath, kotlinDir.absolutePath)
}
