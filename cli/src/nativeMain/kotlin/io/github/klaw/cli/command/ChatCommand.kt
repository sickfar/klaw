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
import io.github.klaw.cli.util.CliLogger
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
        CliLogger.info { "chat start" }
        val chatConfig = configReader(configDir)
        if (url == null && !chatConfig.enabled) {
            CliLogger.warn { "console chat not enabled" }
            showConsoleChatDisabledError()
            return
        }
        val resolvedUrl = url ?: chatConfig.wsUrl
        CliLogger.debug { "connecting to $resolvedUrl" }
        runBlocking { runChat(resolvedUrl) }
        CliLogger.info { "chat end" }
    }

    private fun showConsoleChatDisabledError() {
        echo("${AnsiColors.RED}✗ WebSocket chat is not enabled.${AnsiColors.RESET}")
        echo("")
        echo("To enable it, add to $configDir/gateway.json:")
        echo("  \"channels\": { \"console\": { \"enabled\": true, \"port\": 37474 } }")
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
                        CliLogger.error { "chat connection failed: ${e::class.simpleName}" }
                        val msg =
                            when (e::class.simpleName) {
                                "ConnectException", "IOException" -> {
                                    "Cannot connect to gateway at $wsUrl — is the gateway running?"
                                }

                                "WebSocketException" -> {
                                    "WebSocket connection failed — gateway may not have console chat enabled"
                                }

                                else -> {
                                    "Connection error: ${e::class.simpleName}"
                                }
                            }
                        events.trySend(ChatEvent.MessageReceived(msg))
                    }
                }

            val stdinJob =
                launch(Dispatchers.Default) {
                    var escState = 0 // 0=none, 1=got ESC, 2=got ESC+[
                    while (true) {
                        val byte = readRawByte() ?: break
                        when (escState) {
                            1 -> {
                                escState = if (byte == 0x5B) 2 else 0 // '[' → wait for direction
                            }

                            2 -> {
                                escState = 0 // consume direction byte (A/B/C/D), ignore
                            }

                            else -> {
                                when (byte) {
                                    0x1B -> escState = 1

                                    // ESC

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
