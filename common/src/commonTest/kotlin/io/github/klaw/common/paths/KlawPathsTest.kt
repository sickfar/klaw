package io.github.klaw.common.paths

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlawPathsTest {
    private fun buildTestPaths(
        env: Map<String, String> = emptyMap(),
        home: String = "/home/testuser",
    ): KlawPathsSnapshot =
        buildPaths(
            envProvider = { key -> env[key] },
            homeProvider = { home },
        )

    @Test
    fun `default config path uses HOME dot config klaw`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("/home/alice/.config/klaw", paths.config)
    }

    @Test
    fun `custom XDG_CONFIG_HOME overrides default config path`() {
        val paths = buildTestPaths(env = mapOf("XDG_CONFIG_HOME" to "/custom/config"), home = "/home/alice")
        assertEquals("/custom/config/klaw", paths.config)
    }

    @Test
    fun `default data path uses HOME dot local share klaw`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("/home/alice/.local/share/klaw", paths.data)
    }

    @Test
    fun `custom XDG_DATA_HOME overrides default data path`() {
        val paths = buildTestPaths(env = mapOf("XDG_DATA_HOME" to "/custom/data"), home = "/home/alice")
        assertEquals("/custom/data/klaw", paths.data)
    }

    @Test
    fun `default state path uses HOME dot local state klaw`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("/home/alice/.local/state/klaw", paths.state)
    }

    @Test
    fun `custom XDG_STATE_HOME overrides default state path`() {
        val paths = buildTestPaths(env = mapOf("XDG_STATE_HOME" to "/custom/state"), home = "/home/alice")
        assertEquals("/custom/state/klaw", paths.state)
    }

    @Test
    fun `default cache path uses HOME dot cache klaw`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("/home/alice/.cache/klaw", paths.cache)
    }

    @Test
    fun `custom XDG_CACHE_HOME overrides default cache path`() {
        val paths = buildTestPaths(env = mapOf("XDG_CACHE_HOME" to "/custom/cache"), home = "/home/alice")
        assertEquals("/custom/cache/klaw", paths.cache)
    }

    @Test
    fun `default workspace uses HOME klaw-workspace`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("/home/alice/klaw-workspace", paths.workspace)
    }

    @Test
    fun `KLAW_WORKSPACE env overrides workspace path`() {
        val paths = buildTestPaths(env = mapOf("KLAW_WORKSPACE" to "/opt/workspace"), home = "/home/alice")
        assertEquals("/opt/workspace", paths.workspace)
    }

    @Test
    fun `default enginePort is 7470`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals(7470, paths.enginePort)
    }

    @Test
    fun `KLAW_ENGINE_PORT env overrides default port`() {
        val paths = buildTestPaths(env = mapOf("KLAW_ENGINE_PORT" to "9090"))
        assertEquals(9090, paths.enginePort)
    }

    @Test
    fun `KLAW_ENGINE_PORT invalid value falls back to default`() {
        val paths = buildTestPaths(env = mapOf("KLAW_ENGINE_PORT" to "notanumber"))
        assertEquals(7470, paths.enginePort)
    }

    @Test
    fun `default engineHost is 127_0_0_1`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("127.0.0.1", paths.engineHost)
    }

    @Test
    fun `KLAW_ENGINE_HOST env overrides default host`() {
        val paths = buildTestPaths(env = mapOf("KLAW_ENGINE_HOST" to "engine"))
        assertEquals("engine", paths.engineHost)
    }

    @Test
    fun `gatewayBuffer is in state dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.gatewayBuffer.startsWith(paths.state))
        assertTrue(paths.gatewayBuffer.endsWith("gateway-buffer.jsonl"))
    }

    @Test
    fun `klawDb is in data dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.klawDb.startsWith(paths.data))
        assertTrue(paths.klawDb.endsWith("klaw.db"))
    }

    @Test
    fun `schedulerDb is in data dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.schedulerDb.startsWith(paths.data))
        assertTrue(paths.schedulerDb.endsWith("scheduler.db"))
    }

    @Test
    fun `conversations is in data dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.conversations.startsWith(paths.data))
        assertTrue(paths.conversations.endsWith("conversations"))
    }

    @Test
    fun `summaries is in data dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.summaries.startsWith(paths.data))
        assertTrue(paths.summaries.endsWith("summaries"))
    }

    @Test
    fun `skills is in data dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.skills.startsWith(paths.data))
        assertTrue(paths.skills.endsWith("skills"))
    }

    @Test
    fun `models is in cache dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertTrue(paths.models.startsWith(paths.cache))
        assertTrue(paths.models.endsWith("models"))
    }

    @Test
    fun `KLAW_ENGINE_PORT does not affect gatewayBuffer path`() {
        val paths = buildTestPaths(env = mapOf("KLAW_ENGINE_PORT" to "9090"), home = "/home/alice")
        assertEquals("/home/alice/.local/state/klaw/gateway-buffer.jsonl", paths.gatewayBuffer)
    }

    @Test
    fun `logs is in state dir`() {
        val paths = buildTestPaths(home = "/home/alice")
        assertEquals("/home/alice/.local/state/klaw/logs", paths.logs)
    }

    @Test
    fun `all XDG vars set simultaneously`() {
        val paths =
            buildTestPaths(
                env =
                    mapOf(
                        "XDG_CONFIG_HOME" to "/c",
                        "XDG_DATA_HOME" to "/d",
                        "XDG_STATE_HOME" to "/s",
                        "XDG_CACHE_HOME" to "/ca",
                        "KLAW_WORKSPACE" to "/w",
                    ),
            )
        assertEquals("/c/klaw", paths.config)
        assertEquals("/d/klaw", paths.data)
        assertEquals("/s/klaw", paths.state)
        assertEquals("/ca/klaw", paths.cache)
        assertEquals("/w", paths.workspace)
    }
}
