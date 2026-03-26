import { defineStore } from 'pinia'
import type { Message } from '~/types/chat'

export interface PendingApproval {
  approvalId: string
  content: string
  riskScore?: number
  timeout?: number
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Message[]>([])
  const currentSessionId = ref<string | null>(null)
  const currentModel = ref<string>('')
  const thinking = ref(false)
  const pendingApproval = ref<PendingApproval | null>(null)
  const availableSessions = ref<string[]>([])
  const availableModels = ref<string[]>([])
  const streamingContent = ref<string | null>(null)
  const isStreaming = ref(false)

  function addMessage(message: Message) {
    messages.value.push(message)
  }

  function appendToStreamingMessage(delta: string) {
    if (!isStreaming.value) {
      isStreaming.value = true
      streamingContent.value = delta
      messages.value.push({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: delta,
        timestamp: new Date(),
        isStreaming: true,
      })
    }
    else {
      streamingContent.value += delta
      const lastMsg = messages.value[messages.value.length - 1]
      if (lastMsg && lastMsg.isStreaming) {
        lastMsg.content = streamingContent.value!
      }
    }
  }

  function finalizeStreamingMessage(fullContent: string) {
    if (isStreaming.value) {
      const lastMsg = messages.value[messages.value.length - 1]
      if (lastMsg && lastMsg.isStreaming) {
        lastMsg.content = fullContent
        lastMsg.isStreaming = false
      }
      streamingContent.value = null
      isStreaming.value = false
    }
    else {
      messages.value.push({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date(),
      })
    }
    thinking.value = false
  }

  function clearMessages() {
    messages.value = []
  }

  function setThinking(value: boolean) {
    thinking.value = value
  }

  function setPendingApproval(approval: PendingApproval | null) {
    pendingApproval.value = approval
  }

  function clearPendingApproval() {
    pendingApproval.value = null
  }

  function setCurrentSession(sessionId: string | null) {
    currentSessionId.value = sessionId
  }

  function setCurrentModel(model: string) {
    currentModel.value = model
  }

  function setAvailableSessions(sessions: string[]) {
    availableSessions.value = sessions
  }

  function setAvailableModels(models: string[]) {
    availableModels.value = models
  }

  async function loadSessionMessages(sessionId: string) {
    const { api } = useApi()
    const previous = [...messages.value]
    clearMessages()
    try {
      const response = await api<Array<{ role: string, content: string, timestamp: string }>>(`/sessions/${encodeURIComponent(sessionId)}/messages`)
      messages.value = response.map(m => ({
        id: crypto.randomUUID(),
        role: m.role as 'user' | 'assistant',
        content: m.content,
        timestamp: new Date(m.timestamp),
      }))
    }
    catch {
      messages.value = previous
    }
  }

  return {
    messages,
    currentSessionId,
    currentModel,
    thinking,
    pendingApproval,
    availableSessions,
    availableModels,
    streamingContent,
    isStreaming,
    addMessage,
    clearMessages,
    setThinking,
    setPendingApproval,
    clearPendingApproval,
    setCurrentSession,
    setCurrentModel,
    setAvailableSessions,
    setAvailableModels,
    loadSessionMessages,
    appendToStreamingMessage,
    finalizeStreamingMessage,
  }
})
