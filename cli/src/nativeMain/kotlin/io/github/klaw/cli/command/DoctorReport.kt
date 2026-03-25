package io.github.klaw.cli.command

import io.github.klaw.common.config.klawJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CheckStatus { OK, FAIL, WARN, SKIP }

data class DoctorCheckResult(
    val name: String,
    val status: CheckStatus,
    val message: String,
    val details: List<String> = emptyList(),
)

data class DoctorDeepResult(
    val raw: String,
)

data class DoctorReport(
    val deployMode: String,
    val checks: List<DoctorCheckResult>,
    val deep: DoctorDeepResult? = null,
)

object DoctorReportFormatter {
    fun toText(report: DoctorReport): String =
        buildString {
            appendLine("  Deploy mode: ${report.deployMode}")
            for (check in report.checks) {
                val symbol = statusSymbol(check.status)
                appendLine("$symbol ${check.name}: ${check.message}")
                for (detail in check.details) {
                    appendLine("  - $detail")
                }
            }
            if (report.deep != null) {
                appendLine()
                appendDeepText(report.deep)
            }
        }.trimEnd()

    fun toJson(report: DoctorReport): String {
        val obj =
            buildJsonObject {
                put("deployMode", report.deployMode)
                put(
                    "checks",
                    buildJsonArray {
                        for (check in report.checks) {
                            add(
                                buildJsonObject {
                                    put("name", check.name)
                                    put("status", check.status.name.lowercase())
                                    put("message", check.message)
                                    if (check.details.isNotEmpty()) {
                                        put(
                                            "details",
                                            buildJsonArray {
                                                for (detail in check.details) {
                                                    add(JsonPrimitive(detail))
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
                if (report.deep != null) {
                    val deepElement =
                        try {
                            klawJson.parseToJsonElement(report.deep.raw)
                        } catch (_: Exception) {
                            JsonPrimitive(report.deep.raw)
                        }
                    put("deep", deepElement)
                }
            }
        return klawJson.encodeToString(JsonObject.serializer(), obj)
    }

    private fun statusSymbol(status: CheckStatus): String =
        when (status) {
            CheckStatus.OK -> "\u2713"

            CheckStatus.FAIL -> "\u2717"

            // WARN and SKIP both use warning triangle — skip is not an error but merits attention
            CheckStatus.WARN, CheckStatus.SKIP -> "\u26a0"
        }

    private fun StringBuilder.appendDeepText(deep: DoctorDeepResult) {
        appendLine("\u2500\u2500 Deep Probe \u2500\u2500")
        val element =
            try {
                klawJson.parseToJsonElement(deep.raw) as? JsonObject
            } catch (_: Exception) {
                null
            }
        if (element == null) {
            appendLine(deep.raw)
            return
        }
        for ((key, value) in element) {
            when (value) {
                is JsonObject -> {
                    val status = value["status"]?.toString()?.trim('"') ?: "unknown"
                    val type = value["type"]?.toString()?.trim('"')
                    val extra = if (type != null) " ($type)" else ""
                    appendLine("  $key: $status$extra")
                }

                is JsonArray -> {
                    appendDeepArray(key, value)
                }

                else -> {
                    appendLine("  $key: $value")
                }
            }
        }
    }

    private fun StringBuilder.appendDeepArray(
        key: String,
        array: JsonArray,
    ) {
        if (array.isEmpty()) {
            appendLine("  $key: none")
            return
        }
        appendLine("  $key:")
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            val name = obj["name"]?.toString()?.trim('"') ?: "unknown"
            val type = obj["type"]?.toString()?.trim('"')
            val status = obj["status"]?.toString()?.trim('"')
            val parts = listOfNotNull(type, status).joinToString(", ")
            val extra = if (parts.isNotEmpty()) " ($parts)" else ""
            appendLine("    - $name$extra")
        }
    }
}
