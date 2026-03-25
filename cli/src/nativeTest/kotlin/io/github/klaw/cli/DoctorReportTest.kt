package io.github.klaw.cli

import io.github.klaw.cli.command.CheckStatus
import io.github.klaw.cli.command.DoctorCheckResult
import io.github.klaw.cli.command.DoctorDeepResult
import io.github.klaw.cli.command.DoctorReport
import io.github.klaw.cli.command.DoctorReportFormatter
import io.github.klaw.common.config.klawJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoctorReportTest {
    @Test
    fun `toText shows checkmark for OK status`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks = listOf(DoctorCheckResult("engine", CheckStatus.OK, "running")),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "\u2713 engine: running")
    }

    @Test
    fun `toText shows cross for FAIL status`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks = listOf(DoctorCheckResult("engine", CheckStatus.FAIL, "stopped")),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "\u2717 engine: stopped")
    }

    @Test
    fun `toText shows warning for WARN status`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks = listOf(DoctorCheckResult("skills", CheckStatus.WARN, "skipped (engine not running)")),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "\u26a0 skills: skipped (engine not running)")
    }

    @Test
    fun `toText shows warning for SKIP status`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks = listOf(DoctorCheckResult("check", CheckStatus.SKIP, "skipped")),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "\u26a0 check: skipped")
    }

    @Test
    fun `toText includes details with indentation`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks =
                    listOf(
                        DoctorCheckResult(
                            "engine.json",
                            CheckStatus.FAIL,
                            "invalid",
                            details = listOf(".routing: Missing required property"),
                        ),
                    ),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "  - .routing: Missing required property")
    }

    @Test
    fun `toText includes deploy mode`() {
        val report =
            DoctorReport(
                deployMode = "hybrid",
                checks = emptyList(),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "Deploy mode: hybrid")
    }

    @Test
    fun `toText with deep result includes deep section`() {
        val deepJson = """{"embedding":{"status":"ok","type":"onnx"},"database":{"status":"ok"}}"""
        val report =
            DoctorReport(
                deployMode = "native",
                checks = emptyList(),
                deep = DoctorDeepResult(deepJson),
            )
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "Deep Probe")
        assertContains(text, "embedding")
    }

    @Test
    fun `toText empty report has deploy mode only`() {
        val report = DoctorReport(deployMode = "native", checks = emptyList())
        val text = DoctorReportFormatter.toText(report)
        assertContains(text, "Deploy mode: native")
    }

    @Test
    fun `toJson produces valid JSON`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks = listOf(DoctorCheckResult("engine", CheckStatus.OK, "running")),
            )
        val json = DoctorReportFormatter.toJson(report)
        val parsed = klawJson.parseToJsonElement(json)
        assertTrue(parsed is JsonObject)
    }

    @Test
    fun `toJson contains checks array with correct structure`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks =
                    listOf(
                        DoctorCheckResult("gateway.json", CheckStatus.OK, "valid"),
                        DoctorCheckResult("engine", CheckStatus.FAIL, "stopped", listOf("not responding")),
                    ),
            )
        val json = DoctorReportFormatter.toJson(report)
        val parsed = klawJson.parseToJsonElement(json).jsonObject
        assertEquals("native", parsed["deployMode"]?.jsonPrimitive?.content)
        val checks = parsed["checks"]?.jsonArray
        assertEquals(2, checks?.size)
        val first = checks?.get(0)?.jsonObject
        assertEquals("gateway.json", first?.get("name")?.jsonPrimitive?.content)
        assertEquals("ok", first?.get("status")?.jsonPrimitive?.content)
        assertEquals("valid", first?.get("message")?.jsonPrimitive?.content)
        val second = checks?.get(1)?.jsonObject
        assertEquals("fail", second?.get("status")?.jsonPrimitive?.content)
        val details = second?.get("details")?.jsonArray
        assertEquals(1, details?.size)
    }

    @Test
    fun `toJson includes deep when present`() {
        val deepJson = """{"embedding":{"status":"ok"}}"""
        val report =
            DoctorReport(
                deployMode = "native",
                checks = emptyList(),
                deep = DoctorDeepResult(deepJson),
            )
        val json = DoctorReportFormatter.toJson(report)
        val parsed = klawJson.parseToJsonElement(json).jsonObject
        assertTrue(parsed.containsKey("deep"))
        val deep = parsed["deep"]?.jsonObject
        assertTrue(deep?.containsKey("embedding") == true)
    }

    @Test
    fun `toJson omits deep when null`() {
        val report =
            DoctorReport(
                deployMode = "native",
                checks = emptyList(),
                deep = null,
            )
        val json = DoctorReportFormatter.toJson(report)
        val parsed = klawJson.parseToJsonElement(json).jsonObject
        assertTrue(!parsed.containsKey("deep"))
    }

    @Test
    fun `toJson empty checks array`() {
        val report = DoctorReport(deployMode = "docker", checks = emptyList())
        val json = DoctorReportFormatter.toJson(report)
        val parsed = klawJson.parseToJsonElement(json).jsonObject
        assertEquals("docker", parsed["deployMode"]?.jsonPrimitive?.content)
        assertEquals(0, parsed["checks"]?.jsonArray?.size)
    }
}
