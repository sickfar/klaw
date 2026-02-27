package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.chat.ChatEvent
import io.github.klaw.cli.chat.ChatSession
import io.github.klaw.cli.chat.ChatTui
import io.github.klaw.cli.chat.ChatWebSocketClient
import io.github.klaw.cli.chat.ConsoleChatConfig
import io.github.klaw.cli.chat.readConsoleChatConfig
import io.github.klaw.cli.chat.readRawByte
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.common.paths.KlawPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class ChatCommand(
    private val configDir: String = KlawPaths.config,
    private val configReader: (String) -> ConsoleChatConfig = { dir -> readConsoleChatConfig(dir) },
    private val sessionFactory: (String) -> ChatSession = { url -> ChatWebSocketClient(url) },
) : CliktCommand(name = "chat") {
    private val url by option("--url", help = "Override gateway WebSocket URL (bypasses enabled check)")

    override fun run() {
        val chatConfig = configReader(configDir)
        if (url == null && !chatConfig.enabled) {
            showConsoleChatDisabledError()
            return
        }
        val resolvedUrl = url ?: chatConfig.wsUrl
        runBlocking { runChat(resolvedUrl) }
    }

    private fun showConsoleChatDisabledError() {
        echo("${AnsiColors.RED}✗ WebSocket chat is not enabled.${AnsiColors.RESET}")
        echo("")
        echo("To enable it, add to $configDir/gateway.yaml:")
        echo("  channels:")
        echo("    console:")
        echo("      enabled: true")
        echo("      port: 37474")
        echo("")
        echo("Then restart the gateway: klaw gateway restart")
        echo("")
        echo("Or re-run: klaw init")
    }

    private suspend fun CoroutineScope.runChat(wsUrl: String) {
        val sendChannel = Channel<String>(Channel.UNLIMITED)
        val events = Channel<ChatEvent>(Channel.UNLIMITED)
        val session = sessionFactory(wsUrl)
        val tui = ChatTui()

        tui.init()
        try {
            val wsJob =
                launch {
                    try {
                        session.connect(
                            onFrame = { frame ->
                                if (frame.type == "assistant") {
                                    events.send(ChatEvent.MessageReceived(frame.content))
                                }
                            },
                            outgoing = sendChannel,
                        )
                    } catch (e: Exception) {
                        events.trySend(ChatEvent.MessageReceived("[Error: ${e::class.simpleName}]"))
                    }
                }

            val stdinJob =
                launch(Dispatchers.Default) {
                    while (true) {
                        val byte = readRawByte() ?: break
                        when (byte) {
                            3 -> events.send(ChatEvent.Quit)

                            // Ctrl+C

                            127 -> events.send(ChatEvent.Backspace)

                            // Backspace

                            13, 10 -> events.send(ChatEvent.Enter)

                            // Enter / newline

                            else -> if (byte >= 32) events.send(ChatEvent.KeyPressed(byte.toChar()))
                        }
                    }
                }

            for (event in events) {
                when (event) {
                    is ChatEvent.MessageReceived -> {
                        tui.addMessage(ChatTui.Message("assistant", event.content))
                        tui.redrawFull()
                    }

                    is ChatEvent.KeyPressed -> {
                        tui.appendInput(event.char)
                        tui.redrawInputLine()
                    }

                    ChatEvent.Backspace -> {
                        tui.deleteLastInput()
                        tui.redrawInputLine()
                    }

                    ChatEvent.Enter -> {
                        val text = tui.submitInput()
                        if (text.isNotBlank()) {
                            if (text == "/exit" || text == "/quit") {
                                events.close()
                                break
                            }
                            tui.addMessage(ChatTui.Message("user", text))
                            sendChannel.send(text)
                            tui.redrawFull()
                        } else {
                            tui.redrawInputLine()
                        }
                    }

                    ChatEvent.Quit -> {
                        events.close()
                        break
                    }
                }
            }

            wsJob.cancel()
            stdinJob.cancel()
        } finally {
            tui.cleanup()
            session.close()
            sendChannel.close()
        }
    }
}
