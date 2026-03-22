<script setup lang="ts">
const scheduleStore = useScheduleStore()
const showCreate = ref(false)
const expandedJob = ref<string | null>(null)

const newJob = ref({ name: '', cron: '', prompt: '' })

onMounted(() => {
  scheduleStore.fetchJobs()
})

async function createJob() {
  if (!newJob.value.name || !newJob.value.cron || !newJob.value.prompt) return
  await scheduleStore.createJob(newJob.value)
  newJob.value = { name: '', cron: '', prompt: '' }
  showCreate.value = false
}

async function toggleJob(name: string, enabled: boolean) {
  await scheduleStore.toggleJob(name, enabled)
}

async function runJob(name: string) {
  await scheduleStore.runJob(name)
}

async function deleteJob(name: string) {
  await scheduleStore.deleteJob(name)
}

async function toggleExpand(name: string) {
  if (expandedJob.value === name) {
    expandedJob.value = null
  }
  else {
    expandedJob.value = name
    await scheduleStore.fetchRuns(name)
  }
}
</script>

<template>
  <div
    class="flex flex-col h-full p-6 overflow-y-auto"
    data-testid="schedule-page"
  >
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-bold">
        Scheduled Jobs
      </h1>
      <div class="flex gap-2">
        <UButton
          icon="i-lucide-refresh-cw"
          variant="ghost"
          size="sm"
          :loading="scheduleStore.loading"
          data-testid="schedule-refresh"
          @click="scheduleStore.fetchJobs()"
        />
        <UButton
          icon="i-lucide-plus"
          size="sm"
          data-testid="schedule-create-button"
          @click="showCreate = true"
        >
          New Job
        </UButton>
      </div>
    </div>

    <!-- Error -->
    <div
      v-if="scheduleStore.error"
      class="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-red-600 dark:text-red-400 text-sm"
      data-testid="schedule-error"
    >
      {{ scheduleStore.error }}
    </div>

    <!-- Jobs table -->
    <div class="space-y-3">
      <UCard
        v-for="job in scheduleStore.jobs"
        :key="job.name"
        :data-testid="`schedule-job-${job.name}`"
      >
        <div class="flex items-center gap-4">
          <USwitch
            :model-value="job.enabled"
            :data-testid="`schedule-toggle-${job.name}`"
            @update:model-value="toggleJob(job.name, $event)"
          />
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-medium text-sm">{{ job.name }}</span>
              <UBadge
                variant="subtle"
                size="xs"
              >
                {{ job.cron }}
              </UBadge>
            </div>
            <p class="text-xs text-gray-500 truncate mt-0.5">
              {{ job.prompt }}
            </p>
            <div class="flex gap-4 text-xs text-gray-400 mt-1">
              <span v-if="job.nextFireTime">Next: {{ job.nextFireTime }}</span>
              <span v-if="job.lastFireTime">Last: {{ job.lastFireTime }}</span>
            </div>
          </div>
          <div class="flex gap-1">
            <UButton
              icon="i-lucide-play"
              variant="ghost"
              size="xs"
              :data-testid="`schedule-run-${job.name}`"
              @click="runJob(job.name)"
            />
            <UButton
              icon="i-lucide-history"
              variant="ghost"
              size="xs"
              :data-testid="`schedule-history-${job.name}`"
              @click="toggleExpand(job.name)"
            />
            <UButton
              icon="i-lucide-trash-2"
              variant="ghost"
              color="error"
              size="xs"
              :data-testid="`schedule-delete-${job.name}`"
              @click="deleteJob(job.name)"
            />
          </div>
        </div>

        <!-- Execution history (expandable) -->
        <div
          v-if="expandedJob === job.name && scheduleStore.runs.length > 0"
          class="mt-4 border-t border-gray-200 dark:border-gray-700 pt-3"
        >
          <p class="text-xs font-medium text-gray-500 mb-2">
            Recent Runs
          </p>
          <div class="space-y-1">
            <div
              v-for="(run, i) in scheduleStore.runs"
              :key="i"
              class="flex items-center gap-2 text-xs"
            >
              <UBadge
                :color="run.status === 'completed' ? 'success' : run.status === 'running' ? 'warning' : 'error'"
                variant="subtle"
                size="xs"
              >
                {{ run.status }}
              </UBadge>
              <span class="text-gray-500">{{ run.startedAt }}</span>
              <span
                v-if="run.finishedAt"
                class="text-gray-400"
              >- {{ run.finishedAt }}</span>
            </div>
          </div>
        </div>
      </UCard>

      <p
        v-if="scheduleStore.jobs.length === 0 && !scheduleStore.loading"
        class="text-sm text-gray-400 text-center py-8"
      >
        No scheduled jobs
      </p>
    </div>

    <!-- Create job slideover -->
    <USlideover
      v-model:open="showCreate"
      data-testid="schedule-create-slideover"
    >
      <template #header>
        <h3 class="text-lg font-semibold">
          New Scheduled Job
        </h3>
      </template>
      <template #body>
        <div class="space-y-4 p-4">
          <div>
            <label class="block text-sm font-medium mb-1">Name</label>
            <UInput
              v-model="newJob.name"
              placeholder="my-job"
              data-testid="schedule-new-name"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Cron Expression</label>
            <UInput
              v-model="newJob.cron"
              placeholder="0 0 * * *"
              data-testid="schedule-new-cron"
            />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1">Prompt</label>
            <UTextarea
              v-model="newJob.prompt"
              placeholder="What should the agent do?"
              rows="4"
              data-testid="schedule-new-prompt"
            />
          </div>
          <UButton
            block
            data-testid="schedule-new-save"
            @click="createJob"
          >
            Create Job
          </UButton>
        </div>
      </template>
    </USlideover>
  </div>
</template>
