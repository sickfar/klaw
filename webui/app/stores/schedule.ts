import { defineStore } from 'pinia'
import type { ScheduleJob, ScheduleRun } from '~/types/schedule'

export const useScheduleStore = defineStore('schedule', () => {
  const jobs = ref<ScheduleJob[]>([])
  const runs = ref<ScheduleRun[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchJobs() {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      jobs.value = await api<ScheduleJob[]>('/schedule/jobs')
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch jobs'
    }
    finally {
      loading.value = false
    }
  }

  async function toggleJob(name: string, enabled: boolean) {
    const { api } = useApi()
    error.value = null
    try {
      await api(`/schedule/jobs/${encodeURIComponent(name)}/${enabled ? 'enable' : 'disable'}`, {
        method: 'POST',
      })
      await fetchJobs()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to toggle job'
    }
  }

  async function runJob(name: string) {
    const { api } = useApi()
    error.value = null
    try {
      await api(`/schedule/jobs/${encodeURIComponent(name)}/run`, {
        method: 'POST',
      })
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to run job'
    }
  }

  async function createJob(job: { name: string, cron: string, prompt: string }) {
    const { api } = useApi()
    error.value = null
    try {
      await api('/schedule/jobs', {
        method: 'POST',
        body: { name: job.name, cron: job.cron, message: job.prompt },
      })
      await fetchJobs()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to create job'
    }
  }

  async function deleteJob(name: string) {
    const { api } = useApi()
    error.value = null
    try {
      await api(`/schedule/jobs/${encodeURIComponent(name)}`, {
        method: 'DELETE',
      })
      await fetchJobs()
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete job'
    }
  }

  async function fetchRuns(jobName: string) {
    const { api } = useApi()
    error.value = null
    try {
      runs.value = await api<ScheduleRun[]>(`/schedule/jobs/${encodeURIComponent(jobName)}/runs`)
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch runs'
    }
  }

  return {
    jobs,
    runs,
    loading,
    error,
    fetchJobs,
    toggleJob,
    runJob,
    createJob,
    deleteJob,
    fetchRuns,
  }
})
