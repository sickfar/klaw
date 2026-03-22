<script setup lang="ts">
import type { ConfigSchema } from '~/types/config'

const props = defineProps<{
  schema: ConfigSchema
  modelValue: Record<string, unknown>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

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
  <div data-testid="config-schema-form">
    <template
      v-for="[fieldName, fieldSchema] in sortedProperties"
      :key="fieldName"
    >
      <ConfigSection
        v-if="fieldSchema.type === 'object' && fieldSchema.properties"
        :name="fieldName"
        :schema="fieldSchema"
        :model-value="(modelValue[fieldName] as Record<string, unknown>) || {}"
        @update:model-value="updateField(fieldName, $event)"
      />
      <ConfigField
        v-else
        :name="fieldName"
        :schema="fieldSchema"
        :model-value="modelValue[fieldName]"
        @update:model-value="updateField(fieldName, $event)"
      />
    </template>
  </div>
</template>
