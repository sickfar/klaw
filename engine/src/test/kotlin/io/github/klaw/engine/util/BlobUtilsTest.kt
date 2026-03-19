package io.github.klaw.engine.util

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlobUtilsTest {
    @Test
    fun `blobToFloatArray roundtrips with floatArrayToBlob`() {
        val original = floatArrayOf(1.0f, -0.5f, 0.0f, 3.14f, Float.MAX_VALUE, Float.MIN_VALUE)
        val blob = floatArrayToBlob(original)
        val restored = blobToFloatArray(blob)
        assertArrayEquals(original, restored)
    }

    @Test
    fun `blobToFloatArray with empty array returns empty`() {
        val blob = floatArrayToBlob(floatArrayOf())
        val restored = blobToFloatArray(blob)
        assertEquals(0, restored.size)
    }

    @Test
    fun `blobToFloatArray preserves known values`() {
        val values = floatArrayOf(1.0f, -0.5f, 0.0f)
        val blob = floatArrayToBlob(values)
        val restored = blobToFloatArray(blob)
        assertEquals(1.0f, restored[0])
        assertEquals(-0.5f, restored[1])
        assertEquals(0.0f, restored[2])
    }
}
