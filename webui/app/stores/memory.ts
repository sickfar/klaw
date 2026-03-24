import { defineStore } from 'pinia'
import type { MemoryCategory, MemoryFact } from '~/types/memory'

export const useMemoryStore = defineStore('memory', () => {
  const categories = ref<MemoryCategory[]>([])
  const facts = ref<MemoryFact[]>([])
  const selectedCategory = ref<string | null>(null)
  const searchQuery = ref('')
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchCategories() {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      const response = await api<{ categories: MemoryCategory[] }>('/memory/categories')
      categories.value = response.categories
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch categories'
    }
    finally {
      loading.value = false
    }
  }

  async function fetchFacts(category: string) {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      facts.value = await api<MemoryFact[]>(`/memory/facts?category=${encodeURIComponent(category)}`)
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch facts'
    }
    finally {
      loading.value = false
    }
  }

  async function searchFacts(query: string) {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      facts.value = await api<MemoryFact[]>(`/memory/search?query=${encodeURIComponent(query)}`)
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to search facts'
    }
    finally {
      loading.value = false
    }
  }

  async function addFact(category: string, content: string) {
    const { api } = useApi()
    error.value = null
    try {
      await api('/memory/facts', {
        method: 'POST',
        body: { category, content },
      })
      if (selectedCategory.value === category) {
        await fetchFacts(category)
      }
      await fetchCategories()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to add fact'
    }
  }

  async function deleteFact(factId: string) {
    const { api } = useApi()
    error.value = null
    try {
      await api(`/memory/facts/${factId}`, { method: 'DELETE' })
      facts.value = facts.value.filter(f => f.id !== factId)
      await fetchCategories()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete fact'
    }
  }

  function selectCategory(category: string | null) {
    selectedCategory.value = category
    if (category) {
      fetchFacts(category)
    }
    else {
      facts.value = []
    }
  }

  return {
    categories,
    facts,
    selectedCategory,
    searchQuery,
    loading,
    error,
    fetchCategories,
    fetchFacts,
    searchFacts,
    addFact,
    deleteFact,
    selectCategory,
  }
})
