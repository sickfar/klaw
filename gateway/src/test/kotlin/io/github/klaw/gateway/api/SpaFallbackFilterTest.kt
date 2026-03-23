package io.github.klaw.gateway.api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaFallbackFilterTest {
    @Test
    fun `shouldSkip returns true for API routes`() {
        assertTrue(SpaFallbackFilter.shouldSkip("/api/v1/status"))
        assertTrue(SpaFallbackFilter.shouldSkip("/api/v1/sessions"))
        assertTrue(SpaFallbackFilter.shouldSkip("/api"))
    }

    @Test
    fun `shouldSkip returns true for WebSocket path`() {
        assertTrue(SpaFallbackFilter.shouldSkip("/ws/chat"))
        assertTrue(SpaFallbackFilter.shouldSkip("/ws/other"))
    }

    @Test
    fun `shouldSkip returns true for upload path`() {
        assertTrue(SpaFallbackFilter.shouldSkip("/upload"))
    }

    @Test
    fun `shouldSkip returns true for nuxt assets`() {
        assertTrue(SpaFallbackFilter.shouldSkip("/_nuxt/entry.js"))
        assertTrue(SpaFallbackFilter.shouldSkip("/_nuxt/style.css"))
    }

    @Test
    fun `shouldSkip returns true for health`() {
        assertTrue(SpaFallbackFilter.shouldSkip("/health"))
    }

    @Test
    fun `shouldSkip returns true for paths with file extensions`() {
        assertTrue(SpaFallbackFilter.shouldSkip("/favicon.ico"))
        assertTrue(SpaFallbackFilter.shouldSkip("/robots.txt"))
        assertTrue(SpaFallbackFilter.shouldSkip("/some/path/file.css"))
    }

    @Test
    fun `shouldSkip returns false for SPA client routes`() {
        assertFalse(SpaFallbackFilter.shouldSkip("/"))
        assertFalse(SpaFallbackFilter.shouldSkip("/chat"))
        assertFalse(SpaFallbackFilter.shouldSkip("/dashboard"))
        assertFalse(SpaFallbackFilter.shouldSkip("/memory"))
        assertFalse(SpaFallbackFilter.shouldSkip("/schedule"))
        assertFalse(SpaFallbackFilter.shouldSkip("/sessions"))
        assertFalse(SpaFallbackFilter.shouldSkip("/skills"))
        assertFalse(SpaFallbackFilter.shouldSkip("/config"))
    }
}
