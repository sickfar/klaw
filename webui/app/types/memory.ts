export interface MemoryCategory {
  name: string
  factCount: number
}

export interface MemoryFact {
  id: string
  category: string
  content: string
  createdAt: string
  updatedAt: string
}

export interface MemorySearchResult {
  facts: MemoryFact[]
  total: number
}
