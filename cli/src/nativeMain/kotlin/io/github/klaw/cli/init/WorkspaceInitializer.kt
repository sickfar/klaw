package io.github.klaw.cli.init

import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.paths.KlawPaths

internal class WorkspaceInitializer(
    private val configDir: String = KlawPaths.config,
    private val dataDir: String = KlawPaths.data,
    private val stateDir: String = KlawPaths.state,
    private val cacheDir: String = KlawPaths.cache,
    private val workspaceDir: String = KlawPaths.workspace,
    private val conversationsDir: String = KlawPaths.conversations,
    private val memoryDir: String = KlawPaths.memory,
    private val skillsDir: String = KlawPaths.skills,
    private val modelsDir: String = KlawPaths.models,
) {
    fun initialize() {
        mkdirAll(configDir)
        mkdirAll(dataDir)
        mkdirAll(stateDir)
        mkdirAll(cacheDir)
        mkdirAll(workspaceDir)
        mkdirAll(conversationsDir)
        mkdirAll(memoryDir)
        mkdirAll(skillsDir)
        mkdirAll(modelsDir)

        writeStubIfAbsent("$workspaceDir/TOOLS.md", TOOLS_STUB)
        writeStubIfAbsent("$workspaceDir/HEARTBEAT.md", HEARTBEAT_STUB)
    }

    private fun mkdirAll(path: String) {
        if (!fileExists(path)) {
            // Create parent dirs recursively
            val parent = path.substringBeforeLast('/')
            if (parent.isNotEmpty() && parent != path) mkdirAll(parent)
            mkdirMode755(path)
        }
    }

    private fun writeStubIfAbsent(
        path: String,
        content: String,
    ) {
        if (!fileExists(path)) {
            writeFileText(path, content)
        }
    }

    private companion object {
        const val TOOLS_STUB = "# Tools\n\nList available tools here.\n"
        const val HEARTBEAT_STUB = "# Heartbeat\n\nThis file is updated by the scheduler.\n"
    }
}
