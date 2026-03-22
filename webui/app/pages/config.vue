<script setup lang="ts">
const configStore = useConfigStore()
const activeTab = ref<'engine' | 'gateway'>('engine')
const saving = ref(false)
const saveSuccess = ref(false)
const toast = useToast()

onMounted(async () => {
  await Promise.all([
    configStore.fetchSchema('engine'),
    configStore.fetchSchema('gateway'),
    configStore.fetchConfig('engine'),
    configStore.fetchConfig('gateway'),
  ])
})

function switchTab(tab: 'engine' | 'gateway') {
  activeTab.value = tab
  saveSuccess.value = false
}

async function save() {
  saving.value = true
  saveSuccess.value = false
  try {
    const config = activeTab.value === 'engine' ? configStore.engineConfig : configStore.gatewayConfig
    await configStore.saveConfig(activeTab.value, config)
    saveSuccess.value = true
    toast.add({
      title: 'Configuration saved',
      description: `${activeTab.value} configuration has been saved successfully.`,
      color: 'success',
    })
  }
  catch {
    toast.add({
      title: 'Save failed',
      description: configStore.error || 'Failed to save configuration.',
      color: 'error',
    })
  }
  finally {
    saving.value = false
  }
}

const currentSchema = computed(() =>
  activeTab.value === 'engine' ? configStore.engineSchema : configStore.gatewaySchema,
)

const currentConfig = computed({
  get() {
    return activeTab.value === 'engine' ? configStore.engineConfig : configStore.gatewayConfig
  },
  set(value: Record<string, unknown>) {
    if (activeTab.value === 'engine') configStore.engineConfig = value
    else configStore.gatewayConfig = value
  },
})
</script>

<template>
  <div
    class="flex flex-col h-full p-6 overflow-y-auto"
    data-testid="config-page"
  >
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-bold">
        Configuration
      </h1>
      <UButton
        icon="i-lucide-save"
        :loading="saving"
        data-testid="config-save-button"
        @click="save"
      >
        Save
      </UButton>
    </div>

    <!-- Error -->
    <div
      v-if="configStore.error"
      class="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-red-600 dark:text-red-400 text-sm"
      data-testid="config-error"
    >
      {{ configStore.error }}
    </div>

    <!-- Tabs -->
    <div class="flex gap-1 mb-6 border-b border-gray-200 dark:border-gray-800">
      <button
        class="px-4 py-2 text-sm font-medium border-b-2 transition-colors"
        :class="activeTab === 'engine'
          ? 'border-primary-500 text-primary-600 dark:text-primary-400'
          : 'border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'"
        data-testid="config-tab-engine"
        @click="switchTab('engine')"
      >
        Engine
      </button>
      <button
        class="px-4 py-2 text-sm font-medium border-b-2 transition-colors"
        :class="activeTab === 'gateway'
          ? 'border-primary-500 text-primary-600 dark:text-primary-400'
          : 'border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'"
        data-testid="config-tab-gateway"
        @click="switchTab('gateway')"
      >
        Gateway
      </button>
    </div>

    <!-- Loading -->
    <div
      v-if="configStore.loading"
      class="flex items-center justify-center py-8"
    >
      <UIcon
        name="i-lucide-loader-2"
        class="size-6 animate-spin text-gray-400"
      />
    </div>

    <!-- Schema form -->
    <ConfigSchemaForm
      v-else-if="currentSchema"
      :schema="currentSchema"
      :model-value="currentConfig"
      @update:model-value="currentConfig = $event"
    />

    <!-- No schema -->
    <p
      v-else
      class="text-sm text-gray-400 text-center py-8"
    >
      No configuration schema available for {{ activeTab }}
    </p>
  </div>
</template>
