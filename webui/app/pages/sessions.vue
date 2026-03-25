<script setup lang="ts">
import type { SessionInfo } from '~/types/config'

const { api } = useApi()
const sessions = ref<SessionInfo[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function fetchSessions() {
  loading.value = true
  error.value = null
  try {
    sessions.value = await api<SessionInfo[]>('/sessions')
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to fetch sessions'
  }
  finally {
    loading.value = false
  }
}

async function cleanupSessions() {
  error.value = null
  try {
    await api('/sessions/cleanup', { method: 'DELETE' })
    await fetchSessions()
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : 'Failed to cleanup sessions'
  }
}

onMounted(fetchSessions)

const columns = [
  { accessorKey: 'chatId', header: 'Chat ID' },
  { accessorKey: 'model', header: 'Model' },
  { accessorKey: 'messageCount', header: 'Messages' },
  { accessorKey: 'updatedAt', header: 'Last Updated' },
  { id: 'actions', header: '' },
]
</script>

<template>
  <div
    class="flex flex-col h-full p-6 overflow-y-auto"
    data-testid="sessions-page"
  >
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-bold">
        Sessions
      </h1>
      <div class="flex gap-2">
        <UButton
          icon="i-lucide-refresh-cw"
          variant="ghost"
          size="sm"
          :loading="loading"
          data-testid="sessions-refresh"
          @click="fetchSessions"
        />
        <UButton
          icon="i-lucide-trash-2"
          color="error"
          variant="outline"
          size="sm"
          data-testid="sessions-cleanup"
          @click="cleanupSessions"
        >
          Cleanup
        </UButton>
      </div>
    </div>

    <!-- Error -->
    <div
      v-if="error"
      class="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-red-600 dark:text-red-400 text-sm"
      data-testid="sessions-error"
    >
      {{ error }}
    </div>

    <UTable
      :data="sessions"
      :columns="columns"
      data-testid="sessions-table"
    >
      <template #chatId-cell="{ row }">
        <NuxtLink
          :to="`/chat?session=${row.original.chatId}`"
          class="text-primary-600 dark:text-primary-400 hover:underline"
          :data-testid="`session-link-${row.original.chatId}`"
        >
          {{ row.original.chatId }}
        </NuxtLink>
      </template>
      <template #actions-cell="{ row }">
        <NuxtLink :to="`/chat?session=${row.original.chatId}`">
          <UButton
            icon="i-lucide-message-square"
            variant="ghost"
            size="xs"
            :data-testid="`session-open-${row.original.chatId}`"
          />
        </NuxtLink>
      </template>
    </UTable>

    <p
      v-if="sessions.length === 0 && !loading"
      class="text-sm text-gray-400 text-center py-8"
    >
      No active sessions
    </p>
  </div>
</template>
