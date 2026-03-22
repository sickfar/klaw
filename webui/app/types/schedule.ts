export interface ScheduleJob {
  name: string
  cron: string
  prompt: string
  enabled: boolean
  nextFireTime?: string
  lastFireTime?: string
}

export interface ScheduleRun {
  jobName: string
  startedAt: string
  finishedAt?: string
  status: 'running' | 'completed' | 'failed'
  output?: string
}
