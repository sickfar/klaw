import { defineStore } from 'pinia'
import type { StatusResponse } from '~/types/status'

export const useStatusStore = defineStore('status', () => {
  const engineStatus = ref<StatusResponse | null>(null)
  const wsConnected = ref(false)
  const lastUpdated = ref<Date | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchStatus() {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      engineStatus.value = await api<StatusResponse>('/status')
      lastUpdated.value = new Date()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch status'
    }
    finally {
      loading.value = false
    }
  }

  function setWsConnected(connected: boolean) {
    wsConnected.value = connected
  }

  return {
    engineStatus,
    wsConnected,
    lastUpdated,
    loading,
    error,
    fetchStatus,
    setWsConnected,
  }
})
