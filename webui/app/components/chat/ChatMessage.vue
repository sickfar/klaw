<script setup lang="ts">
import type { Message } from '~/types/chat'

defineProps<{
  message: Message
}>()
</script>

<template>
  <div
    class="flex gap-3 px-4 py-3"
    :class="message.role === 'user' ? 'bg-transparent' : 'bg-gray-50 dark:bg-gray-900/50'"
    :data-testid="`chat-message-${message.role}`"
  >
    <div class="shrink-0 pt-1">
      <div
        class="size-7 rounded-full flex items-center justify-center text-xs font-medium"
        :class="message.role === 'user'
          ? 'bg-primary-100 dark:bg-primary-900 text-primary-700 dark:text-primary-300'
          : 'bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-300'"
      >
        {{ message.role === 'user' ? 'U' : 'K' }}
      </div>
    </div>
    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2 mb-1">
        <span class="text-sm font-medium">
          {{ message.role === 'user' ? 'You' : 'Klaw' }}
        </span>
        <span class="text-xs text-gray-400">
          {{ message.timestamp?.toLocaleTimeString?.() ?? '' }}
        </span>
      </div>
      <CommonMarkdownContent :content="message.content" />
      <div
        v-if="message.attachments?.length"
        class="mt-2 flex flex-wrap gap-2"
      >
        <UBadge
          v-for="(att, i) in message.attachments"
          :key="i"
          variant="subtle"
          size="sm"
          data-testid="chat-attachment"
        >
          {{ att }}
        </UBadge>
      </div>
    </div>
  </div>
</template>
