package io.github.klaw.cli.http

import io.ktor.client.HttpClient

/**
 * Creates a platform-appropriate [HttpClient] with TLS support.
 * - macOS: Darwin engine (URLSession)
 * - Linux: Curl engine (libcurl)
 */
internal expect fun createHttpClient(): HttpClient
