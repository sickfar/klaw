package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.common.paths.KlawPaths

internal class DoctorCommand(
    private val configDir: String = KlawPaths.config,
    private val engineSocketPath: String = KlawPaths.engineSocket,
    private val modelsDir: String = KlawPaths.models,
) : CliktCommand(name = "doctor") {
    override fun run() {
        val gatewayYaml = "$configDir/gateway.yaml"
        val engineYaml = "$configDir/engine.yaml"

        // Config files
        if (fileExists(gatewayYaml)) {
            echo("✓ gateway.yaml: found")
        } else {
            echo("✗ gateway.yaml: missing ($gatewayYaml)")
        }

        if (fileExists(engineYaml)) {
            echo("✓ engine.yaml: found")
        } else {
            echo("✗ engine.yaml: missing ($engineYaml)")
        }

        // Engine socket
        if (fileExists(engineSocketPath)) {
            echo("✓ Engine: running")
        } else {
            echo("✗ Engine: stopped (socket not found at $engineSocketPath)")
        }

        // ONNX models
        val onnxFiles =
            if (fileExists(modelsDir) && isDirectory(modelsDir)) {
                listDirectory(modelsDir).filter { it.endsWith(".onnx") }
            } else {
                emptyList()
            }
        if (onnxFiles.isNotEmpty()) {
            echo("✓ ONNX model: ${onnxFiles.size} .onnx file(s) found in $modelsDir")
        } else {
            echo("✗ ONNX: no .onnx files in models directory ($modelsDir)")
        }
    }
}
