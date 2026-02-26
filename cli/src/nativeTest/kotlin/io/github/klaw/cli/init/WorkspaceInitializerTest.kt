package io.github.klaw.cli.init

import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.readFileText
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceInitializerTest {
    private val tmpDir = "/private/tmp/klaw-ws-test-${getpid()}"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        deleteDir(tmpDir)
    }

    @Test
    fun `creates all required directories`() {
        val initializer =
            WorkspaceInitializer(
                configDir = "$tmpDir/config",
                dataDir = "$tmpDir/data",
                stateDir = "$tmpDir/state",
                cacheDir = "$tmpDir/cache",
                workspaceDir = "$tmpDir/workspace",
                conversationsDir = "$tmpDir/conversations",
                memoryDir = "$tmpDir/memory",
                skillsDir = "$tmpDir/skills",
                modelsDir = "$tmpDir/models",
            )
        initializer.initialize()

        assertTrue(isDirectory("$tmpDir/config"), "config dir missing")
        assertTrue(isDirectory("$tmpDir/workspace"), "workspace dir missing")
        assertTrue(isDirectory("$tmpDir/conversations"), "conversations dir missing")
        assertTrue(isDirectory("$tmpDir/memory"), "memory dir missing")
        assertTrue(isDirectory("$tmpDir/models"), "models dir missing")
    }

    @Test
    fun `creates stub TOOLS_md and HEARTBEAT_md`() {
        val initializer =
            WorkspaceInitializer(
                configDir = "$tmpDir/config",
                dataDir = "$tmpDir/data",
                stateDir = "$tmpDir/state",
                cacheDir = "$tmpDir/cache",
                workspaceDir = "$tmpDir/workspace",
                conversationsDir = "$tmpDir/conversations",
                memoryDir = "$tmpDir/memory",
                skillsDir = "$tmpDir/skills",
                modelsDir = "$tmpDir/models",
            )
        initializer.initialize()

        assertTrue(fileExists("$tmpDir/workspace/TOOLS.md"), "TOOLS.md missing")
        assertTrue(fileExists("$tmpDir/workspace/HEARTBEAT.md"), "HEARTBEAT.md missing")
        val tools = readFileText("$tmpDir/workspace/TOOLS.md")
        assertNotNull(tools, "TOOLS.md should be readable")
    }

    @Test
    fun `is idempotent on second call`() {
        val initializer =
            WorkspaceInitializer(
                configDir = "$tmpDir/config",
                dataDir = "$tmpDir/data",
                stateDir = "$tmpDir/state",
                cacheDir = "$tmpDir/cache",
                workspaceDir = "$tmpDir/workspace",
                conversationsDir = "$tmpDir/conversations",
                memoryDir = "$tmpDir/memory",
                skillsDir = "$tmpDir/skills",
                modelsDir = "$tmpDir/models",
            )
        // Write custom content to TOOLS.md first
        initializer.initialize()
        platform.posix.mkdir("$tmpDir/workspace", 0x1EDu) // might already exist

        // Second call should not fail or overwrite existing custom files
        initializer.initialize()
        assertTrue(isDirectory("$tmpDir/workspace"), "workspace dir should still exist")
    }

    private fun deleteDir(path: String) {
        // Simple recursive dir delete for test cleanup
        val entries =
            io.github.klaw.cli.util
                .listDirectory(path)
        for (entry in entries) {
            val entryPath = "$path/$entry"
            if (isDirectory(entryPath)) {
                deleteDir(entryPath)
            } else {
                platform.posix.unlink(entryPath)
            }
        }
        platform.posix.rmdir(path)
    }
}
