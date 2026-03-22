import { defineStore } from 'pinia'
import type { ConfigSchema } from '~/types/config'

export const useConfigStore = defineStore('config', () => {
  const engineSchema = ref<ConfigSchema | null>(null)
  const gatewaySchema = ref<ConfigSchema | null>(null)
  const engineConfig = ref<Record<string, unknown>>({})
  const gatewayConfig = ref<Record<string, unknown>>({})
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchSchema(target: 'engine' | 'gateway') {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      const schema = await api<ConfigSchema>(`/config/schema/${target}`)
      if (target === 'engine') engineSchema.value = schema
      else gatewaySchema.value = schema
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch schema'
    }
    finally {
      loading.value = false
    }
  }

  async function fetchConfig(target: 'engine' | 'gateway') {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      const config = await api<Record<string, unknown>>(`/config/${target}`)
      if (target === 'engine') engineConfig.value = config
      else gatewayConfig.value = config
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch config'
    }
    finally {
      loading.value = false
    }
  }

  async function saveConfig(target: 'engine' | 'gateway', config: Record<string, unknown>) {
    const { api } = useApi()
    error.value = null
    try {
      await api(`/config/${target}`, {
        method: 'PUT',
        body: config,
      })
      if (target === 'engine') engineConfig.value = config
      else gatewayConfig.value = config
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save config'
    }
  }

  return {
    engineSchema,
    gatewaySchema,
    engineConfig,
    gatewayConfig,
    loading,
    error,
    fetchSchema,
    fetchConfig,
    saveConfig,
  }
})
