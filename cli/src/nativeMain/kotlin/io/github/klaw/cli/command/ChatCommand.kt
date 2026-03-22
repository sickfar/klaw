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
import kotlinx.coroutines.channels.SendChannel
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
            CliLogger.warn { "local WebSocket chat not enabled" }
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
        echo("  \"channels\": { \"localWs\": { \"enabled\": true, \"port\": 37474 } }")
        echo("")
        echo("Then restart the gateway: klaw service restart gateway")
        echo("")
        echo("Or re-run: klaw init")
    }

    private suspend fun CoroutineScope.runChat(wsUrl: String) {
        val sendChannel = Channel<ChatFrame>(Channel.UNLIMITED)
        val events = Channel<ChatEvent>(Channel.UNLIMITED)
        val session = sessionFactory(wsUrl)
        val tui = ChatTui()

        tui.init()
        try {
            val wsJob = launch { connectToGateway(session, wsUrl, events, sendChannel) }
            val stdinJob = launchStdin(events)
            runEventLoop(tui, sendChannel, events)
            wsJob.cancel()
            stdinJob.cancel()
        } finally {
            tui.cleanup()
            session.close()
            sendChannel.close()
            exit(0) // stdinJob is blocked in native read() — force exit after cleanup
        }
    }

    private suspend fun CoroutineScope.runEventLoop(
        tui: ChatTui,
        sendChannel: Channel<ChatFrame>,
        events: Channel<ChatEvent>,
    ) {
        var spinnerJob: Job? = null
        for (event in events) {
            when (event) {
                is ChatEvent.MessageReceived -> {
                    spinnerJob?.cancel()
                    spinnerJob = null
                    tui.setStatus("")
                    tui.addMessage(ChatTui.Message("assistant", event.content))
                    tui.redrawFull()
                }

                ChatEvent.Enter -> {
                    val (shouldClose, newJob) = handleEnterEvent(tui, sendChannel, events)
                    if (newJob != null) {
                        spinnerJob?.cancel()
                        spinnerJob = newJob
                    }
                    if (shouldClose) break
                }

                ChatEvent.Quit -> {
                    events.close()
                    break
                }

                is ChatEvent.StatusUpdate -> {
                    spinnerJob = handleStatusUpdate(event.status, tui, spinnerJob)
                }

                is ChatEvent.ApprovalRequest -> {
                    spinnerJob?.cancel()
                    spinnerJob = null
                    tui.setStatus("")
                    tui.showApproval(event.id, event.command, event.riskScore, event.timeout)
                    tui.redrawFull()
                }

                else -> {
                    handleInputEditingEvent(event, tui, sendChannel)
                }
            }
        }
        spinnerJob?.cancel()
    }

    private suspend fun handleInputEditingEvent(
        event: ChatEvent,
        tui: ChatTui,
        sendChannel: Channel<ChatFrame>,
    ) {
        if (event is ChatEvent.KeyPressed && tui.isApprovalMode()) {
            handleApprovalKey(event.text, tui, sendChannel)
            return
        }
        if (tui.isApprovalMode()) return
        when (event) {
            is ChatEvent.KeyPressed -> {
                tui.appendInput(event.text)
                tui.redrawInputPanel()
            }

            is ChatEvent.ArrowKey -> {
                handleArrowKey(event.direction, tui)
            }

            ChatEvent.Backspace -> {
                tui.deleteLastInput()
                tui.redrawInputPanel()
            }

            ChatEvent.Delete -> {
                tui.deleteForward()
                tui.redrawInputPanel()
            }

            ChatEvent.Home -> {
                tui.moveHome()
                tui.redrawInputPanel()
            }

            ChatEvent.End -> {
                tui.moveEnd()
                tui.redrawInputPanel()
            }

            ChatEvent.NewLine -> {
                tui.insertNewline()
                tui.redrawInputPanel()
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun handleFrame(
        frame: ChatFrame,
        events: SendChannel<ChatEvent>,
    ) {
        when (frame.type) {
            "assistant" -> {
                events.send(ChatEvent.MessageReceived(frame.content))
            }

            "status" -> {
                events.send(ChatEvent.StatusUpdate(frame.content))
            }

            "approval_request" -> {
                val id = frame.approvalId ?: return
                val riskScore = frame.riskScore ?: 0
                val timeout = frame.timeout ?: 0
                events.send(ChatEvent.ApprovalRequest(id, frame.content, riskScore, timeout))
            }
        }
    }

    private suspend fun connectToGateway(
        session: ChatSession,
        wsUrl: String,
        events: Channel<ChatEvent>,
        sendChannel: Channel<ChatFrame>,
    ) {
        runCatching {
            session.connect(
                onFrame = { frame -> handleFrame(frame, events) },
                outgoing = sendChannel,
            )
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            CliLogger.error { "chat connection failed: ${e::class.simpleName}" }
            val msg =
                when (e::class.simpleName) {
                    "ConnectException", "IOException" -> {
                        "Cannot connect to gateway at $wsUrl — is the gateway running?"
                    }

                    "WebSocketException" -> {
                        "WebSocket connection failed — gateway may not have local WebSocket chat enabled"
                    }

                    else -> {
                        "Connection error: ${e::class.simpleName}"
                    }
                }
            events.trySend(ChatEvent.MessageReceived(msg))
        }
    }

    private fun handleArrowKey(
        direction: ChatEvent.ArrowKey.Direction,
        tui: ChatTui,
    ) {
        when (direction) {
            ChatEvent.ArrowKey.Direction.LEFT -> tui.moveLeft()
            ChatEvent.ArrowKey.Direction.RIGHT -> tui.moveRight()
            ChatEvent.ArrowKey.Direction.UP -> tui.moveUp()
            ChatEvent.ArrowKey.Direction.DOWN -> tui.moveDown()
        }
        tui.redrawInputPanel()
    }

    private fun CoroutineScope.handleStatusUpdate(
        status: String,
        tui: ChatTui,
        currentSpinnerJob: Job?,
    ): Job? {
        if (status.isNotEmpty()) {
            tui.setStatus(status)
            val newJob = if (currentSpinnerJob?.isActive != true) launchSpinner(tui) else currentSpinnerJob
            tui.redrawFull()
            return newJob
        }
        currentSpinnerJob?.cancel()
        tui.setStatus("")
        tui.redrawFull()
        return null
    }

    private fun CoroutineScope.launchStdin(events: Channel<ChatEvent>): Job =
        launch(Dispatchers.Default) {
            val keyParser = KeyParser()
            while (true) {
                val byte = readRawByte() ?: break
                val event = keyParser.feed(byte)
                if (event != null && events.trySend(event).isFailure) break
            }
        }

    // Returns (shouldClose, newSpinnerJob)
    private suspend fun CoroutineScope.handleEnterEvent(
        tui: ChatTui,
        sendChannel: Channel<ChatFrame>,
        events: Channel<ChatEvent>,
    ): Pair<Boolean, Job?> {
        if (tui.isApprovalMode()) {
            handleApprovalKey("y", tui, sendChannel)
            return false to null
        }
        val text = tui.submitInput()
        if (text.isBlank()) {
            tui.redrawInputPanel()
            return false to null
        }
        if (text == "/exit" || text == "/quit") {
            events.close()
            return true to null
        }
        tui.addMessage(ChatTui.Message("user", text))
        sendChannel.send(ChatFrame(type = "user", content = text))
        tui.setStatus("Thinking...")
        val newJob = launchSpinner(tui)
        tui.redrawFull()
        return false to newJob
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
