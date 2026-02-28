package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComposeConfigTest {
    @Test
    fun `ComposeConfig round-trips through serialization`() {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "engine" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-engine:latest",
                                restart = "unless-stopped",
                                envFile = ".env",
                                environment = mapOf("HOME" to "/home/klaw"),
                                volumes = listOf("/state:/home/klaw/.local/state/klaw"),
                            ),
                        "gateway" to
                            ComposeServiceConfig(
                                image = "ghcr.io/sickfar/klaw-gateway:latest",
                                dependsOn = listOf("engine"),
                            ),
                    ),
                volumes = mapOf("klaw-run" to ComposeVolumeConfig(name = "klaw-run")),
            )

        val json = encodeComposeConfig(config)
        val parsed = parseComposeConfig(json)

        assertEquals(config.services.size, parsed.services.size)
        assertEquals("ghcr.io/sickfar/klaw-engine:latest", parsed.services["engine"]?.image)
        assertEquals("unless-stopped", parsed.services["engine"]?.restart)
        assertEquals(".env", parsed.services["engine"]?.envFile)
        assertEquals("/home/klaw", parsed.services["engine"]?.environment?.get("HOME"))
        assertEquals(1, parsed.services["engine"]?.volumes?.size)
        assertEquals(listOf("engine"), parsed.services["gateway"]?.dependsOn)
        val volumes = parsed.volumes
        assertNotNull(volumes)
        assertEquals("klaw-run", volumes["klaw-run"]?.name)
    }

    @Test
    fun `serialized JSON has correct structure`() {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "engine" to
                            ComposeServiceConfig(
                                image = "test:v1",
                                envFile = ".env",
                            ),
                    ),
            )

        val json = encodeComposeConfig(config)

        assertTrue(json.contains("\"services\""), "Expected 'services' key in:\n$json")
        assertTrue(json.contains("\"engine\""), "Expected 'engine' key in:\n$json")
        assertTrue(json.contains("\"image\""), "Expected 'image' key in:\n$json")
        assertTrue(json.contains("\"env_file\""), "Expected 'env_file' (snake_case) key in:\n$json")
        assertTrue(!json.contains("\"envFile\""), "Should not contain camelCase 'envFile' in:\n$json")
    }

    @Test
    fun `serialized JSON uses depends_on not dependsOn`() {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "gateway" to
                            ComposeServiceConfig(
                                image = "test:v1",
                                dependsOn = listOf("engine"),
                            ),
                    ),
            )

        val json = encodeComposeConfig(config)

        assertTrue(json.contains("\"depends_on\""), "Expected 'depends_on' (snake_case) in:\n$json")
        assertTrue(!json.contains("\"dependsOn\""), "Should not contain camelCase 'dependsOn' in:\n$json")
    }

    @Test
    fun `null optional fields are omitted`() {
        val config =
            ComposeConfig(
                services =
                    mapOf(
                        "engine" to
                            ComposeServiceConfig(
                                image = "test:v1",
                            ),
                    ),
            )

        val json = encodeComposeConfig(config)

        assertTrue(!json.contains("\"restart\""), "Expected no 'restart' key when null in:\n$json")
        assertTrue(!json.contains("\"env_file\""), "Expected no 'env_file' key when null in:\n$json")
        assertTrue(!json.contains("\"environment\""), "Expected no 'environment' key when null in:\n$json")
        assertTrue(!json.contains("\"depends_on\""), "Expected no 'depends_on' key when null in:\n$json")
        assertTrue(!json.contains("\"volumes\""), "Expected no 'volumes' key when null in:\n$json")
    }
}
