package io.github.klaw.common.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenClawCronConverterTest {
    // --- Cron format conversion ---

    @Test
    fun convertSimpleDailyCron() {
        // OpenClaw: "0 3 * * *" = at 03:00 daily
        // Quartz:   "0 0 3 * * ?" = at 03:00 daily
        assertEquals("0 0 3 * * ?", OpenClawCronConverter.convertCron("0 3 * * *"))
    }

    @Test
    fun convertHourlyRangeCron() {
        // OpenClaw: "0 9-23 * * *" = every hour 9-23
        assertEquals("0 0 9-23 * * ?", OpenClawCronConverter.convertCron("0 9-23 * * *"))
    }

    @Test
    fun convertCronWithDayOfWeek() {
        // OpenClaw: "0 11 * * 0-5" = 11:00 Sun-Fri
        // Quartz DOW: 0=SUN in standard cron -> Quartz uses 1=SUN or names
        assertEquals("0 0 11 ? * 1-6", OpenClawCronConverter.convertCron("0 11 * * 0-5"))
    }

    @Test
    fun convertCronWithSpecificDayOfWeek() {
        // OpenClaw: "0 12 * * 3" = Wednesday at 12:00
        // Quartz: 3=TUE in standard (0-indexed from SUN), so 3+1=4=WED in Quartz
        assertEquals("0 0 12 ? * 4", OpenClawCronConverter.convertCron("0 12 * * 3"))
    }

    @Test
    fun convertCronSundaySpecific() {
        // OpenClaw: "0 12 * * 0" = Sunday at 12:00
        // Quartz: 0+1=1=SUN
        assertEquals("0 0 12 ? * 1", OpenClawCronConverter.convertCron("0 12 * * 0"))
    }

    @Test
    fun convertEveryMinuteCron() {
        assertEquals("0 * * * * ?", OpenClawCronConverter.convertCron("* * * * *"))
    }

    @Test
    fun convertCronWithSpecificMinute() {
        // OpenClaw: "30 14 * * *" = 14:30 daily
        assertEquals("0 30 14 * * ?", OpenClawCronConverter.convertCron("30 14 * * *"))
    }

    @Test
    fun convertCronWithDayOfMonth() {
        // OpenClaw: "0 9 1 * *" = 1st of every month at 09:00
        assertEquals("0 0 9 1 * ?", OpenClawCronConverter.convertCron("0 9 1 * *"))
    }

    @Test
    fun convertCronWithMonthAndDom() {
        // OpenClaw: "0 9 15 6 *" = June 15 at 09:00
        assertEquals("0 0 9 15 6 ?", OpenClawCronConverter.convertCron("0 9 15 6 *"))
    }

    // --- Job parsing ---

    @Test
    fun parseEnabledCronJob() {
        val json =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "abc-123",
                  "name": "Daily Report",
                  "enabled": true,
                  "schedule": {
                    "kind": "cron",
                    "expr": "0 12 * * *",
                    "tz": "Europe/Prague"
                  },
                  "sessionTarget": "main",
                  "payload": {
                    "kind": "systemEvent",
                    "text": "Run daily report"
                  }
                }
              ]
            }
            """.trimIndent()

        val result = OpenClawCronConverter.parseJobs(json)
        assertEquals(1, result.size)
        val job = result[0]
        assertEquals("Daily Report", job.name)
        assertTrue(job.enabled)
        assertEquals("cron", job.scheduleKind)
        assertEquals("0 12 * * *", job.cronExpr)
        assertNull(job.at)
        assertEquals("Europe/Prague", job.timezone)
        assertEquals("Run daily report", job.message)
        assertNull(job.model)
        assertNull(job.deliveryChannel)
        assertNull(job.deliveryTo)
    }

    @Test
    fun parseOneShortAtJob() {
        val json =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "def-456",
                  "name": "Reminder",
                  "enabled": true,
                  "schedule": {
                    "kind": "at",
                    "at": "2026-03-31T09:00:00.000Z"
                  },
                  "payload": {
                    "kind": "systemEvent",
                    "text": "Check this"
                  }
                }
              ]
            }
            """.trimIndent()

        val result = OpenClawCronConverter.parseJobs(json)
        assertEquals(1, result.size)
        assertEquals("at", result[0].scheduleKind)
        assertEquals("2026-03-31T09:00:00.000Z", result[0].at)
        assertNull(result[0].cronExpr)
    }

    @Test
    fun parseJobWithDelivery() {
        val json =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "ghi-789",
                  "name": "Tracker",
                  "enabled": true,
                  "schedule": {
                    "kind": "cron",
                    "expr": "0 12 * * *"
                  },
                  "sessionTarget": "isolated",
                  "payload": {
                    "kind": "agentTurn",
                    "message": "Check for updates",
                    "model": "zai/glm-5"
                  },
                  "delivery": {
                    "mode": "announce",
                    "channel": "telegram",
                    "to": "292077641"
                  }
                }
              ]
            }
            """.trimIndent()

        val result = OpenClawCronConverter.parseJobs(json)
        assertEquals(1, result.size)
        val job = result[0]
        assertEquals("Check for updates", job.message)
        assertEquals("zai/glm-5", job.model)
        assertEquals("telegram", job.deliveryChannel)
        assertEquals("292077641", job.deliveryTo)
    }

    @Test
    fun parseDisabledJobFiltered() {
        val json =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "a",
                  "name": "Active",
                  "enabled": true,
                  "schedule": { "kind": "cron", "expr": "0 3 * * *" },
                  "payload": { "kind": "systemEvent", "text": "msg1" }
                },
                {
                  "id": "b",
                  "name": "Disabled",
                  "enabled": false,
                  "schedule": { "kind": "cron", "expr": "0 4 * * *" },
                  "payload": { "kind": "systemEvent", "text": "msg2" }
                }
              ]
            }
            """.trimIndent()

        val enabledOnly = OpenClawCronConverter.parseJobs(json, includeDisabled = false)
        assertEquals(1, enabledOnly.size)
        assertEquals("Active", enabledOnly[0].name)

        val all = OpenClawCronConverter.parseJobs(json, includeDisabled = true)
        assertEquals(2, all.size)
    }

    // --- Klaw schedule_add params conversion ---

    @Test
    fun convertCronJobToKlawParams() {
        val job =
            OpenClawJob(
                name = "Daily Report",
                enabled = true,
                scheduleKind = "cron",
                cronExpr = "0 12 * * *",
                at = null,
                timezone = "Europe/Prague",
                message = "Run daily report",
                model = null,
                deliveryChannel = null,
                deliveryTo = null,
            )

        val params = OpenClawCronConverter.toKlawScheduleParams(job)
        assertEquals("Daily Report", params["name"])
        assertEquals("0 0 12 * * ?", params["cron"])
        assertFalse(params.containsKey("at"))
        assertEquals("Run daily report", params["message"])
        assertFalse(params.containsKey("model"))
        assertFalse(params.containsKey("inject_into"))
        assertFalse(params.containsKey("channel"))
    }

    @Test
    fun convertAtJobToKlawParams() {
        val job =
            OpenClawJob(
                name = "Reminder",
                enabled = true,
                scheduleKind = "at",
                cronExpr = null,
                at = "2026-03-31T09:00:00.000Z",
                timezone = null,
                message = "Check this",
                model = null,
                deliveryChannel = null,
                deliveryTo = null,
            )

        val params = OpenClawCronConverter.toKlawScheduleParams(job)
        assertEquals("Reminder", params["name"])
        assertEquals("2026-03-31T09:00:00.000Z", params["at"])
        assertFalse(params.containsKey("cron"))
    }

    @Test
    fun convertJobWithDeliveryToKlawParams() {
        val job =
            OpenClawJob(
                name = "Tracker",
                enabled = true,
                scheduleKind = "cron",
                cronExpr = "0 12 * * *",
                at = null,
                timezone = null,
                message = "Check for updates",
                model = "zai/glm-5",
                deliveryChannel = "telegram",
                deliveryTo = "292077641",
            )

        val params = OpenClawCronConverter.toKlawScheduleParams(job)
        assertEquals("zai/glm-5", params["model"])
        assertEquals("telegram_292077641", params["inject_into"])
        assertEquals("telegram", params["channel"])
    }

    @Test
    fun convertJobWithDiscordDelivery() {
        val job =
            OpenClawJob(
                name = "Standup",
                enabled = true,
                scheduleKind = "cron",
                cronExpr = "0 11 * * 0-5",
                at = null,
                timezone = "Europe/Prague",
                message = "Daily standup",
                model = null,
                deliveryChannel = "discord",
                deliveryTo = "channel:1466871052608213075",
            )

        val params = OpenClawCronConverter.toKlawScheduleParams(job)
        assertEquals("0 0 11 ? * 1-6", params["cron"])
        assertEquals("discord_channel:1466871052608213075", params["inject_into"])
        assertEquals("discord", params["channel"])
    }

    @Test
    fun parseEmptyJobsList() {
        val json = """{"version": 1, "jobs": []}"""
        val result = OpenClawCronConverter.parseJobs(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseJobWithMissingOptionalFields() {
        val json =
            """
            {
              "version": 1,
              "jobs": [
                {
                  "id": "x",
                  "name": "Minimal",
                  "enabled": true,
                  "schedule": { "kind": "cron", "expr": "0 3 * * *" },
                  "payload": { "kind": "systemEvent", "text": "hello" }
                }
              ]
            }
            """.trimIndent()

        val result = OpenClawCronConverter.parseJobs(json)
        assertEquals(1, result.size)
        assertNull(result[0].timezone)
        assertNull(result[0].model)
        assertNull(result[0].deliveryChannel)
    }
}
