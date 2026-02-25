package io.github.klaw.common.util

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

private val encoding by lazy {
    Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
}

actual fun approximateTokenCount(text: String): Int = if (text.isEmpty()) 0 else encoding.countTokensOrdinary(text)
