export interface HealthCheck {
  name: string
  status: 'ok' | 'error'
  details?: string
}

export interface StatusResponse {
  status: string
  sessions?: number
  uptime?: string
  health?: Record<string, HealthCheck>
  usage?: Record<string, ModelUsage>
}

export interface ModelUsage {
  requests: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
}
