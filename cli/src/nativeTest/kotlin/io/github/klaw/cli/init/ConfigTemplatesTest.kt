package io.github.klaw.cli.init

import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigTemplatesTest {
    @Test
    fun `engine yaml template contains provider url`() {
        val yaml = ConfigTemplates.engineYaml("https://api.z.ai/api/paas/v4", "zai/glm-5")
        assertTrue(yaml.contains("https://api.z.ai/api/paas/v4"), "Expected providerUrl in:\n$yaml")
    }

    @Test
    fun `engine yaml api key uses per-provider env var`() {
        val yaml = ConfigTemplates.engineYaml("https://api.z.ai/api/paas/v4", "zai/glm-5")
        assertTrue(yaml.contains("ZAI_API_KEY"), "Expected per-provider env var ZAI_API_KEY in:\n$yaml")
        assertTrue(!yaml.contains("KLAW_LLM_API_KEY"), "Should not contain generic KLAW_LLM_API_KEY in:\n$yaml")
    }

    @Test
    fun `apiKeyEnvVar derives uppercase alias`() {
        assertTrue(ConfigTemplates.apiKeyEnvVar("zai") == "ZAI_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("anthropic") == "ANTHROPIC_API_KEY")
        assertTrue(ConfigTemplates.apiKeyEnvVar("openai") == "OPENAI_API_KEY")
    }

    @Test
    fun `engine yaml template contains model id placeholder`() {
        val yaml = ConfigTemplates.engineYaml("https://api.example.com", "myProvider/my-model")
        assertTrue(yaml.contains("myProvider/my-model"), "Expected full modelId in:\n$yaml")
        assertTrue(yaml.contains("my-model"), "Expected modelId part in:\n$yaml")
    }

    @Test
    fun `gateway yaml template contains telegram channel`() {
        val yaml = ConfigTemplates.gatewayYaml(allowedChatIds = listOf("123456789"))
        assertTrue(yaml.contains("telegram"), "Expected 'telegram' in:\n$yaml")
        assertTrue(yaml.contains("123456789"), "Expected chatId in:\n$yaml")
    }

    @Test
    fun `gateway yaml template with empty chat ids is valid`() {
        val yaml = ConfigTemplates.gatewayYaml(allowedChatIds = emptyList())
        assertTrue(yaml.contains("telegram"), "Expected 'telegram' in:\n$yaml")
        assertTrue(yaml.contains("allowedChatIds"), "Expected 'allowedChatIds' in:\n$yaml")
    }

    @Test
    fun `engine yaml api key uses per-provider env var for custom provider`() {
        val yaml = ConfigTemplates.engineYaml("https://api.example.com", "test/model")
        assertTrue(yaml.contains("TEST_API_KEY"), "Expected per-provider env var TEST_API_KEY in:\n$yaml")
    }

    @Test
    fun `gateway yaml token uses env var reference`() {
        val yaml = ConfigTemplates.gatewayYaml(allowedChatIds = emptyList())
        assertTrue(yaml.contains("KLAW_TELEGRAM_TOKEN"), "Expected env var reference in:\n$yaml")
    }

    @Test
    fun `gatewayYaml with enableConsole=true includes console section with enabled and port`() {
        val yaml = ConfigTemplates.gatewayYaml(enableConsole = true)
        assertTrue(yaml.contains("console:"), "Expected 'console:' in:\n$yaml")
        assertTrue(yaml.contains("enabled: true"), "Expected 'enabled: true' in:\n$yaml")
        assertTrue(yaml.contains("port: 37474"), "Expected default port in:\n$yaml")
    }

    @Test
    fun `gatewayYaml with enableConsole=false omits console section`() {
        val yaml = ConfigTemplates.gatewayYaml(enableConsole = false)
        assertTrue(!yaml.contains("console:"), "Expected no 'console:' section in:\n$yaml")
    }

    @Test
    fun `gatewayYaml with enableConsole=true and custom port uses correct port`() {
        val yaml = ConfigTemplates.gatewayYaml(enableConsole = true, consolePort = 9090)
        assertTrue(yaml.contains("port: 9090"), "Expected custom port in:\n$yaml")
    }

    @Test
    fun `gatewayYaml with chatIds and enableConsole=true includes both sections`() {
        val yaml = ConfigTemplates.gatewayYaml(allowedChatIds = listOf("123456"), enableConsole = true, consolePort = 8080)
        assertTrue(yaml.contains("123456"), "Expected chatId in:\n$yaml")
        assertTrue(yaml.contains("console:"), "Expected console section in:\n$yaml")
        assertTrue(yaml.contains("port: 8080"), "Expected custom port in:\n$yaml")
    }

    // --- Telegram enabled/disabled ---

    @Test
    fun `gatewayYaml with telegramEnabled=false omits telegram section`() {
        val yaml = ConfigTemplates.gatewayYaml(telegramEnabled = false)
        assertTrue(!yaml.contains("telegram"), "Expected no 'telegram' section in:\n$yaml")
    }

    @Test
    fun `gatewayYaml with telegramEnabled=true includes telegram section`() {
        val yaml = ConfigTemplates.gatewayYaml(telegramEnabled = true)
        assertTrue(yaml.contains("telegram"), "Expected 'telegram' section in:\n$yaml")
    }

    @Test
    fun `gatewayYaml with telegramEnabled=false and enableConsole=true has console but no telegram`() {
        val yaml = ConfigTemplates.gatewayYaml(telegramEnabled = false, enableConsole = true)
        assertTrue(!yaml.contains("telegram"), "Expected no telegram in:\n$yaml")
        assertTrue(yaml.contains("console:"), "Expected console section in:\n$yaml")
    }

    @Test
    fun `gatewayYaml telegramEnabled=false with console produces valid yaml structure`() {
        val yaml = ConfigTemplates.gatewayYaml(telegramEnabled = false, enableConsole = true, consolePort = 8888)
        assertTrue(yaml.contains("channels:"), "Expected 'channels:' in:\n$yaml")
        assertTrue(yaml.contains("port: 8888"), "Expected port in:\n$yaml")
    }

    // --- dockerComposeHybrid ---

    @Test
    fun `dockerComposeHybrid contains engine image with bind mount paths`() {
        val result =
            ConfigTemplates.dockerComposeHybrid(
                statePath = "/home/pi/.local/state/klaw",
                dataPath = "/home/pi/.local/share/klaw",
                configPath = "/home/pi/.config/klaw",
                workspacePath = "/home/pi/workspace",
                imageTag = "latest",
            )
        assertTrue(result.contains("/home/pi/.local/state/klaw:"), "Expected bind mount syntax in:\n$result")
        assertTrue(result.contains("/home/pi/.local/share/klaw:"), "Expected bind mount syntax in:\n$result")
    }

    @Test
    fun `dockerComposeHybrid does NOT contain a cli service`() {
        val result =
            ConfigTemplates.dockerComposeHybrid(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        assertTrue(!result.contains("cli:"), "Expected no cli service in:\n$result")
    }

    @Test
    fun `dockerComposeHybrid uses provided absolute paths verbatim`() {
        val result =
            ConfigTemplates.dockerComposeHybrid(
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
    fun `dockerComposeHybrid does NOT contain top-level volumes section`() {
        val result =
            ConfigTemplates.dockerComposeHybrid(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "latest",
            )
        val lines = result.lines()
        val topLevelVolumes = lines.any { it.matches(Regex("^volumes:.*")) }
        assertTrue(!topLevelVolumes, "Expected no top-level volumes: section in:\n$result")
    }

    @Test
    fun `dockerComposeHybrid image tag is baked in`() {
        val result =
            ConfigTemplates.dockerComposeHybrid(
                statePath = "/state",
                dataPath = "/data",
                configPath = "/config",
                workspacePath = "/workspace",
                imageTag = "v0.5.0",
            )
        assertTrue(result.contains("klaw-engine:v0.5.0"), "Expected engine tag in:\n$result")
        assertTrue(result.contains("klaw-gateway:v0.5.0"), "Expected gateway tag in:\n$result")
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
