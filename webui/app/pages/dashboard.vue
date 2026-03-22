<script setup lang="ts">
const statusStore = useStatusStore()

onMounted(() => {
  statusStore.fetchStatus()
})

const healthEntries = computed(() => {
  if (!statusStore.engineStatus?.health) return []
  return Object.entries(statusStore.engineStatus.health)
})

const usageEntries = computed(() => {
  if (!statusStore.engineStatus?.usage) return []
  return Object.entries(statusStore.engineStatus.usage)
})

function healthColor(status: string): string {
  return status === 'ok' ? 'success' : 'error'
}

function formatNumber(n: number): string {
  return n.toLocaleString()
}
</script>

<template>
  <div
    class="flex flex-col h-full p-6 overflow-y-auto"
    data-testid="dashboard-page"
  >
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-bold">
        Dashboard
      </h1>
      <UButton
        icon="i-lucide-refresh-cw"
        variant="ghost"
        size="sm"
        :loading="statusStore.loading"
        data-testid="dashboard-refresh"
        @click="statusStore.fetchStatus()"
      />
    </div>

    <!-- Error -->
    <div
      v-if="statusStore.error"
      class="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-red-600 dark:text-red-400 text-sm"
      data-testid="dashboard-error"
    >
      {{ statusStore.error }}
    </div>

    <!-- Top stats cards -->
    <div class="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
      <UCard data-testid="stat-status">
        <div class="text-sm text-gray-500">
          Status
        </div>
        <div class="text-2xl font-bold mt-1">
          <UBadge
            :color="statusStore.engineStatus?.status === 'ok' ? 'success' : 'error'"
            size="lg"
          >
            {{ statusStore.engineStatus?.status || 'Unknown' }}
          </UBadge>
        </div>
      </UCard>
      <UCard data-testid="stat-uptime">
        <div class="text-sm text-gray-500">
          Uptime
        </div>
        <div class="text-2xl font-bold mt-1">
          {{ statusStore.engineStatus?.uptime || '-' }}
        </div>
      </UCard>
      <UCard data-testid="stat-sessions">
        <div class="text-sm text-gray-500">
          Active Sessions
        </div>
        <div class="text-2xl font-bold mt-1">
          {{ statusStore.engineStatus?.sessions ?? '-' }}
        </div>
      </UCard>
    </div>

    <!-- Health checks -->
    <div
      v-if="healthEntries.length > 0"
      class="mb-6"
    >
      <h2 class="text-lg font-semibold mb-3">
        Health Checks
      </h2>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        <UCard
          v-for="[name, check] in healthEntries"
          :key="name"
          :data-testid="`health-${name}`"
        >
          <div class="flex items-center gap-2">
            <UBadge
              :color="healthColor(check.status)"
              variant="subtle"
              size="sm"
            >
              {{ check.status }}
            </UBadge>
            <span class="text-sm font-medium">{{ name }}</span>
          </div>
          <p
            v-if="check.details"
            class="text-xs text-gray-500 mt-1"
          >
            {{ check.details }}
          </p>
        </UCard>
      </div>
    </div>

    <!-- Usage table -->
    <div v-if="usageEntries.length > 0">
      <h2 class="text-lg font-semibold mb-3">
        LLM Usage
      </h2>
      <UTable
        :data="usageEntries.map(([model, usage]) => ({
          model,
          requests: formatNumber(usage.requests),
          promptTokens: formatNumber(usage.promptTokens),
          completionTokens: formatNumber(usage.completionTokens),
          totalTokens: formatNumber(usage.totalTokens),
        }))"
        :columns="[
          { key: 'model', label: 'Model' },
          { key: 'requests', label: 'Requests' },
          { key: 'promptTokens', label: 'Prompt Tokens' },
          { key: 'completionTokens', label: 'Completion Tokens' },
          { key: 'totalTokens', label: 'Total Tokens' },
        ]"
        data-testid="usage-table"
      />
    </div>

    <!-- Last updated -->
    <p
      v-if="statusStore.lastUpdated"
      class="text-xs text-gray-400 mt-4"
      data-testid="last-updated"
    >
      Last updated: {{ statusStore.lastUpdated.toLocaleTimeString() }}
    </p>
  </div>
</template>
