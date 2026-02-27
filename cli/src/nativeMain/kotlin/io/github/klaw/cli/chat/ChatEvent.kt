package io.github.klaw.cli.chat

internal sealed class ChatEvent {
    data class MessageReceived(
        val content: String,
    ) : ChatEvent()

    data class KeyPressed(
        val char: Char,
    ) : ChatEvent()

    data object Enter : ChatEvent()

    data object Backspace : ChatEvent()

    data object Quit : ChatEvent()
}
