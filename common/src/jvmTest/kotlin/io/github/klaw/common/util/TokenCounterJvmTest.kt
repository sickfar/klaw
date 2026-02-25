package io.github.klaw.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenCounterJvmTest {
    @Test
    fun `empty string returns 0`() {
        assertEquals(0, approximateTokenCount(""))
    }

    @Test
    fun `non-empty input returns positive count`() {
        assertTrue(approximateTokenCount("hello") > 0)
    }

    @Test
    fun `Hello comma world exclamation is 4 tokens cl100k_base`() {
        // JTokkit cl100k_base: "Hello, world!" = 4 tokens
        assertEquals(4, approximateTokenCount("Hello, world!"))
    }

    @Test
    fun `The quick brown fox is 4 tokens cl100k_base`() {
        assertEquals(4, approximateTokenCount("The quick brown fox"))
    }

    @Test
    fun `100 word English paragraph approximately matches gpt reference`() {
        val text =
            "The development of artificial intelligence has transformed numerous industries " +
                "over the past decade. From healthcare to finance, AI systems are now capable of " +
                "performing complex tasks that were previously thought to require human intelligence. " +
                "Machine learning algorithms analyze vast amounts of data to identify patterns and " +
                "make predictions. Natural language processing enables computers to understand and " +
                "generate human text. Computer vision allows machines to interpret visual information " +
                "from images and videos. These capabilities are continuously improving as researchers " +
                "develop more sophisticated neural network architectures and training techniques."
        val tokens = approximateTokenCount(text)
        // Reference count ~94 tokens (cl100k_base, countTokensOrdinary), allow +-15
        assertTrue(tokens in 79..109, "Expected ~94 tokens, got $tokens")
    }
}
