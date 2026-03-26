import type { ChatFrame } from '~/types/chat'

export function useChat() {
  const ws = useWebSocket()
  const chatStore = useChatStore()

  function sendMessage(content: string) {
    const frame: ChatFrame = {
      type: 'user',
      content,
    }
    const sent = ws.send(frame)
    if (sent) {
      chatStore.addMessage({
        id: crypto.randomUUID(),
        role: 'user',
        content,
        timestamp: new Date(),
      })
    }
    else {
      chatStore.addMessage({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: 'Error: Not connected to server. Please wait for reconnection.',
        timestamp: new Date(),
      })
    }
  }

  function sendApproval(approvalId: string, approved: boolean) {
    const frame: ChatFrame = {
      type: 'approval_response',
      content: '',
      approvalId,
      approved,
    }
    const sent = ws.send(frame)
    if (sent) {
      chatStore.clearPendingApproval()
    }
    else {
      chatStore.addMessage({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: 'Error: Could not send approval response. Please wait for reconnection and try again.',
        timestamp: new Date(),
      })
    }
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
      case 'stream_delta':
        chatStore.appendToStreamingMessage(frame.content)
        break
      case 'stream_end':
        chatStore.finalizeStreamingMessage(frame.content)
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
