package io.github.klaw.engine.memory

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.LongBuffer

class OnnxEmbeddingServiceTest {
    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `tensor cleanup - first tensor closed when second creation throws`() {
        val tensor1 = mockk<OnnxTensor>(relaxed = true)
        val mockEnv = mockk<OrtEnvironment>(relaxed = true)
        val mockSession = mockk<OrtSession>(relaxed = true)
        val mockEncoding = mockk<Encoding>(relaxed = true)
        val mockTokenizer = mockk<HuggingFaceTokenizer>(relaxed = true)

        every { mockTokenizer.encode(any<String>()) } returns mockEncoding
        every { mockEncoding.ids } returns LongArray(3) { it.toLong() }
        every { mockEncoding.attentionMask } returns LongArray(3) { 1L }

        mockkStatic(OnnxTensor::class) {
            var callCount = 0
            every { OnnxTensor.createTensor(any(), any<LongBuffer>(), any()) } answers {
                callCount++
                when (callCount) {
                    1 -> tensor1
                    else -> throw RuntimeException("simulated second tensor creation failure")
                }
            }

            val service = OnnxEmbeddingService(mockEnv, mockSession, mockTokenizer)

            assertThrows<RuntimeException> {
                runBlocking { service.embed("test") }
            }

            // The first tensor MUST be closed even though second tensor creation failed
            verify { tensor1.close() }
        }
    }

    @Test
    fun `tensor cleanup - all tensors closed when session run throws`() {
        val tensor1 = mockk<OnnxTensor>(relaxed = true)
        val tensor2 = mockk<OnnxTensor>(relaxed = true)
        val tensor3 = mockk<OnnxTensor>(relaxed = true)
        val mockEnv = mockk<OrtEnvironment>(relaxed = true)
        val mockSession = mockk<OrtSession>(relaxed = true)
        val mockEncoding = mockk<Encoding>(relaxed = true)
        val mockTokenizer = mockk<HuggingFaceTokenizer>(relaxed = true)

        every { mockTokenizer.encode(any<String>()) } returns mockEncoding
        every { mockEncoding.ids } returns LongArray(3) { it.toLong() }
        every { mockEncoding.attentionMask } returns LongArray(3) { 1L }
        every { mockSession.run(any()) } throws RuntimeException("session run failed")

        mockkStatic(OnnxTensor::class) {
            var callCount = 0
            every { OnnxTensor.createTensor(any(), any<LongBuffer>(), any()) } answers {
                callCount++
                when (callCount) {
                    1 -> tensor1
                    2 -> tensor2
                    else -> tensor3
                }
            }

            val service = OnnxEmbeddingService(mockEnv, mockSession, mockTokenizer)

            assertThrows<RuntimeException> {
                runBlocking { service.embed("test") }
            }

            // All three tensors must be closed
            verify { tensor1.close() }
            verify { tensor2.close() }
            verify { tensor3.close() }
        }
    }
}
