package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.migration.OpenClawCronConverter

internal class ScheduleCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "schedule") {
    val agent by option("--agent", "-a", help = "Agent ID").default("default")

    init {
        subcommands(
            ScheduleListCommand(requestFn),
            ScheduleAddCommand(requestFn),
            ScheduleRemoveCommand(requestFn),
            ScheduleEditCommand(requestFn),
            ScheduleEnableCommand(requestFn),
            ScheduleDisableCommand(requestFn),
            ScheduleRunCommand(requestFn),
            ScheduleRunsCommand(requestFn),
            ScheduleStatusCommand(requestFn),
            ScheduleImportCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class ScheduleListCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "list") {
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule list" }
        try {
            echo(requestFn("schedule_list", emptyMap(), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleAddCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "add") {
    private val name by argument()
    private val cron by argument()
    private val message by argument()
    private val model by option("--model")
    private val injectInto by option("--inject-into")
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule add name=$name" }
        try {
            val params =
                buildMap {
                    put("name", name)
                    put("cron", cron)
                    put("message", message)
                    model?.let { put("model", it) }
                    injectInto?.let { put("inject_into", it) }
                }
            echo(requestFn("schedule_add", params, agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleRemoveCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "remove") {
    private val name by argument()
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule remove name=$name" }
        try {
            echo(requestFn("schedule_remove", mapOf("name" to name), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleEditCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "edit") {
    private val name by argument()
    private val cron by option("--cron")
    private val message by option("--message")
    private val model by option("--model")
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule edit name=$name" }
        try {
            val params =
                buildMap {
                    put("name", name)
                    cron?.let { put("cron", it) }
                    message?.let { put("message", it) }
                    model?.let { put("model", it) }
                }
            echo(requestFn("schedule_edit", params, agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleEnableCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "enable") {
    private val name by argument()
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule enable name=$name" }
        try {
            echo(requestFn("schedule_enable", mapOf("name" to name), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleDisableCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "disable") {
    private val name by argument()
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule disable name=$name" }
        try {
            echo(requestFn("schedule_disable", mapOf("name" to name), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleRunCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "run") {
    private val name by argument()
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule run name=$name" }
        try {
            echo(requestFn("schedule_run", mapOf("name" to name), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleRunsCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "runs") {
    private val name by argument()
    private val limit by option("--limit").int().default(DEFAULT_RUNS_LIMIT)
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    companion object {
        private const val DEFAULT_RUNS_LIMIT = 20
    }

    override fun run() {
        CliLogger.debug { "schedule runs name=$name limit=$limit" }
        try {
            echo(requestFn("schedule_runs", mapOf("name" to name, "limit" to limit.toString()), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleStatusCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "status") {
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        CliLogger.debug { "schedule status" }
        try {
            echo(requestFn("schedule_status", emptyMap(), agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleImportCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "import") {
    private val fromOpenclaw by option("--from-openclaw", help = "Path to OpenClaw jobs.json file")
    private val all by option("--all", help = "Import all jobs including disabled").flag()
    private val agentId: String get() = (currentContext.parent?.command as? ScheduleCommand)?.agent ?: "default"

    override fun run() {
        val path = fromOpenclaw
        if (path == null) {
            echo("Error: --from-openclaw is required")
            return
        }

        CliLogger.debug { "schedule import from-openclaw=$path all=$all" }

        val content = readFileText(path)
        if (content == null) {
            echo("Error: cannot read file: $path")
            return
        }

        val jobs =
            try {
                OpenClawCronConverter.parseJobs(content, includeDisabled = all)
            } catch (e: kotlinx.serialization.SerializationException) {
                echo("Error: failed to parse jobs.json: ${e::class.simpleName}")
                return
            } catch (e: IllegalArgumentException) {
                echo("Error: failed to parse jobs.json: ${e::class.simpleName}")
                return
            }

        if (jobs.isEmpty()) {
            echo(
                "No jobs to import (${if (all) "file is empty" else "no enabled jobs, use --all to include disabled"})",
            )
            return
        }

        echo("Found ${jobs.size} job(s) to import:")
        val (imported, failed) = importJobs(jobs)
        echo("")
        echo("Import complete: $imported imported, $failed failed, ${jobs.size} total")
    }

    private fun importJobs(jobs: List<io.github.klaw.common.migration.OpenClawJob>): Pair<Int, Int> {
        var imported = 0
        var failed = 0
        try {
            for (job in jobs) {
                val params =
                    try {
                        OpenClawCronConverter.toKlawScheduleParams(job)
                    } catch (e: IllegalArgumentException) {
                        echo("  SKIP  ${job.name} — conversion error: ${e::class.simpleName}")
                        failed++
                        continue
                    } catch (e: IllegalStateException) {
                        echo("  SKIP  ${job.name} — conversion error: ${e::class.simpleName}")
                        failed++
                        continue
                    }
                val result = requestFn("schedule_add", params, agentId)
                if (result.contains("error", ignoreCase = true)) {
                    echo("  FAIL  ${job.name} — $result")
                    failed++
                } else {
                    echo("  OK    ${job.name}")
                    imported++
                }
            }
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
        return imported to failed
    }
}
