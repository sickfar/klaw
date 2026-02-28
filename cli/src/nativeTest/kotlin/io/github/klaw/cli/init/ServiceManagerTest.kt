package io.github.klaw.cli.init

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalNativeApi::class)
class ServiceManagerTest {
    private fun buildDockerManager(commands: MutableList<String> = mutableListOf()): ServiceManager =
        ServiceManager(
            printer = {},
            commandRunner = { cmd ->
                commands += cmd
                0
            },
            deployMode = DeployMode.DOCKER,
            composeFile = "/app/docker-compose.json",
        )

    private fun buildNativeLinuxManager(commands: MutableList<String> = mutableListOf()): ServiceManager =
        ServiceManager(
            printer = {},
            commandRunner = { cmd ->
                commands += cmd
                0
            },
            deployMode = DeployMode.NATIVE,
            osFamily = OsFamily.LINUX,
        )

    private fun buildHybridManager(
        commands: MutableList<String> = mutableListOf(),
        composeFile: String = "/home/user/.config/klaw/docker-compose.json",
    ): ServiceManager =
        ServiceManager(
            printer = {},
            commandRunner = { cmd ->
                commands += cmd
                0
            },
            deployMode = DeployMode.HYBRID,
            composeFile = composeFile,
        )

    @Test
    fun `docker mode start engine issues docker compose up -d engine`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.start(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") && it.contains(" engine") },
            "Expected docker compose up -d engine, got: $commands",
        )
    }

    @Test
    fun `docker mode stop engine issues docker compose stop engine`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.stop(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("stop") && it.contains(" engine") },
            "Expected docker compose stop engine, got: $commands",
        )
    }

    @Test
    fun `docker mode restart engine issues docker compose restart engine`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.restart(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("restart") && it.contains(" engine") },
            "Expected docker compose restart engine, got: $commands",
        )
    }

    @Test
    fun `docker mode stopAll stops gateway then engine in same command`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.stopAll()
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("stop") &&
                    it.contains("gateway") && it.contains("engine")
            },
            "Expected single docker compose stop for both services, got: $commands",
        )
    }

    @Test
    fun `native linux start engine issues systemctl --user start klaw-engine`() {
        val commands = mutableListOf<String>()
        val manager = buildNativeLinuxManager(commands)
        manager.start(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("systemctl") && it.contains("--user") && it.contains("start") && it.contains("klaw-engine") },
            "Expected systemctl --user start klaw-engine, got: $commands",
        )
    }

    @Test
    fun `native linux stopAll stops both services`() {
        val commands = mutableListOf<String>()
        val manager = buildNativeLinuxManager(commands)
        manager.stopAll()
        assertTrue(
            commands.any {
                it.contains("systemctl") && it.contains("--user") && it.contains("stop") &&
                    it.contains("klaw-gateway") && it.contains("klaw-engine")
            },
            "Expected single systemctl --user stop for both services, got: $commands",
        )
    }

    @Test
    fun `start returns false when command fails`() {
        val manager =
            ServiceManager(
                printer = {},
                commandRunner = { 1 },
                deployMode = DeployMode.DOCKER,
            )
        val result = manager.start(KlawService.ENGINE)
        assertFalse(result, "Expected false when commandRunner returns non-zero")
    }

    @Test
    fun `stop returns false when command fails`() {
        val manager =
            ServiceManager(
                printer = {},
                commandRunner = { 1 },
                deployMode = DeployMode.DOCKER,
            )
        val result = manager.stop(KlawService.ENGINE)
        assertFalse(result, "Expected false when commandRunner returns non-zero")
    }

    @Test
    fun `hybrid mode start issues docker compose with config-dir compose file path`() {
        val commands = mutableListOf<String>()
        val manager = buildHybridManager(commands)
        manager.start(KlawService.ENGINE)
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("up -d") &&
                    it.contains(" engine") && it.contains("/home/user/.config/klaw/docker-compose.json")
            },
            "Expected docker compose up -d with hybrid compose file path, got: $commands",
        )
    }

    @Test
    fun `hybrid mode stop issues compose stop with config-dir path`() {
        val commands = mutableListOf<String>()
        val manager = buildHybridManager(commands)
        manager.stop(KlawService.ENGINE)
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("stop") &&
                    it.contains(" engine") && it.contains("/home/user/.config/klaw/docker-compose.json")
            },
            "Expected docker compose stop with hybrid compose file path, got: $commands",
        )
    }

    @Test
    fun `hybrid mode restart issues compose restart with config-dir path`() {
        val commands = mutableListOf<String>()
        val manager = buildHybridManager(commands)
        manager.restart(KlawService.ENGINE)
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("restart") &&
                    it.contains(" engine") && it.contains("/home/user/.config/klaw/docker-compose.json")
            },
            "Expected docker compose restart with hybrid compose file path, got: $commands",
        )
    }

    @Test
    fun `hybrid mode stopAll uses config-dir path`() {
        val commands = mutableListOf<String>()
        val manager = buildHybridManager(commands)
        manager.stopAll()
        assertTrue(
            commands.any {
                it.contains("docker compose") && it.contains("stop") &&
                    it.contains("gateway") && it.contains("engine") &&
                    it.contains("/home/user/.config/klaw/docker-compose.json")
            },
            "Expected docker compose stop with hybrid compose file path, got: $commands",
        )
    }
}
