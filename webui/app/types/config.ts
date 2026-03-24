export interface ConfigSchema {
  type: string
  properties?: Record<string, ConfigSchemaProperty>
  required?: string[]
}

export interface ConfigSchemaProperty {
  type: string
  description?: string
  default?: unknown
  properties?: Record<string, ConfigSchemaProperty>
  items?: ConfigSchemaProperty
  enum?: string[]
  sensitive?: boolean
  required?: string[]
}

export interface SkillInfo {
  name: string
  description: string
  source: string
  tools?: string[]
}

export interface SessionInfo {
  chatId: string
  model: string
  messageCount?: number
  createdAt: string
  updatedAt: string
}
