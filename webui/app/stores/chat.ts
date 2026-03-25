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

  function addMessage(message: Message) {
    messages.value.push(message)
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
  }
})
