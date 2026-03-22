<script setup lang="ts">
</script>

<template>
  <div
    class="flex gap-3 px-4 py-3 bg-gray-50 dark:bg-gray-900/50"
    data-testid="thinking-indicator"
  >
    <div class="shrink-0 pt-1">
      <div class="size-7 rounded-full flex items-center justify-center text-xs font-medium bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300">
        K
      </div>
    </div>
    <div class="flex items-center gap-1 pt-2">
      <span class="size-2 rounded-full bg-gray-400 animate-bounce [animation-delay:0ms]" />
      <span class="size-2 rounded-full bg-gray-400 animate-bounce [animation-delay:150ms]" />
      <span class="size-2 rounded-full bg-gray-400 animate-bounce [animation-delay:300ms]" />
      <span class="text-sm text-gray-400 ml-2">Thinking...</span>
    </div>
  </div>
</template>
