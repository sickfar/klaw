package io.github.klaw.cli.init

import io.github.klaw.common.config.parseComposeConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigTemplatesTest {
    @Test
    fun `engine json template contains provider url`() {
        val json = ConfigTemplates.engineJson("https://api.z.ai/api/paas/v4", "zai/glm-5")
        assertTrue(json.contains("https://api.z.ai/api/paas/v4"), "Expected providerUrl in:\n$json")
    }

    @Test
    fun `engine json api key uses per-provider env var`() {
        val json = ConfigTemplates.engineJson("https://api.z.ai/api/paas/v4", "zai/glm-5")
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
        val json = ConfigTemplates.gatewayJson(allowedChatIds = listOf("123456789"))
        assertTrue(json.contains("telegram"), "Expected 'telegram' in:\n$json")
        assertTrue(json.contains("123456789"), "Expected chatId in:\n$json")
    }

    @Test
    fun `gateway json template with empty chat ids is valid`() {
        val json = ConfigTemplates.gatewayJson(allowedChatIds = emptyList())
        assertTrue(json.contains("telegram"), "Expected 'telegram' in:\n$json")
        assertTrue(json.contains("allowedChatIds"), "Expected 'allowedChatIds' in:\n$json")
    }

    @Test
    fun `engine json api key uses per-provider env var for custom provider`() {
        val json = ConfigTemplates.engineJson("https://api.example.com", "test/model")
        assertTrue(json.contains("TEST_API_KEY"), "Expected per-provider env var TEST_API_KEY in:\n$json")
    }

    @Test
    fun `gateway json token uses env var reference`() {
        val json = ConfigTemplates.gatewayJson(allowedChatIds = emptyList())
        assertTrue(json.contains("KLAW_TELEGRAM_TOKEN"), "Expected env var reference in:\n$json")
    }

    @Test
    fun `gatewayJson with enableConsole=true includes console section`() {
        val json = ConfigTemplates.gatewayJson(enableConsole = true)
        assertTrue(json.contains("console"), "Expected 'console' in:\n$json")
        assertTrue(json.contains("true"), "Expected 'true' (enabled) in:\n$json")
        assertTrue(json.contains("37474"), "Expected default port in:\n$json")
    }

    @Test
    fun `gatewayJson with enableConsole=false omits console section`() {
        val json = ConfigTemplates.gatewayJson(enableConsole = false)
        assertTrue(!json.contains("console"), "Expected no 'console' section in:\n$json")
    }

    @Test
    fun `gatewayJson with enableConsole=true and custom port uses correct port`() {
        val json = ConfigTemplates.gatewayJson(enableConsole = true, consolePort = 9090)
        assertTrue(json.contains("9090"), "Expected custom port in:\n$json")
    }

    @Test
    fun `gatewayJson with chatIds and enableConsole=true includes both sections`() {
        val json = ConfigTemplates.gatewayJson(allowedChatIds = listOf("123456"), enableConsole = true, consolePort = 8080)
        assertTrue(json.contains("123456"), "Expected chatId in:\n$json")
        assertTrue(json.contains("console"), "Expected console section in:\n$json")
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
    fun `gatewayJson with telegramEnabled=false and enableConsole=true has console but no telegram`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, enableConsole = true)
        assertTrue(!json.contains("telegram"), "Expected no telegram in:\n$json")
        assertTrue(json.contains("console"), "Expected console section in:\n$json")
    }

    @Test
    fun `gatewayJson telegramEnabled=false with console produces valid json`() {
        val json = ConfigTemplates.gatewayJson(telegramEnabled = false, enableConsole = true, consolePort = 8888)
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
        val json = ConfigTemplates.gatewayJson(allowedChatIds = listOf("123"), enableConsole = true, consolePort = 9090)
        val config = parseGatewayConfig(json)
        assertTrue(config.channels.telegram != null, "Expected telegram section")
        assertTrue(config.channels.console != null, "Expected console section")
        assertTrue(config.channels.console?.port == 9090, "Expected port 9090")
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
    fun `dockerComposeJson contains klaw-run named volume`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val config = parseComposeConfig(result)
        val volumes = config.volumes
        assertNotNull(volumes, "Expected top-level volumes")
        assertEquals("klaw-run", volumes["klaw-run"]?.name)
    }

    @Test
    fun `dockerComposeJson sets HOME KLAW_SOCKET_PATH KLAW_SOCKET_PERMS environment`() {
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
        assertEquals("/home/klaw/.local/state/klaw/run/engine.sock", engineEnv["KLAW_SOCKET_PATH"])
        assertEquals("rw-rw-rw-", engineEnv["KLAW_SOCKET_PERMS"])
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
    fun `dockerComposeJson uses named volume for socket run dir`() {
        val result =
            ConfigTemplates.dockerComposeJson(
                statePath = "/home/pi/.local/state/klaw",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        assertTrue(
            result.contains("klaw-run:/home/klaw/.local/state/klaw/run"),
            "Expected named klaw-run volume mount in:\n$result",
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
            result.contains(":/home/klaw/.config/klaw:ro"),
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
    fun `dockerComposeProd contains 4 named volumes`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        val volumes = config.volumes
        assertNotNull(volumes)
        assertEquals(4, volumes.size, "Expected 4 named volumes, got: ${volumes.keys}")
        assertTrue(volumes.containsKey("klaw-state"))
        assertTrue(volumes.containsKey("klaw-data"))
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
    fun `dockerComposeProd has no env_file and no KLAW_SOCKET_PERMS`() {
        val result = ConfigTemplates.dockerComposeProd()
        val config = parseComposeConfig(result)
        assertTrue(config.services["engine"]?.envFile == null, "Expected no env_file in prod")
        assertTrue(
            config.services["engine"]?.environment?.containsKey("KLAW_SOCKET_PERMS") != true,
            "Expected no KLAW_SOCKET_PERMS in prod",
        )
    }

    // --- deployConf ---

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
}
