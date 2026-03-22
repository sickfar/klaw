export interface ChatFrame {
  type: 'user' | 'assistant' | 'status' | 'approval_request' | 'approval_response' | 'error'
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
}
