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
            isDockerEnv = true,
            composeFile = "/app/docker-compose.yml",
        )

    private fun buildNativeLinuxManager(commands: MutableList<String> = mutableListOf()): ServiceManager =
        ServiceManager(
            printer = {},
            commandRunner = { cmd ->
                commands += cmd
                0
            },
            isDockerEnv = false,
            osFamily = OsFamily.LINUX,
        )

    @Test
    fun `docker mode start engine issues docker compose up -d klaw-engine`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.start(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") && it.contains("klaw-engine") },
            "Expected docker compose up -d klaw-engine, got: $commands",
        )
    }

    @Test
    fun `docker mode stop engine issues docker compose stop klaw-engine`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.stop(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("stop") && it.contains("klaw-engine") },
            "Expected docker compose stop klaw-engine, got: $commands",
        )
    }

    @Test
    fun `docker mode restart engine issues docker compose restart klaw-engine`() {
        val commands = mutableListOf<String>()
        val manager = buildDockerManager(commands)
        manager.restart(KlawService.ENGINE)
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("restart") && it.contains("klaw-engine") },
            "Expected docker compose restart klaw-engine, got: $commands",
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
                    it.contains("klaw-gateway") && it.contains("klaw-engine")
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
                isDockerEnv = true,
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
                isDockerEnv = true,
            )
        val result = manager.stop(KlawService.ENGINE)
        assertFalse(result, "Expected false when commandRunner returns non-zero")
    }
}
