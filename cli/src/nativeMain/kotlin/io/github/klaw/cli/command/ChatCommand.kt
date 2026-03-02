package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.chat.ChatEvent
import io.github.klaw.cli.chat.ChatSession
import io.github.klaw.cli.chat.ChatTui
import io.github.klaw.cli.chat.ChatWebSocketClient
import io.github.klaw.cli.chat.ConsoleChatConfig
import io.github.klaw.cli.chat.KeyParser
import io.github.klaw.cli.chat.readConsoleChatConfig
import io.github.klaw.cli.chat.readRawByte
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.ChatFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.exit

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

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun CoroutineScope.runChat(wsUrl: String) {
        val sendChannel = Channel<ChatFrame>(Channel.UNLIMITED)
        val events = Channel<ChatEvent>(Channel.UNLIMITED)
        val session = sessionFactory(wsUrl)
        val tui = ChatTui()
        var spinnerJob: Job? = null

        tui.init()
        try {
            val wsJob =
                launch {
                    try {
                        session.connect(
                            onFrame = { frame ->
                                when (frame.type) {
                                    "assistant" -> {
                                        events.send(ChatEvent.MessageReceived(frame.content))
                                    }

                                    "status" -> {
                                        events.send(ChatEvent.StatusUpdate(frame.content))
                                    }

                                    "approval_request" -> {
                                        val id = frame.approvalId ?: return@connect
                                        val riskScore = frame.riskScore ?: 0
                                        val timeout = frame.timeout ?: 0
                                        events.send(
                                            ChatEvent.ApprovalRequest(id, frame.content, riskScore, timeout),
                                        )
                                    }
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
                    val keyParser = KeyParser()
                    while (true) {
                        val byte = readRawByte() ?: break
                        val event = keyParser.feed(byte)
                        if (event != null && events.trySend(event).isFailure) break
                    }
                }

            for (event in events) {
                when (event) {
                    is ChatEvent.MessageReceived -> {
                        spinnerJob?.cancel()
                        spinnerJob = null
                        tui.setStatus("")
                        tui.addMessage(ChatTui.Message("assistant", event.content))
                        tui.redrawFull()
                    }

                    is ChatEvent.KeyPressed -> {
                        if (tui.isApprovalMode()) {
                            handleApprovalKey(event.text, tui, sendChannel)
                        } else {
                            tui.appendInput(event.text)
                            tui.redrawInputPanel()
                        }
                    }

                    ChatEvent.Backspace -> {
                        if (!tui.isApprovalMode()) {
                            tui.deleteLastInput()
                            tui.redrawInputPanel()
                        }
                    }

                    ChatEvent.Enter -> {
                        if (tui.isApprovalMode()) {
                            handleApprovalKey("y", tui, sendChannel)
                        } else {
                            val text = tui.submitInput()
                            if (text.isNotBlank()) {
                                if (text == "/exit" || text == "/quit") {
                                    events.close()
                                    break
                                }
                                tui.addMessage(ChatTui.Message("user", text))
                                sendChannel.send(ChatFrame(type = "user", content = text))
                                tui.setStatus("Thinking...")
                                spinnerJob = launchSpinner(tui)
                                tui.redrawFull()
                            } else {
                                tui.redrawInputPanel()
                            }
                        }
                    }

                    ChatEvent.Quit -> {
                        events.close()
                        break
                    }

                    is ChatEvent.ArrowKey -> {
                        if (!tui.isApprovalMode()) {
                            when (event.direction) {
                                ChatEvent.ArrowKey.Direction.LEFT -> tui.moveLeft()
                                ChatEvent.ArrowKey.Direction.RIGHT -> tui.moveRight()
                                ChatEvent.ArrowKey.Direction.UP -> tui.moveUp()
                                ChatEvent.ArrowKey.Direction.DOWN -> tui.moveDown()
                            }
                            tui.redrawInputPanel()
                        }
                    }

                    ChatEvent.Delete -> {
                        if (!tui.isApprovalMode()) {
                            tui.deleteForward()
                            tui.redrawInputPanel()
                        }
                    }

                    ChatEvent.Home -> {
                        if (!tui.isApprovalMode()) {
                            tui.moveHome()
                            tui.redrawInputPanel()
                        }
                    }

                    ChatEvent.End -> {
                        if (!tui.isApprovalMode()) {
                            tui.moveEnd()
                            tui.redrawInputPanel()
                        }
                    }

                    ChatEvent.NewLine -> {
                        if (!tui.isApprovalMode()) {
                            tui.insertNewline()
                            tui.redrawInputPanel()
                        }
                    }

                    is ChatEvent.StatusUpdate -> {
                        if (event.status.isNotEmpty()) {
                            tui.setStatus(event.status)
                            if (spinnerJob?.isActive != true) {
                                spinnerJob = launchSpinner(tui)
                            }
                        } else {
                            spinnerJob?.cancel()
                            spinnerJob = null
                            tui.setStatus("")
                        }
                        tui.redrawFull()
                    }

                    is ChatEvent.ApprovalRequest -> {
                        spinnerJob?.cancel()
                        spinnerJob = null
                        tui.setStatus("")
                        tui.showApproval(event.id, event.command, event.riskScore, event.timeout)
                        tui.redrawFull()
                    }
                }
            }

            spinnerJob?.cancel()
            wsJob.cancel()
            stdinJob.cancel()
        } finally {
            tui.cleanup()
            session.close()
            sendChannel.close()
            exit(0) // stdinJob is blocked in native read() — force exit after cleanup
        }
    }

    private suspend fun handleApprovalKey(
        text: String,
        tui: ChatTui,
        sendChannel: Channel<ChatFrame>,
    ) {
        val char = text.firstOrNull() ?: return
        val approved =
            when (char.lowercaseChar()) {
                'y' -> true
                'n' -> false
                else -> return
            }
        val result = tui.resolveApproval(approved)
        if (result != null) {
            val (id, wasApproved) = result
            sendChannel.send(
                ChatFrame(type = "approval_response", approvalId = id, approved = wasApproved),
            )
            tui.redrawFull()
        }
    }

    private fun CoroutineScope.launchSpinner(tui: ChatTui): Job =
        launch {
            while (isActive) {
                delay(SPINNER_INTERVAL_MS)
                tui.tickSpinner()
                tui.redrawSeparator()
            }
        }

    private companion object {
        const val SPINNER_INTERVAL_MS = 100L
    }
}
