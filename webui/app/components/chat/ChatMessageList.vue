<script setup lang="ts">
const chatStore = useChatStore()
const listRef = ref<HTMLDivElement>()

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.streamingContent, scrollToBottom)
onMounted(scrollToBottom)
</script>

<template>
  <div
    ref="listRef"
    class="flex-1 overflow-y-auto"
    data-testid="chat-message-list"
  >
    <div
      v-if="chatStore.messages.length === 0"
      class="flex items-center justify-center h-full text-gray-400"
    >
      <div class="text-center">
        <UIcon
          name="i-lucide-message-square"
          class="size-12 mb-3 mx-auto opacity-50"
        />
        <p>No messages yet. Start a conversation!</p>
      </div>
    </div>
    <ChatMessage
      v-for="msg in chatStore.messages"
      :key="msg.id"
      :message="msg"
    />
  </div>
</template>
