package io.github.klaw.e2e.infra

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking

data class ApiResponse(
    val status: HttpStatusCode,
    val body: String,
)

class RestApiClient(
    private val host: String,
    private val port: Int,
    private val token: String? = null,
) : AutoCloseable {
    private val httpClient = HttpClient(CIO)

    private fun baseUrl(): String = "http://$host:$port"

    fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): ApiResponse =
        runBlocking {
            val response =
                httpClient.get("${baseUrl()}$path") {
                    token?.let { header("Authorization", "Bearer $it") }
                    params.forEach { (k, v) -> parameter(k, v) }
                }
            ApiResponse(response.status, response.bodyAsText())
        }

    fun post(
        path: String,
        body: String? = null,
    ): ApiResponse =
        runBlocking {
            val response =
                httpClient.post("${baseUrl()}$path") {
                    token?.let { header("Authorization", "Bearer $it") }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            ApiResponse(response.status, response.bodyAsText())
        }

    fun put(
        path: String,
        body: String,
    ): ApiResponse =
        runBlocking {
            val response =
                httpClient.put("${baseUrl()}$path") {
                    token?.let { header("Authorization", "Bearer $it") }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            ApiResponse(response.status, response.bodyAsText())
        }

    fun delete(path: String): ApiResponse =
        runBlocking {
            val response =
                httpClient.delete("${baseUrl()}$path") {
                    token?.let { header("Authorization", "Bearer $it") }
                }
            ApiResponse(response.status, response.bodyAsText())
        }

    override fun close() {
        httpClient.close()
    }
}
