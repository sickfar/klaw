package io.github.klaw.engine.workspace

import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.engine.session.Session

data class HeartbeatCallbacks(
    val chat: suspend (LlmRequest, String) -> LlmResponse,
    val getOrCreateSession: suspend (chatId: String, defaultModel: String) -> Session,
    val denyPendingApprovals: () -> List<String>,
    val sendDismiss: suspend (String) -> Unit,
)
