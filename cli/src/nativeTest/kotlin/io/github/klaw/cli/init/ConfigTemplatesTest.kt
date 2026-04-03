package io.github.klaw.cli.init

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.parseComposeConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTemplatesTest {
    @Test
    fun `engine json for known provider omits endpoint`() {
        val json = ConfigTemplates.engineJson("zai/glm-5")
        assertTrue(!json.contains("endpoint"), "Known provider should not have explicit endpoint in:\n$json")
    }

    @Test
    fun `engine json api key uses per-provider env var`() {
        val json = ConfigTemplates.engineJson("zai/glm-5")
        assertTrue(json.contains("ZAI_API_KEY"), "Expected per-provider env var ZAI_API_KEY in:\n$json")
        assertTrue(!json.contains("KLAW_LLM_API_KEY"), "Should not contain generic KLAW_LLM_API_KEY in:\n$json")
    }

    @Test
    fun `apiKeyEnvVar derives uppercase alias`() {
        assertTrue(ConfigTemplates.apiKeyEnvVar("zai") == "ZAI_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("anthropic") == "ANTHROPIC_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("openai") == "OPENAI_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("kimi-code") == "KIMI_CODE_API_KEY")
    }

    @Test
    fun `engine json template contains model id`() {
        val json = ConfigTemplates.engineJson("myProvider/my-model")
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
        val allowedChats =
            config.channels.telegram.values
                .firstOrNull()
                ?.allowedChats
        assertTrue(allowedChats?.isEmpty() == true, "Expected empty allowedChats in parsed config")
    }

    @Test
    fun `engine json api key uses per-provider env var for custom provider`() {
        val json = ConfigTemplates.engineJson("test/model")
        assertTrue(json.contains("TEST_API_KEY"), "Expected per-provider env var TEST_API_KEY in:\n$json")
    }

    @Test
    fun `gateway json token uses env var reference`() {
        val json = ConfigTemplates.gatewayJson(allowedChats = emptyList())
        assertTrue(json.contains("KLAW_TELEGRAM_TOKEN"), "Expected env var reference in:\n$json")
    }

    @Test
    fun `gatewayJson with enableLocalWs=true includes websocket section`() {
        val json = ConfigTemplates.gatewayJson(enableLocalWs = true)
        assertTrue(json.contains("websocket"), "Expected 'websocket' in:\n$json")
        // Verify via round-trip parse instead of fragile string checks
        val config = parseGatewayConfig(json)
        assertTrue(config.channels.websocket.isNotEmpty(), "Expected websocket configured")
        assertTrue(
            config.channels.websocket.values
                .firstOrNull()
                ?.port == 37474,
            "Expected default port 37474 in parsed config",
        )
    }

    @Test
    fun `gatewayJson with enableLocalWs=false omits websocket section`() {
        val json = ConfigTemplates.gatewayJson(enableLocalWs = false)
        assertTrue(!json.contains("websocket"), "Expected no 'websocket' section in:\n$json")
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
        assertTrue(json.contains("websocket"), "Expected websocket section in:\n$json")
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
    fun `gatewayJson with telegramEnabled=false and enableLocalWs=true has websocket but no telegram`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, enableLocalWs = true)
        assertTrue(!json.contains("telegram"), "Expected no telegram in:\n$json")
        assertTrue(json.contains("websocket"), "Expected websocket section in:\n$json")
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
        val json = ConfigTemplates.engineJson("anthropic/claude-sonnet-4-5-20250514")
        val config = parseEngineConfig(json)
        assertTrue(config.providers.containsKey("anthropic"), "Expected provider 'anthropic' in parsed config")
        assertTrue(
            config.routing.default == "anthropic/claude-sonnet-4-5-20250514",
            "Expected routing default 'anthropic/claude-sonnet-4-5-20250514'",
        )
    }

    @Test
    fun `engineJson produces agents section with default agent`() {
        val json = ConfigTemplates.engineJson("test/model", workspace = "/my/workspace")
        val config = parseEngineConfig(json)
        assertTrue(config.agents.containsKey("default"), "Expected 'default' agent in agents map")
        assertEquals("/my/workspace", config.agents["default"]?.workspace, "Expected workspace in default agent")
    }

    @Test
    fun `engineJson agents section uses fallback workspace when workspace is null`() {
        val json = ConfigTemplates.engineJson("test/model", workspace = null)
        val config = parseEngineConfig(json)
        assertTrue(config.agents.containsKey("default"), "Expected 'default' agent in agents map")
        val workspace = config.agents["default"]?.workspace
        assertTrue(workspace != null && workspace.isNotBlank(), "Expected non-blank workspace in default agent")
    }

    @Test
    fun `gatewayJson telegram channel has agentId=default`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = true)
        val config = parseGatewayConfig(json)
        val tg = config.channels.telegram.values.firstOrNull()
        assertNotNull(tg, "Expected telegram channel")
        assertEquals("default", tg.agentId, "Expected agentId=default in telegram channel")
    }

    @Test
    fun `gatewayJson websocket channel has agentId=default`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, enableLocalWs = true)
        val config = parseGatewayConfig(json)
        val ws = config.channels.websocket.values.firstOrNull()
        assertNotNull(ws, "Expected websocket channel")
        assertEquals("default", ws.agentId, "Expected agentId=default in websocket channel")
    }

    @Test
    fun `gatewayJson discord channel has agentId=default`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, discordEnabled = true)
        val config = parseGatewayConfig(json)
        val dc = config.channels.discord.values.firstOrNull()
        assertNotNull(dc, "Expected discord channel")
        assertEquals("default", dc.agentId, "Expected agentId=default in discord channel")
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
        assertTrue(config.channels.telegram.isNotEmpty(), "Expected telegram section")
        assertTrue(config.channels.websocket.isNotEmpty(), "Expected websocket section")
        assertTrue(
            config.channels.websocket.values
                .firstOrNull()
                ?.port == 9090,
            "Expected port 9090",
        )
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
        val json = ConfigTemplates.engineJson("test/model")
        assertTrue(!json.contains("\"llm\""), "Expected no 'llm' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits codeExecution section`() {
        val json = ConfigTemplates.engineJson("test/model")
        assertTrue(!json.contains("codeExecution"), "Expected no 'codeExecution' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits autoRag section`() {
        val json = ConfigTemplates.engineJson("test/model")
        assertTrue(!json.contains("autoRag"), "Expected no 'autoRag' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits hostExecution section`() {
        val json = ConfigTemplates.engineJson("test/model")
        assertTrue(!json.contains("hostExecution"), "Expected no 'hostExecution' section (all defaults) in:\n$json")
    }

    @Test
    fun `engineJson with hostExecutionEnabled=true includes hostExecution section`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "test/model",
                hostExecutionEnabled = true,
            )
        assertTrue(json.contains("hostExecution"), "Expected 'hostExecution' section in:\n$json")
        val config = parseEngineConfig(json)
        assertTrue(config.hostExecution.enabled, "Expected hostExecution.enabled=true")
    }

    @Test
    fun `engineJson with hostExecutionEnabled=false omits hostExecution section`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "test/model",
                hostExecutionEnabled = false,
            )
        assertTrue(!json.contains("hostExecution"), "Expected no 'hostExecution' section in:\n$json")
    }

    @Test
    fun `engineJson minimal config omits commands section`() {
        val json = ConfigTemplates.engineJson("test/model")
        assertTrue(!json.contains("\"commands\""), "Expected no 'commands' section (empty default) in:\n$json")
    }

    @Test
    fun `engineJson maxToolCallRounds defaults to 50`() {
        val json = ConfigTemplates.engineJson("test/model")
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

    // --- Anthropic provider ---

    @Test
    fun `engineJson with known provider omits type and endpoint`() {
        val json = ConfigTemplates.engineJson("anthropic/claude-sonnet-4-5-20250514")
        val config = parseEngineConfig(json)
        assertNull(config.providers["anthropic"]?.type, "Known provider should not have explicit type")
        assertNull(config.providers["anthropic"]?.endpoint, "Known provider should not have explicit endpoint")
    }

    @Test
    fun `engineJson with anthropic uses ANTHROPIC_API_KEY`() {
        val json = ConfigTemplates.engineJson("anthropic/claude-sonnet-4-5-20250514")
        assertTrue(json.contains("ANTHROPIC_API_KEY"), "Expected ANTHROPIC_API_KEY in:\n$json")
    }

    @Test
    fun `engineJson with anthropic round-trips through parser`() {
        val json = ConfigTemplates.engineJson("anthropic/claude-sonnet-4-5-20250514")
        val config = parseEngineConfig(json)
        assertTrue(config.providers.containsKey("anthropic"))
        assertEquals("anthropic/claude-sonnet-4-5-20250514", config.routing.default)
    }

    @Test
    fun `engineJson provider config only contains apiKey for known provider`() {
        val json = ConfigTemplates.engineJson("zai/glm-5")
        val config = parseEngineConfig(json)
        assertNotNull(config.providers["zai"]?.apiKey, "Expected apiKey")
        assertNull(config.providers["zai"]?.type, "Known provider should not have explicit type")
        assertNull(config.providers["zai"]?.endpoint, "Known provider should not have explicit endpoint")
    }

    // --- Vision config ---

    @Test
    fun `engineJson with visionModelId sets vision enabled and model`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "zai/glm-5",
                visionModelId = "zai/glm-4.6v",
                attachmentsDirectory = "/data/attachments",
            )
        val config = parseEngineConfig(json)
        assertTrue(config.vision.enabled, "Expected vision.enabled=true")
        assertEquals("zai/glm-4.6v", config.vision.model, "Expected vision.model=zai/glm-4.6v")
        assertEquals("/data/attachments", config.vision.attachmentsDirectory, "Expected attachmentsDirectory")
    }

    @Test
    fun `engineJson with visionModelId adds vision model to models map`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "zai/glm-5",
                visionModelId = "zai/glm-4.6v",
                attachmentsDirectory = "/data/attachments",
            )
        val config = parseEngineConfig(json)
        assertTrue(config.models.containsKey("zai/glm-5"), "Expected main model in models map")
        assertTrue(config.models.containsKey("zai/glm-4.6v"), "Expected vision model in models map")
    }

    @Test
    fun `engineJson without visionModelId omits vision section`() {
        val json = ConfigTemplates.engineJson("zai/glm-5")
        assertFalse(json.contains("\"vision\""), "Expected no vision section in:\n$json")
    }

    @Test
    fun `gatewayJson with attachmentsDirectory sets attachments config`() {
        val json = ConfigTemplates.gatewayJson(attachmentsDirectory = "/path/to/attachments")
        val config = parseGatewayConfig(json)
        assertEquals("/path/to/attachments", config.attachments.directory, "Expected attachments.directory")
    }

    @Test
    fun `gatewayJson without attachmentsDirectory omits attachments section`() {
        val json = ConfigTemplates.gatewayJson()
        assertFalse(json.contains("\"attachments\""), "Expected no attachments section in:\n$json")
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
        assertTrue(config.channels.discord.isNotEmpty(), "Expected discord config in parsed result")
        assertNotNull(
            config.channels.discord.values
                .firstOrNull(),
            "Expected discord entry",
        )
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
        val guilds =
            config.channels.discord.values
                .firstOrNull()
                ?.allowedGuilds
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
        assertTrue(config.channels.discord.isEmpty(), "Expected empty discord in parsed result")
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
        assertTrue(config.channels.telegram.isNotEmpty(), "Expected telegram config")
        assertTrue(config.channels.discord.isNotEmpty(), "Expected discord config")
        val guilds =
            config.channels.discord.values
                .firstOrNull()
                ?.allowedGuilds ?: emptyList()
        assertEquals("999", guilds.first().guildId)
    }

    // --- Pre-validation model ---

    @Test
    fun `engineJson with hostExecution and preValidationModel includes model in config`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "test/model",
                hostExecutionEnabled = true,
                preValidationModel = "anthropic/claude-sonnet-4-5-20250514",
            )
        val config = parseEngineConfig(json)
        assertTrue(config.hostExecution.enabled, "Expected hostExecution.enabled=true")
        assertTrue(
            config.hostExecution.preValidation.enabled,
            "Expected preValidation.enabled=true",
        )
        assertEquals(
            "anthropic/claude-sonnet-4-5-20250514",
            config.hostExecution.preValidation.model,
            "Expected preValidation.model to match",
        )
    }

    @Test
    fun `engineJson with hostExecution and null preValidationModel uses default empty model`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "test/model",
                hostExecutionEnabled = true,
                preValidationModel = null,
            )
        val config = parseEngineConfig(json)
        assertTrue(config.hostExecution.enabled, "Expected hostExecution.enabled=true")
        assertEquals(
            "",
            config.hostExecution.preValidation.model,
            "Expected default empty model",
        )
    }

    @Test
    fun `engineJson with hostExecution disabled ignores preValidationModel`() {
        val json =
            ConfigTemplates.engineJson(
                modelId = "test/model",
                hostExecutionEnabled = false,
                preValidationModel = "anthropic/claude-sonnet-4-5-20250514",
            )
        val config = parseEngineConfig(json)
        assertFalse(config.hostExecution.enabled, "Expected hostExecution.enabled=false")
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
        assertTrue(config.channels.telegram.isEmpty(), "Expected no telegram")
        assertTrue(config.channels.discord.isNotEmpty(), "Expected discord")
        assertNotNull(
            config.channels.discord.values
                .firstOrNull(),
            "Expected discord entry",
        )
        assertTrue(config.channels.websocket.isNotEmpty(), "Expected websocket")
        assertEquals(
            9090,
            config.channels.websocket.values
                .firstOrNull()
                ?.port,
        )
    }
}
