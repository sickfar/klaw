import type { ChatFrame } from '~/types/chat'

export function useChat() {
  const ws = useWebSocket()
  const chatStore = useChatStore()

  function sendMessage(content: string) {
    const frame: ChatFrame = {
      type: 'user',
      content,
    }
    ws.send(frame)
    chatStore.addMessage({
      id: crypto.randomUUID(),
      role: 'user',
      content,
      timestamp: new Date(),
    })
  }

  function sendApproval(approvalId: string, approved: boolean) {
    const frame: ChatFrame = {
      type: 'approval_response',
      content: '',
      approvalId,
      approved,
    }
    ws.send(frame)
    chatStore.clearPendingApproval()
  }

  function handleFrame(frame: ChatFrame) {
    switch (frame.type) {
      case 'assistant':
        chatStore.addMessage({
          id: crypto.randomUUID(),
          role: 'assistant',
          content: frame.content,
          timestamp: new Date(),
          attachments: frame.attachments,
        })
        chatStore.setThinking(false)
        break
      case 'status':
        if (frame.content === 'thinking') {
          chatStore.setThinking(true)
        }
        else {
          chatStore.setThinking(false)
        }
        break
      case 'approval_request':
        chatStore.setPendingApproval({
          approvalId: frame.approvalId!,
          content: frame.content,
          riskScore: frame.riskScore,
          timeout: frame.timeout,
        })
        break
      case 'error':
        chatStore.addMessage({
          id: crypto.randomUUID(),
          role: 'assistant',
          content: `Error: ${frame.content}`,
          timestamp: new Date(),
        })
        chatStore.setThinking(false)
        break
    }
  }

  return {
    sendMessage,
    sendApproval,
    handleFrame,
  }
}
