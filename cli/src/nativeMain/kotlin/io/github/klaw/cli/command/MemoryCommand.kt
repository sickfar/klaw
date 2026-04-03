package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class MemoryCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "memory") {
    val agent by option("--agent", "-a", help = "Agent ID").default("default")

    init {
        subcommands(
            MemorySearchCommand(requestFn),
            MemoryCategoriesCommand(requestFn),
            MemoryFactsCommand(requestFn),
            MemoryConsolidateCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class MemorySearchCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "search") {
    private val query by argument()
    private val agentId: String get() = (currentContext.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "memory search" }
        try {
            echo(requestFn("memory_search", mapOf("query" to query), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryCategoriesCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "categories") {
    init {
        subcommands(
            MemoryCategoriesListCommand(requestFn),
            MemoryCategoriesRenameCommand(requestFn),
            MemoryCategoriesMergeCommand(requestFn),
            MemoryCategoriesDeleteCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class MemoryCategoriesListCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "list") {
    private val json by option("--json").flag(default = false)
    private val agentId: String
        get() = (currentContext.parent?.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        val params = if (json) mapOf("json" to "true") else emptyMap()
        try {
            echo(requestFn("memory_categories_list", params, agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryCategoriesRenameCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "rename") {
    private val oldName by argument("old-name")
    private val newName by argument("new-name")
    private val agentId: String
        get() = (currentContext.parent?.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        try {
            echo(requestFn("memory_categories_rename", mapOf("old_name" to oldName, "new_name" to newName), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryCategoriesMergeCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "merge") {
    private val sources by argument("sources", help = "Comma-separated source category names")
    private val target by argument("target", help = "Target category name")
    private val agentId: String
        get() = (currentContext.parent?.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        try {
            echo(requestFn("memory_categories_merge", mapOf("sources" to sources, "target" to target), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryCategoriesDeleteCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "delete") {
    private val name by argument()
    private val keepFacts by option("--keep-facts").flag(default = false)
    private val agentId: String
        get() = (currentContext.parent?.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        try {
            echo(
                requestFn(
                    "memory_categories_delete",
                    mapOf("name" to name, "keep_facts" to keepFacts.toString()),
                    agentId,
                ),
            )
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryFactsCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "facts") {
    init {
        subcommands(
            MemoryFactsListCommand(requestFn),
            MemoryFactsAddCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class MemoryFactsListCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "list") {
    private val category by argument()
    private val agentId: String
        get() = (currentContext.parent?.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        try {
            echo(requestFn("memory_facts_list", mapOf("category" to category), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryFactsAddCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "add") {
    private val category by argument()
    private val content by argument()
    private val agentId: String
        get() = (currentContext.parent?.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        try {
            echo(requestFn("memory_facts_add", mapOf("category" to category, "content" to content), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class MemoryConsolidateCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "consolidate") {
    private val date by option("--date", help = "Date to consolidate (YYYY-MM-DD, default: yesterday)")
    private val force by option("--force", help = "Force re-consolidation even if already done").flag(default = false)
    private val agentId: String get() = (currentContext.parent?.command as? MemoryCommand)?.agent ?: "default"

    override fun run() {
        val params = mutableMapOf<String, String>()
        date?.let { params["date"] = it }
        if (force) params["force"] = "true"
        try {
            echo(requestFn("memory_consolidate", params, agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}
