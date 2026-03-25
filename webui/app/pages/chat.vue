<script setup lang="ts">
const chatStore = useChatStore()
const ws = useWebSocket()
const { sendMessage, sendApproval, handleFrame } = useChat()
const { api } = useApi()
const route = useRoute()

onMounted(async () => {
  ws.onFrame(handleFrame)
  ws.connect()

  try {
    const sessions = await api<Array<{ chatId: string }>>('/sessions')
    chatStore.setAvailableSessions(sessions.map(s => s.chatId))
  }
  catch {
    // sessions endpoint may not exist yet
  }

  const querySession = route.query.session as string | undefined
  if (querySession && chatStore.availableSessions.includes(querySession)) {
    chatStore.setCurrentSession(querySession)
  }
  else if (chatStore.availableSessions.includes('local_ws_default')) {
    chatStore.setCurrentSession('local_ws_default')
  }

  try {
    const response = await api<{ models: string[] }>('/models')
    chatStore.setAvailableModels(response.models)
  }
  catch {
    // models endpoint may not exist yet
  }
})

onUnmounted(() => {
  ws.offFrame(handleFrame)
  ws.disconnect()
})

watch(() => chatStore.currentSessionId, async (sessionId) => {
  if (sessionId) {
    await chatStore.loadSessionMessages(sessionId)
  }
})

function onSend(content: string) {
  sendMessage(content)
}

function onApprove(approvalId: string) {
  sendApproval(approvalId, true)
}

function onDeny(approvalId: string) {
  sendApproval(approvalId, false)
}
</script>

<template>
  <div
    class="flex flex-col h-full"
    data-testid="chat-page"
  >
    <!-- Top bar with session/model switchers -->
    <div class="flex items-center gap-3 px-4 py-2 border-b border-gray-200 dark:border-gray-800 shrink-0">
      <h1 class="text-lg font-semibold">
        Chat
      </h1>
      <div class="flex-1" />
      <USelect
        v-if="chatStore.availableSessions.length > 0"
        :model-value="chatStore.currentSessionId || ''"
        :items="chatStore.availableSessions"
        placeholder="Session"
        size="sm"
        class="w-40"
        data-testid="session-switcher"
        @update:model-value="chatStore.setCurrentSession($event as string)"
      />
      <USelect
        v-if="chatStore.availableModels.length > 0"
        :model-value="chatStore.currentModel || ''"
        :items="chatStore.availableModels"
        placeholder="Model"
        size="sm"
        class="w-48"
        data-testid="model-switcher"
        @update:model-value="chatStore.setCurrentModel($event as string)"
      />
    </div>

    <!-- Messages -->
    <ChatMessageList />

    <!-- Thinking indicator -->
    <ChatThinkingIndicator v-if="chatStore.thinking" />

    <!-- Approval banner -->
    <ChatApprovalBanner
      v-if="chatStore.pendingApproval"
      :approval="chatStore.pendingApproval"
      @approve="onApprove"
      @deny="onDeny"
    />

    <!-- Input -->
    <ChatInput @send="onSend" />
  </div>
</template>
