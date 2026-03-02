package io.github.klaw.cli.chat

internal sealed class ChatEvent {
    data class MessageReceived(
        val content: String,
    ) : ChatEvent()

    data class KeyPressed(
        val text: String,
    ) : ChatEvent()

    data object Enter : ChatEvent()

    data object Backspace : ChatEvent()

    data object Quit : ChatEvent()

    data class ArrowKey(
        val direction: Direction,
    ) : ChatEvent() {
        enum class Direction { UP, DOWN, LEFT, RIGHT }
    }

    data object Delete : ChatEvent()

    data object Home : ChatEvent()

    data object End : ChatEvent()

    data object NewLine : ChatEvent()

    data class StatusUpdate(
        val status: String,
    ) : ChatEvent()

    data class ApprovalRequest(
        val id: String,
        val command: String,
        val riskScore: Int,
        val timeout: Int,
    ) : ChatEvent()
}
