export interface ChatFrame {
  type: 'user' | 'assistant' | 'status' | 'approval_request' | 'approval_response' | 'error' | 'stream_delta' | 'stream_end'
  content: string
  attachments?: string[]
  approvalId?: string
  approved?: boolean
  riskScore?: number
  timeout?: number
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: Date
  attachments?: string[]
  isStreaming?: boolean
}
