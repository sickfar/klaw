<script setup lang="ts">
const ws = useWebSocket()

const colorClass = computed(() => {
  switch (ws.status.value) {
    case 'connected':
      return 'bg-green-500'
    case 'connecting':
      return 'bg-yellow-500 animate-pulse'
    default:
      return 'bg-red-500'
  }
})

const label = computed(() => {
  switch (ws.status.value) {
    case 'connected':
      return 'Connected'
    case 'connecting':
      return 'Connecting...'
    default:
      return 'Disconnected'
  }
})
</script>

<template>
  <div
    class="flex items-center gap-2"
    data-testid="connection-status"
    :title="label"
  >
    <div
      class="size-2 rounded-full"
      :class="colorClass"
      data-testid="connection-dot"
    />
    <span class="text-xs text-gray-500 hidden sm:inline">{{ label }}</span>
  </div>
</template>
