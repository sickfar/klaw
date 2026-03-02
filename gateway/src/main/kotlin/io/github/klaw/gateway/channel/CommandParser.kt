package io.github.klaw.gateway.channel

object CommandParser {
    data class Result(
        val isCommand: Boolean,
        val commandName: String?,
        val commandArgs: String?,
    )

    fun parse(text: String): Result {
        val isCommand = text.startsWith("/")
        if (!isCommand) return Result(false, null, null)
        val parts = text.drop(1).split(" ", limit = 2)
        val commandName =
            parts
                .firstOrNull()
                ?.substringBefore("@")
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        val commandArgs = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Result(isCommand, commandName, commandArgs)
    }
}
