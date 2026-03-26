package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.configure.ConfigSection
import io.github.klaw.cli.configure.ConfigureRunner
import io.github.klaw.cli.configure.DiscordSectionHandler
import io.github.klaw.cli.configure.ModelSectionHandler
import io.github.klaw.cli.configure.SectionHandler
import io.github.klaw.cli.configure.ServicesSectionHandler
import io.github.klaw.cli.configure.TelegramSectionHandler
import io.github.klaw.cli.configure.WebSearchSectionHandler
import io.github.klaw.cli.configure.WebSocketSectionHandler
import io.github.klaw.cli.ui.RadioSelector
import io.github.klaw.cli.util.CliLogger

internal class ConfigureCommand(
    private val configDir: String,
    private val commandRunner: (String) -> Int,
    private val commandOutput: (String) -> String?,
    private val readLineFn: () -> String? = ::readlnOrNull,
) : CliktCommand(name = "configure") {
    private val sectionNames by option(
        "--section",
        "-s",
        help = "Section to configure: model, telegram, discord, websocket, web-search, services",
    ).multiple()

    override fun run() {
        CliLogger.info { "configure started sections=${sectionNames.ifEmpty { listOf("all") }}" }

        val sections =
            if (sectionNames.isEmpty()) {
                ConfigSection.entries.toList()
            } else {
                val resolved = mutableListOf<ConfigSection>()
                for (name in sectionNames) {
                    val section = ConfigSection.fromCliName(name)
                    if (section == null) {
                        echo("Unknown section: $name")
                        echo("Available: ${ConfigSection.entries.joinToString(", ") { it.cliName }}")
                        return
                    }
                    resolved.add(section)
                }
                resolved
            }

        ConfigureRunner(
            configDir = configDir,
            sections = sections,
            printer = ::echo,
            handlerFactory = ::createHandler,
        ).run()
    }

    private fun createHandler(section: ConfigSection): SectionHandler =
        when (section) {
            ConfigSection.MODEL -> {
                ModelSectionHandler(
                    readLine = readLineFn,
                    printer = ::echo,
                    radioSelector = { items, prompt -> RadioSelector(items).select(prompt) },
                )
            }

            ConfigSection.TELEGRAM -> {
                TelegramSectionHandler(
                    readLine = readLineFn,
                    printer = ::echo,
                )
            }

            ConfigSection.DISCORD -> {
                DiscordSectionHandler(
                    readLine = readLineFn,
                    printer = ::echo,
                )
            }

            ConfigSection.WEBSOCKET -> {
                WebSocketSectionHandler(
                    readLine = readLineFn,
                    printer = ::echo,
                )
            }

            ConfigSection.WEB_SEARCH -> {
                WebSearchSectionHandler(
                    readLine = readLineFn,
                    printer = ::echo,
                    radioSelector = { items, prompt -> RadioSelector(items).select(prompt) },
                    commandOutput = commandOutput,
                )
            }

            ConfigSection.SERVICES -> {
                ServicesSectionHandler(
                    readLine = readLineFn,
                    printer = ::echo,
                    commandRunner = commandRunner,
                    configDir = configDir,
                )
            }
        }
}
