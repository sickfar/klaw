package io.github.klaw.cli.init

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.parseComposeConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTemplatesTest {
    @Test
    fun `engine json template contains provider url`() {
        val json = ConfigTemplates.engineJson("https://api.z.ai/api/coding/paas/v4", "zai/glm-5")
        assertTrue(json.contains("https://api.z.ai/api/coding/paas/v4"), "Expected providerUrl in:\n$json")
    }

    @Test
    fun `engine json api key uses per-provider env var`() {
        val json = ConfigTemplates.engineJson("https://api.z.ai/api/coding/paas/v4", "zai/glm-5")
        assertTrue(json.contains("ZAI_API_KEY"), "Expected per-provider env var ZAI_API_KEY in:\n$json")
        assertTrue(!json.contains("KLAW_LLM_API_KEY"), "Should not contain generic KLAW_LLM_API_KEY in:\n$json")
    }

    @Test
    fun `apiKeyEnvVar derives uppercase alias`() {
        assertTrue(ConfigTemplates.apiKeyEnvVar("zai") == "ZAI_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("anthropic") == "ANTHROPIC_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("openai") == "OPENAI_API_KEY")
    }

    @Test
    fun `engine json template contains model id`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "myProvider/my-model")
        assertTrue(json.contains("myProvider/my-model"), "Expected full modelId in:\n$json")
    }

    @Test
    fun `gateway json template contains telegram channel`() {
        val json = ConfigTemplates.gatewayJson(allowedChats = listOf(AllowedChat("123456789")))
        assertTrue(json.contains("telegram"), "Expected 'telegram' in:\n$json")
        assertTrue(json.contains("123456789"), "Expected chatId in:\n$json")
    }

    @Test
    fun `gateway json template with empty chat ids is valid`() {
        val json = ConfigTemplates.gatewayJson(allowedChats = emptyList())
        assertTrue(json.contains("telegram"), "Expected 'telegram' in:\n$json")
        // allowedChats=emptyList() matches Kotlin default — not encoded by minimal encoder
        val config = parseGatewayConfig(json)
        val allowedChats = config.channels.telegram?.allowedChats
        assertTrue(allowedChats?.isEmpty() == true, "Expected empty allowedChats in parsed config")
    }

    @Test
    fun `engine json api key uses per-provider env var for custom provider`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(json.contains("TEST_API_KEY"), "Expected per-provider env var TEST_API_KEY in:\n$json")
    }

    @Test
    fun `gateway json token uses env var reference`() {
        val json = ConfigTemplates.gatewayJson(allowedChats = emptyList())
        assertTrue(json.contains("KLAW_TELEGRAM_TOKEN"), "Expected env var reference in:\n$json")
    }

    @Test
    fun `gatewayJson with enableLocalWs=true includes localWs section`() {
        val json = ConfigTemplates.gatewayJson(enableLocalWs = true)
        assertTrue(json.contains("localWs"), "Expected 'localWs' in:\n$json")
        assertTrue(json.contains("true"), "Expected 'true' (enabled) in:\n$json")
        // Default port (37474) is not encoded by minimal encoder; verify via round-trip parse
        val config = parseGatewayConfig(json)
        assertTrue(config.channels.localWs?.port == 37474, "Expected default port 37474 in parsed config")
    }

    @Test
    fun `gatewayJson with enableLocalWs=false omits localWs section`() {
        val json = ConfigTemplates.gatewayJson(enableLocalWs = false)
        assertTrue(!json.contains("localWs"), "Expected no 'localWs' section in:\n$json")
    }

    @Test
    fun `gatewayJson with enableLocalWs=true and custom port uses correct port`() {
        val json = ConfigTemplates.gatewayJson(enableLocalWs = true, localWsPort = 9090)
        assertTrue(json.contains("9090"), "Expected custom port in:\n$json")
    }

    @Test
    fun `gatewayJson with chatIds and enableLocalWs=true includes both sections`() {
        val json =
            ConfigTemplates.gatewayJson(
                allowedChats = listOf(AllowedChat("123456")),
                enableLocalWs = true,
                localWsPort = 8080,
            )
        assertTrue(json.contains("123456"), "Expected chatId in:\n$json")
        assertTrue(json.contains("localWs"), "Expected localWs section in:\n$json")
        assertTrue(json.contains("8080"), "Expected custom port in:\n$json")
    }

    // --- Telegram enabled/disabled ---

    @Test
    fun `gatewayJson with telegramEnabled=false omits telegram section`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false)
        assertTrue(!json.contains("telegram"), "Expected no 'telegram' section in:\n$json")
    }

    @Test
    fun `gatewayJson with telegramEnabled=true includes telegram section`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = true)
        assertTrue(json.contains("telegram"), "Expected 'telegram' section in:\n$json")
    }

    @Test
    fun `gatewayJson with telegramEnabled=false and enableLocalWs=true has localWs but no telegram`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, enableLocalWs = true)
        assertTrue(!json.contains("telegram"), "Expected no telegram in:\n$json")
        assertTrue(json.contains("localWs"), "Expected localWs section in:\n$json")
    }

    @Test
    fun `gatewayJson telegramEnabled=false with localWs produces valid json`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, enableLocalWs = true, localWsPort = 8888)
        assertTrue(json.contains("channels"), "Expected 'channels' in:\n$json")
        assertTrue(json.contains("8888"), "Expected port in:\n$json")
    }

    // --- Round-trip: generated JSON parses back correctly ---

    @Test
    fun `engineJson round-trips through parser`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        val config = parseEngineConfig(json)
        assertTrue(config.providers.containsKey("test"), "Expected provider 'test' in parsed config")
        assertTrue(config.routing.default == "test/model", "Expected routing default 'test/model'")
    }

    @Test
    fun `gatewayJson round-trips through parser`() {
        val json =
            ConfigTemplates.gatewayJson(
                allowedChats = listOf(AllowedChat("123")),
                enableLocalWs = true,
                localWsPort = 9090,
            )
        val config = parseGatewayConfig(json)
        assertTrue(config.channels.telegram != null, "Expected telegram section")
        assertTrue(config.channels.localWs != null, "Expected localWs section")
        assertTrue(config.channels.localWs?.port == 9090, "Expected port 9090")
    }

    // --- dockerComposeJson ---

    @Test
    fun `dockerComposeJson returns valid JSON parseable by parseComposeConfig`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        assertNotNull(config, "Expected parseable ComposeConfig")
    }

    @Test
    fun `dockerComposeJson contains engine service with correct image tag`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "v0.5.0",
            )
        val config = parseComposeConfig(result)
        assertEquals("ghcr.io/sickfar/klaw-engine:v0.5.0", config.services["engine"]?.image)
        assertEquals("ghcr.io/sickfar/klaw-gateway:v0.5.0", config.services["gateway"]?.image)
    }

    @Test
    fun `dockerComposeJson contains gateway with depends_on engine`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        assertEquals(listOf("engine"), config.services["gateway"]?.dependsOn)
    }

    @Test
    fun `dockerComposeJson contains correct bind mount volumes`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/home/pi/.local/state/klaw",
                dataPath = "/home/pi/.local/share/klaw",
                configPath = "/home/pi/.config/klaw",
                workspacePath = "/home/pi/workspace",
                imageTag = "latest",
            )
        assertTrue(
            result.contains("/home/pi/.local/state/klaw:/home/klaw/.local/state/klaw"),
            "Expected bind mount syntax in:\n$result",
        )
        assertTrue(
            result.contains("/home/pi/.local/share/klaw:/home/klaw/.local/share/klaw"),
            "Expected bind mount syntax in:\n$result",
        )
    }

    @Test
    fun `dockerComposeJson has klaw-cache named volume`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        assertNotNull(config.volumes)
        assertEquals(1, config.volumes!!.size, "Expected 1 named volume (klaw-cache)")
        assertTrue(config.volumes!!.containsKey("klaw-cache"))
    }

    @Test
    fun `dockerComposeJson sets HOME and KLAW_ENGINE_BIND environment`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        val engineEnv = config.services["engine"]?.environment
        assertNotNull(engineEnv)
        assertEquals("/home/klaw", engineEnv["HOME"])
        assertEquals("0.0.0.0", engineEnv["KLAW_ENGINE_BIND"])
    }

    @Test
    fun `dockerComposeJson engine has port mapping`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        val ports = config.services["engine"]?.ports
        assertNotNull(ports, "Expected engine ports")
        assertTrue(ports.contains("127.0.0.1:7470:7470"), "Expected port mapping in: $ports")
    }

    @Test
    fun `dockerComposeJson gateway has KLAW_ENGINE_HOST=engine`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        val gatewayEnv = config.services["gateway"]?.environment
        assertNotNull(gatewayEnv)
        assertEquals("engine", gatewayEnv["KLAW_ENGINE_HOST"])
    }

    @Test
    fun `dockerComposeJson engine has KLAW_HOST_WORKSPACE env var`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/home/pi/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        val engineEnv = config.services["engine"]?.environment
        assertNotNull(engineEnv)
        assertEquals("/home/pi/workspace", engineEnv["KLAW_HOST_WORKSPACE"])
    }

    @Test
    fun `dockerComposeJson does NOT contain cli service`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        assertTrue(config.services["cli"] == null, "Expected no cli service")
    }

    @Test
    fun `dockerComposeJson uses provided absolute paths verbatim`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/custom/state/dir",
                dataPath = "/custom/data/dir",
                configPath = "/custom/config/dir",
                workspacePath = "/custom/workspace/dir",
                imageTag = "unstable",
            )
        assertTrue(result.contains("/custom/state/dir"), "Expected statePath in:\n$result")
        assertTrue(result.contains("/custom/data/dir"), "Expected dataPath in:\n$result")
        assertTrue(result.contains("/custom/config/dir"), "Expected configPath in:\n$result")
        assertTrue(result.contains("/custom/workspace/dir"), "Expected workspacePath in:\n$result")
    }

    @Test
    fun `dockerComposeJson does not contain klaw-run or socket references`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/home/pi/.local/state/klaw",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        assertTrue(
            !result.contains("klaw-run"),
            "Expected no klaw-run volume reference in:\n$result",
        )
        assertTrue(
            !result.contains("engine.sock"),
            "Expected no engine.sock reference in:\n$result",
        )
    }

    @Test
    fun `dockerComposeJson mounts to klaw home paths`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        assertTrue(
            result.contains(":/home/klaw/.local/state/klaw"),
            "Expected klaw home state mount in:\n$result",
        )
        assertTrue(
            result.contains(":/home/klaw/.local/share/klaw"),
            "Expected klaw home data mount in:\n$result",
        )
        assertTrue(
            result.contains(":/home/klaw/.config/klaw"),
            "Expected klaw home config mount in:\n$result",
        )
    }

    // --- dockerComposeProd ---

    @Test
    fun `dockerComposeProd returns valid JSON parseable by parseComposeConfig`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        assertNotNull(config, "Expected parseable ComposeConfig")
    }

    @Test
    fun `dockerComposeProd contains engine and gateway with correct images`() {
        val result = ConfigTemplates.dockerComposeProd("v1.0.0")
        val config = parseComposeConfig(result)
        assertEquals("ghcr.io/sickfar/klaw-engine:v1.0.0", config.services["engine"]?.image)
        assertEquals("ghcr.io/sickfar/klaw-gateway:v1.0.0", config.services["gateway"]?.image)
    }

    @Test
    fun `dockerComposeProd contains 5 named volumes`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        val volumes = config.volumes
        assertNotNull(volumes)
        assertEquals(5, volumes.size, "Expected 5 named volumes, got: ${volumes.keys}")
        assertTrue(volumes.containsKey("klaw-state"))
        assertTrue(volumes.containsKey("klaw-data"))
        assertTrue(volumes.containsKey("klaw-cache"))
        assertTrue(volumes.containsKey("klaw-workspace"))
        assertTrue(volumes.containsKey("klaw-config"))
    }

    @Test
    fun `dockerComposeProd engine has KLAW_WORKSPACE environment`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        assertEquals("/workspace", config.services["engine"]?.environment?.get("KLAW_WORKSPACE"))
    }

    @Test
    fun `dockerComposeProd gateway has depends_on engine`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        assertEquals(listOf("engine"), config.services["gateway"]?.dependsOn)
    }

    @Test
    fun `dockerComposeProd has no env_file and correct TCP env vars`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        assertTrue(config.services["engine"]?.envFile == null, "Expected no env_file in prod")
        assertEquals("0.0.0.0", config.services["engine"]?.environment?.get("KLAW_ENGINE_BIND"))
        assertEquals("engine", config.services["gateway"]?.environment?.get("KLAW_ENGINE_HOST"))
    }

    @Test
    fun `dockerComposeProd uses home klaw paths not root`() {
        val result = ConfigTemplates.dockerComposeProd()
        assertTrue(!result.contains("/root/"), "Expected no /root/ paths in prod compose, got:\n$result")
        assertTrue(result.contains("/home/klaw/.local/state/klaw"), "Expected /home/klaw state path")
        assertTrue(result.contains("/home/klaw/.local/share/klaw"), "Expected /home/klaw data path")
        assertTrue(result.contains("/home/klaw/.config/klaw"), "Expected /home/klaw config path")
        assertTrue(result.contains("/home/klaw/.cache/klaw"), "Expected /home/klaw cache path")
    }

    @Test
    fun `dockerComposeProd sets HOME env var for both services`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        assertEquals("/home/klaw", config.services["engine"]?.environment?.get("HOME"))
        assertEquals("/home/klaw", config.services["gateway"]?.environment?.get("HOME"))
    }

    // --- deployConf ---

    // --- Minimal encoder: default sections omitted ---

    @Test
    fun `engineJson minimal config omits llm section`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(!json.contains("\"llm\""), "Expected no 'llm' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits codeExecution section`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(!json.contains("codeExecution"), "Expected no 'codeExecution' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits autoRag section`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(!json.contains("autoRag"), "Expected no 'autoRag' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits hostExecution section`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(!json.contains("hostExecution"), "Expected no 'hostExecution' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits commands section`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(!json.contains("\"commands\""), "Expected no 'commands' section (empty default) in:\n$json")
    }

    @Test
    fun `engineJson maxToolCallRounds defaults to 50`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        val config = parseEngineConfig(json)
        assertTrue(
            config.processing.maxToolCallRounds == 50,
            "Expected maxToolCallRounds=50, got ${config.processing.maxToolCallRounds}",
        )
    }

    @Test
    fun `gatewayJson minimal config omits commands section`() {
        val json = ConfigTemplates.gatewayJson(allowedChats = emptyList())
        assertTrue(!json.contains("commands"), "Expected no 'commands' section (empty default) in:\n$json")
    }

    @Test
    fun `deployConf for NATIVE latest round-trips`() {
        val result = ConfigTemplates.deployConf(DeployMode.NATIVE, "latest")
        assertTrue(result.contains("mode=native"), "Expected mode=native in:\n$result")
        assertTrue(result.contains("docker_tag=latest"), "Expected docker_tag=latest in:\n$result")
    }

    @Test
    fun `deployConf for HYBRID unstable contains correct values`() {
        val result = ConfigTemplates.deployConf(DeployMode.HYBRID, "unstable")
        assertTrue(result.contains("mode=hybrid"), "Expected mode=hybrid in:\n$result")
        assertTrue(result.contains("docker_tag=unstable"), "Expected docker_tag=unstable in:\n$result")
    }

    // --- Discord ---

    @Test
    fun `gatewayJson with discordEnabled=true includes discord section`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, discordEnabled = true)
        assertTrue(json.contains("discord"), "Expected 'discord' in:\n$json")
        val config = parseGatewayConfig(json)
        assertNotNull(config.channels.discord, "Expected discord config in parsed result")
        assertTrue(config.channels.discord!!.enabled, "Expected discord enabled=true")
    }

    @Test
    fun `gatewayJson with discordEnabled=true uses env var token`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, discordEnabled = true)
        assertTrue(json.contains("KLAW_DISCORD_TOKEN"), "Expected KLAW_DISCORD_TOKEN env var in:\n$json")
    }

    @Test
    fun `gatewayJson with discordEnabled=true and allowedGuilds includes guild IDs`() {
        val json =
            ConfigTemplates.gatewayJson(
                telegramEnabled = false,
                discordEnabled = true,
                discordAllowedGuilds = listOf("111222333", "444555666"),
            )
        val config = parseGatewayConfig(json)
        val guilds = config.channels.discord?.allowedGuilds
        assertNotNull(guilds, "Expected allowedGuilds")
        assertEquals(2, guilds.size, "Expected 2 guilds")
        assertEquals("111222333", guilds[0].guildId)
        assertEquals("444555666", guilds[1].guildId)
    }

    @Test
    fun `gatewayJson with discordEnabled=false omits discord section`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = true, discordEnabled = false)
        assertTrue(!json.contains("discord"), "Expected no 'discord' section in:\n$json")
        val config = parseGatewayConfig(json)
        assertNull(config.channels.discord, "Expected null discord in parsed result")
    }

    @Test
    fun `gatewayJson with discord and telegram both enabled includes both`() {
        val json =
            ConfigTemplates.gatewayJson(
                telegramEnabled = true,
                allowedChats = listOf(AllowedChat("123")),
                discordEnabled = true,
                discordAllowedGuilds = listOf("999"),
            )
        val config = parseGatewayConfig(json)
        assertNotNull(config.channels.telegram, "Expected telegram config")
        assertNotNull(config.channels.discord, "Expected discord config")
        val guilds = config.channels.discord!!.allowedGuilds
        assertEquals("999", guilds.first().guildId)
    }

    @Test
    fun `gatewayJson discord round-trips through parser`() {
        val json =
            ConfigTemplates.gatewayJson(
                telegramEnabled = false,
                discordEnabled = true,
                discordAllowedGuilds = listOf("guild1"),
                enableLocalWs = true,
                localWsPort = 9090,
            )
        val config = parseGatewayConfig(json)
        assertNull(config.channels.telegram, "Expected no telegram")
        assertNotNull(config.channels.discord, "Expected discord")
        assertTrue(config.channels.discord!!.enabled)
        assertNotNull(config.channels.localWs, "Expected localWs")
        assertEquals(9090, config.channels.localWs?.port)
    }
}
