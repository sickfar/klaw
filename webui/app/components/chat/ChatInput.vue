<script setup lang="ts">
const emit = defineEmits<{
  send: [content: string]
}>()

const input = ref('')
const inputRef = ref<HTMLTextAreaElement>()
const slashCommands = [
  '/help', '/memory', '/schedule', '/status', '/sessions',
  '/skills', '/config', '/clear', '/model', '/session',
]
const showSlashMenu = ref(false)
const filteredCommands = ref<string[]>([])

function handleInput() {
  if (input.value.startsWith('/')) {
    const query = input.value.toLowerCase()
    filteredCommands.value = slashCommands.filter(c => c.startsWith(query))
    showSlashMenu.value = filteredCommands.value.length > 0
  }
  else {
    showSlashMenu.value = false
  }
}

function selectCommand(cmd: string) {
  input.value = cmd + ' '
  showSlashMenu.value = false
  inputRef.value?.focus()
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    send()
  }
  if (event.key === 'Escape') {
    showSlashMenu.value = false
  }
}

function send() {
  const content = input.value.trim()
  if (!content) return
  emit('send', content)
  input.value = ''
  showSlashMenu.value = false
}
</script>

<template>
  <div
    class="relative border-t border-gray-200 dark:border-gray-800 p-4"
    data-testid="chat-input-area"
  >
    <!-- Slash command autocomplete -->
    <div
      v-if="showSlashMenu"
      class="absolute bottom-full left-4 right-4 mb-1 bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-700 rounded-lg shadow-lg overflow-hidden z-10"
      data-testid="slash-command-menu"
    >
      <button
        v-for="cmd in filteredCommands"
        :key="cmd"
        class="w-full text-left px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-800"
        :data-testid="`slash-cmd-${cmd.slice(1)}`"
        @click="selectCommand(cmd)"
      >
        <span class="font-mono text-primary-600 dark:text-primary-400">{{ cmd }}</span>
      </button>
    </div>

    <div class="flex gap-2 items-end">
      <textarea
        ref="inputRef"
        v-model="input"
        placeholder="Type a message... (Ctrl+Enter to send, / for commands)"
        class="flex-1 resize-none rounded-lg border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-900 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 min-h-[40px] max-h-[120px]"
        rows="1"
        data-testid="chat-input"
        @input="handleInput"
        @keydown="handleKeydown"
      />
      <UButton
        icon="i-lucide-send"
        size="md"
        :disabled="!input.trim()"
        data-testid="chat-send-button"
        @click="send"
      />
    </div>
    <p class="text-xs text-gray-400 mt-1">
      Press Ctrl+Enter to send
    </p>
  </div>
</template>
