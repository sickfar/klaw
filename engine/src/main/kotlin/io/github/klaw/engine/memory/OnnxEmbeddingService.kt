package io.github.klaw.engine.memory

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import java.nio.file.Path

class OnnxEmbeddingService internal constructor(
    private val env: ai.onnxruntime.OrtEnvironment,
    private val session: ai.onnxruntime.OrtSession,
    private val tokenizer: HuggingFaceTokenizer,
) : EmbeddingService {
    constructor(
        modelDir: Path =
            Path.of(
                System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache",
                "klaw",
                "models",
                "all-MiniLM-L6-v2",
            ),
    ) : this(
        env = ai.onnxruntime.OrtEnvironment.getEnvironment(),
        session = createSession(ai.onnxruntime.OrtEnvironment.getEnvironment(), modelDir),
        tokenizer = createTokenizer(modelDir),
    )

    companion object {
        private fun createSession(
            env: ai.onnxruntime.OrtEnvironment,
            modelDir: Path,
        ): ai.onnxruntime.OrtSession {
            val modelPath = modelDir.resolve("model.onnx")
            require(modelPath.toFile().exists()) { "ONNX model not found: $modelPath" }
            return env.createSession(modelPath.toString())
        }

        private fun createTokenizer(modelDir: Path): HuggingFaceTokenizer {
            val tokenizerPath = modelDir.resolve("tokenizer.json")
            require(tokenizerPath.toFile().exists()) { "Tokenizer not found: $tokenizerPath" }
            return HuggingFaceTokenizer.newInstance(tokenizerPath)
        }
    }

    @Suppress("LongMethod")
    override suspend fun embed(text: String): FloatArray =
        withContext(Dispatchers.VT) {
            val encoding = tokenizer.encode(text)
            val inputIds = encoding.ids
            val attentionMask = encoding.attentionMask
            val tokenTypeIds = LongArray(inputIds.size) { 0L }
            val seqLen = inputIds.size.toLong()

            var inputIdsTensor: ai.onnxruntime.OnnxTensor? = null
            var attentionMaskTensor: ai.onnxruntime.OnnxTensor? = null
            var tokenTypeIdsTensor: ai.onnxruntime.OnnxTensor? = null
            try {
                inputIdsTensor =
                    ai.onnxruntime.OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(inputIds),
                        longArrayOf(1, seqLen),
                    )
                attentionMaskTensor =
                    ai.onnxruntime.OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(attentionMask),
                        longArrayOf(1, seqLen),
                    )
                tokenTypeIdsTensor =
                    ai.onnxruntime.OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(tokenTypeIds),
                        longArrayOf(1, seqLen),
                    )

                val inputs =
                    mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "token_type_ids" to tokenTypeIdsTensor,
                    )

                val result = session.run(inputs)
                try {
                    @Suppress("UNCHECKED_CAST")
                    val output = (result[0].value as Array<Array<FloatArray>>)[0]

                    // Mean pooling over non-masked tokens
                    val dim = output[0].size
                    val pooled = FloatArray(dim)
                    var tokenCount = 0
                    for (i in output.indices) {
                        if (attentionMask[i] == 1L) {
                            tokenCount++
                            for (j in 0 until dim) {
                                pooled[j] += output[i][j]
                            }
                        }
                    }
                    if (tokenCount > 0) {
                        for (j in 0 until dim) {
                            pooled[j] /= tokenCount
                        }
                    }

                    // L2 normalize
                    normalize(pooled)
                    pooled
                } finally {
                    result.close()
                }
            } finally {
                inputIdsTensor?.close()
                attentionMaskTensor?.close()
                tokenTypeIdsTensor?.close()
            }
        }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    private fun normalize(arr: FloatArray) {
        val norm = kotlin.math.sqrt(arr.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in arr.indices) arr[i] /= norm
        }
    }
}
