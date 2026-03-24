package io.github.klaw.cli.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

internal actual fun createHttpClient(): HttpClient = HttpClient(Curl)
