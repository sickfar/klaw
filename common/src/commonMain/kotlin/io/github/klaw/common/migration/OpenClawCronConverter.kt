package io.github.klaw.common.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OpenClawJob(
    val name: String,
    val enabled: Boolean,
    val scheduleKind: String,
    val cronExpr: String?,
    val at: String?,
    val timezone: String?,
    val message: String,
    val model: String?,
    val deliveryChannel: String?,
    val deliveryTo: String?,
)

object OpenClawCronConverter {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Convert 5-field standard cron to 6-field Quartz cron.
     *
     * Standard: MIN HOUR DOM MON DOW
     * Quartz:   SEC MIN HOUR DOM MON DOW
     *
     * Rules:
     * - Prepend "0" for seconds
     * - If DOM != "*" -> DOW becomes "?"
     * - If DOW != "*" -> DOM becomes "?", shift DOW numbers +1 (standard 0=SUN -> Quartz 1=SUN)
     * - If both are "*" -> DOW becomes "?"
     */
    fun convertCron(standardCron: String): String {
        val parts = standardCron.trim().split("\\s+".toRegex())
        require(parts.size == STANDARD_CRON_FIELDS) { "Expected 5-field cron, got ${parts.size}: $standardCron" }

        val min = parts[IDX_MIN]
        val hour = parts[IDX_HOUR]
        val dom = parts[IDX_DOM]
        val mon = parts[IDX_MON]
        val dow = parts[IDX_DOW]

        return if (dow != "*") {
            // DOW is specified -> DOM must be "?"
            val quartzDow = convertDow(dow)
            "0 $min $hour ? $mon $quartzDow"
        } else {
            // DOW is wildcard -> replace with "?"
            "0 $min $hour $dom $mon ?"
        }
    }

    /**
     * Convert standard cron DOW (0=SUN..6=SAT) to Quartz DOW (1=SUN..7=SAT).
     * Handles single values, ranges (0-5), and lists (1,3,5).
     */
    private fun convertDow(dow: String): String =
        dow.split(",").joinToString(",") { part ->
            if (part.contains("-")) {
                val (start, end) = part.split("-", limit = 2)
                "${shiftDow(start)}-${shiftDow(end)}"
            } else {
                shiftDow(part)
            }
        }

    private fun shiftDow(value: String): String {
        val num = value.toIntOrNull() ?: return value // named days pass through
        return (num + 1).toString()
    }

    /**
     * Parse OpenClaw jobs.json and extract job definitions.
     */
    fun parseJobs(
        jsonString: String,
        includeDisabled: Boolean = false,
    ): List<OpenClawJob> {
        val root = json.parseToJsonElement(jsonString).jsonObject
        val jobs = root["jobs"]?.jsonArray ?: return emptyList()

        return jobs
            .map { it.jsonObject }
            .mapNotNull { parseJob(it) }
            .filter { includeDisabled || it.enabled }
    }

    private fun parseJob(obj: JsonObject): OpenClawJob? {
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val enabled = obj["enabled"]?.jsonPrimitive?.boolean ?: true

        val schedule = obj["schedule"]?.jsonObject ?: return null
        val kind = schedule["kind"]?.jsonPrimitive?.content ?: return null

        val payload = obj["payload"]?.jsonObject ?: return null
        val message =
            payload["text"]?.jsonPrimitive?.content
                ?: payload["message"]?.jsonPrimitive?.content
                ?: return null

        val model = payload["model"]?.jsonPrimitive?.content

        val delivery = obj["delivery"]?.jsonObject
        val deliveryChannel = delivery?.get("channel")?.jsonPrimitive?.content
        val deliveryTo = delivery?.get("to")?.jsonPrimitive?.content

        return OpenClawJob(
            name = name,
            enabled = enabled,
            scheduleKind = kind,
            cronExpr = if (kind == "cron") schedule["expr"]?.jsonPrimitive?.content else null,
            at = if (kind == "at") schedule["at"]?.jsonPrimitive?.content else null,
            timezone = schedule["tz"]?.jsonPrimitive?.content,
            message = message,
            model = model,
            deliveryChannel = deliveryChannel,
            deliveryTo = deliveryTo,
        )
    }

    /**
     * Convert an OpenClawJob to Klaw schedule_add parameters.
     */
    fun toKlawScheduleParams(job: OpenClawJob): Map<String, String> =
        buildMap {
            put("name", job.name)
            put("message", job.message)

            if (job.scheduleKind == "cron" && job.cronExpr != null) {
                put("cron", convertCron(job.cronExpr))
            }
            if (job.scheduleKind == "at" && job.at != null) {
                put("at", job.at)
            }

            job.model?.let { put("model", it) }

            if (job.deliveryChannel != null && job.deliveryTo != null) {
                put("inject_into", "${job.deliveryChannel}_${job.deliveryTo}")
                put("channel", job.deliveryChannel)
            }
        }

    private const val STANDARD_CRON_FIELDS = 5
    private const val IDX_MIN = 0
    private const val IDX_HOUR = 1
    private const val IDX_DOM = 2
    private const val IDX_MON = 3
    private const val IDX_DOW = 4
}
