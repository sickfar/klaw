package io.github.klaw.cli.init

import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigTemplatesTest {
    @Test
    fun `engine yaml template contains provider url`() {
        val yaml = ConfigTemplates.engineYaml("https://open.bigmodel.cn/api/paas/v4", "glm/glm-4-plus")
        assertTrue(yaml.contains("https://open.bigmodel.cn/api/paas/v4"), "Expected providerUrl in:\n$yaml")
    }

    @Test
    fun `engine yaml template contains model id placeholder`() {
        val yaml = ConfigTemplates.engineYaml("https://api.example.com", "myProvider/my-model")
        assertTrue(yaml.contains("myProvider/my-model"), "Expected full modelId in:\n$yaml")
        assertTrue(yaml.contains("my-model"), "Expected modelId part in:\n$yaml")
    }

    @Test
    fun `gateway yaml template contains telegram channel`() {
        val yaml = ConfigTemplates.gatewayYaml(listOf("123456789"))
        assertTrue(yaml.contains("telegram"), "Expected 'telegram' in:\n$yaml")
        assertTrue(yaml.contains("123456789"), "Expected chatId in:\n$yaml")
    }

    @Test
    fun `gateway yaml template with empty chat ids is valid`() {
        val yaml = ConfigTemplates.gatewayYaml(emptyList())
        assertTrue(yaml.contains("telegram"), "Expected 'telegram' in:\n$yaml")
        assertTrue(yaml.contains("allowedChatIds"), "Expected 'allowedChatIds' in:\n$yaml")
    }

    @Test
    fun `engine yaml api key uses env var reference`() {
        val yaml = ConfigTemplates.engineYaml("https://api.example.com", "test/model")
        assertTrue(yaml.contains("KLAW_LLM_API_KEY"), "Expected env var reference in:\n$yaml")
    }

    @Test
    fun `gateway yaml token uses env var reference`() {
        val yaml = ConfigTemplates.gatewayYaml(emptyList())
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
        val yaml = ConfigTemplates.gatewayYaml(listOf("123456"), enableConsole = true, consolePort = 8080)
        assertTrue(yaml.contains("123456"), "Expected chatId in:\n$yaml")
        assertTrue(yaml.contains("console:"), "Expected console section in:\n$yaml")
        assertTrue(yaml.contains("port: 8080"), "Expected custom port in:\n$yaml")
    }
}
