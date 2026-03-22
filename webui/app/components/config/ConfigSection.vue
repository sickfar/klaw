<script setup lang="ts">
import type { ConfigSchemaProperty } from '~/types/config'

const props = defineProps<{
  name: string
  schema: ConfigSchemaProperty
  modelValue: Record<string, unknown>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

const collapsed = ref(false)

function updateField(fieldName: string, value: unknown) {
  emit('update:modelValue', {
    ...props.modelValue,
    [fieldName]: value,
  })
}

const sortedProperties = computed(() => {
  if (!props.schema.properties) return []
  return Object.entries(props.schema.properties).sort(([a], [b]) => a.localeCompare(b))
})
</script>

<template>
  <div
    class="mb-4"
    :data-testid="`config-section-${name}`"
  >
    <button
      class="flex items-center gap-2 w-full text-left py-2 text-sm font-semibold text-gray-800 dark:text-gray-200 hover:text-primary-600 dark:hover:text-primary-400"
      :data-testid="`config-section-toggle-${name}`"
      @click="collapsed = !collapsed"
    >
      <UIcon
        :name="collapsed ? 'i-lucide-chevron-right' : 'i-lucide-chevron-down'"
        class="size-4"
      />
      {{ name }}
    </button>
    <div
      v-show="!collapsed"
      class="pl-4 border-l-2 border-gray-200 dark:border-gray-700"
    >
      <template
        v-for="[fieldName, fieldSchema] in sortedProperties"
        :key="fieldName"
      >
        <!-- Nested object -->
        <ConfigSection
          v-if="fieldSchema.type === 'object' && fieldSchema.properties"
          :name="fieldName"
          :schema="fieldSchema"
          :model-value="(modelValue[fieldName] as Record<string, unknown>) || {}"
          @update:model-value="updateField(fieldName, $event)"
        />
        <!-- Leaf field -->
        <ConfigField
          v-else
          :name="fieldName"
          :schema="fieldSchema"
          :model-value="modelValue[fieldName]"
          @update:model-value="updateField(fieldName, $event)"
        />
      </template>
    </div>
  </div>
</template>
