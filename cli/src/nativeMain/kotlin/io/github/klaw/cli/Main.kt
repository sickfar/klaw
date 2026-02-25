package io.github.klaw.cli

import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.socket.EngineSocketClient

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: klaw <command>")
        return
    }
    val client = EngineSocketClient()
    try {
        println(client.request(args[0]))
    } catch (e: EngineNotRunningException) {
        println(e.message)
    }
}

