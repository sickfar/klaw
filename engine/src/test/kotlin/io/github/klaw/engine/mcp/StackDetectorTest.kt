package io.github.klaw.engine.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StackDetectorTest {
    @Test
    fun `resolve returns node image for npx`() {
        assertEquals("node:22-alpine", StackDetector.resolve("npx"))
    }

    @Test
    fun `resolve returns node image for node`() {
        assertEquals("node:22-alpine", StackDetector.resolve("node"))
    }

    @Test
    fun `resolve returns python image for uvx`() {
        assertEquals("python:3.12-slim", StackDetector.resolve("uvx"))
    }

    @Test
    fun `resolve returns python image for python`() {
        assertEquals("python:3.12-slim", StackDetector.resolve("python"))
    }

    @Test
    fun `resolve returns python image for python3`() {
        assertEquals("python:3.12-slim", StackDetector.resolve("python3"))
    }

    @Test
    fun `resolve returns null for unknown command`() {
        assertNull(StackDetector.resolve("ruby"))
    }

    @Test
    fun `isDockerCommand returns true for docker`() {
        assertTrue(StackDetector.isDockerCommand("docker"))
    }

    @Test
    fun `isDockerCommand returns false for other commands`() {
        assertFalse(StackDetector.isDockerCommand("npx"))
        assertFalse(StackDetector.isDockerCommand("node"))
        assertFalse(StackDetector.isDockerCommand(""))
    }
}
