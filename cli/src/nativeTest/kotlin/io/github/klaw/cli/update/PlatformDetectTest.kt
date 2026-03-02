package io.github.klaw.cli.update

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformDetectTest {
    @Test
    fun `cliAssetName returns non-blank string with klaw prefix`() {
        val name = cliAssetName()
        assertTrue(name.isNotBlank(), "cliAssetName() should not be blank")
        assertTrue(name.startsWith("klaw-"), "cliAssetName() should start with 'klaw-'")
    }

    @Test
    fun `cliAssetName contains os family`() {
        val name = cliAssetName()
        // On macOS dev machine this should contain "macos"
        // On Linux it should contain "linux"
        val hasOs = name.contains("macos") || name.contains("linux")
        assertTrue(hasOs, "cliAssetName() should contain 'macos' or 'linux', got: $name")
    }

    @Test
    fun `cliAssetName contains architecture`() {
        val name = cliAssetName()
        val hasArch = name.contains("Arm64") || name.contains("X64")
        assertTrue(hasArch, "cliAssetName() should contain 'Arm64' or 'X64', got: $name")
    }

    @Test
    fun `jarAssetPrefix returns correct prefix`() {
        val prefix = jarAssetPrefix("engine")
        assertTrue(prefix == "klaw-engine-", "jarAssetPrefix('engine') should be 'klaw-engine-', got: $prefix")
    }

    @Test
    fun `jarAssetPrefix for gateway returns correct prefix`() {
        val prefix = jarAssetPrefix("gateway")
        assertTrue(prefix == "klaw-gateway-", "jarAssetPrefix('gateway') should be 'klaw-gateway-', got: $prefix")
    }
}
