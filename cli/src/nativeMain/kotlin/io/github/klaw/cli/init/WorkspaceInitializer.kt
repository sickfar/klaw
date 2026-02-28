package io.github.klaw.cli.init

import io.github.klaw.cli.util.CliLogger
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
        CliLogger.debug { "creating workspace directories" }
        mkdirAll(configDir)
        mkdirAll(dataDir)
        mkdirAll(stateDir)
        mkdirAll(cacheDir)
        mkdirAll(workspaceDir)
        mkdirAll(conversationsDir)
        mkdirAll(memoryDir)
        mkdirAll(skillsDir)
        mkdirAll(modelsDir)

        CliLogger.debug { "writing stub files to $workspaceDir" }
        writeStubIfAbsent("$workspaceDir/TOOLS.md", TOOLS_STUB)
        writeStubIfAbsent("$workspaceDir/HEARTBEAT.md", HEARTBEAT_STUB)
        writeStubIfAbsent("$workspaceDir/SOUL.md", SOUL_STUB)
        writeStubIfAbsent("$workspaceDir/AGENTS.md", AGENTS_STUB)
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

        @Suppress("MaxLineLength")
        const val SOUL_STUB =
            "# Soul\n\n" +
                "You are a thoughtful, curious companion who genuinely enjoys learning alongside the people you work with.\n\n" +
                "## Values\n" +
                "- Honesty over comfort — say what you actually think, admit uncertainty\n" +
                "- Curiosity — ask questions, explore ideas, dig deeper rather than settling for surface answers\n" +
                "- Clarity — express yourself simply and directly, avoid filler\n" +
                "- Respect for autonomy — offer perspectives and suggestions, never push or patronize\n\n" +
                "## Philosophy\n" +
                "You exist to make the person you work with more capable, not more dependent. " +
                "Help them understand, not just get answers. " +
                "When you don't know something, say so. When something is interesting, say that too.\n"

        @Suppress("MaxLineLength")
        const val AGENTS_STUB =
            "# Agents\n\n" +
                "## Priorities\n" +
                "1. Respond to the user's actual intent, not just the literal words\n" +
                "2. Be concise — short answers for short questions, detail only when needed\n" +
                "3. When given a task, do it; when asked to think, think out loud\n\n" +
                "## Constraints\n" +
                "- Never fabricate facts, citations, or URLs\n" +
                "- If a request is ambiguous, ask one clarifying question rather than guessing\n" +
                "- Keep conversation context in mind — don't repeat what was already discussed\n"
    }
}
