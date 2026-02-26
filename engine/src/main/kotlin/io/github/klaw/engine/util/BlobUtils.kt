package io.github.klaw.engine.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Serializes a float array to a little-endian byte blob for sqlite-vec storage. */
@Suppress("MagicNumber")
fun floatArrayToBlob(arr: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in arr) buf.putFloat(f)
    return buf.array()
}
