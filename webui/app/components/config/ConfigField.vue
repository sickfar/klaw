<script setup lang="ts">
import type { ConfigSchemaProperty } from '~/types/config'

const props = defineProps<{
  name: string
  schema: ConfigSchemaProperty
  modelValue: unknown
}>()

const emit = defineEmits<{
  'update:modelValue': [value: unknown]
}>()

const isSensitive = computed(() => props.schema.sensitive || props.name.toLowerCase().includes('key') || props.name.toLowerCase().includes('token') || props.name.toLowerCase().includes('secret'))
const showPassword = ref(false)

function updateValue(value: unknown) {
  emit('update:modelValue', value)
}

function handleNumberInput(event: Event) {
  const target = event.target as HTMLInputElement
  const num = Number(target.value)
  emit('update:modelValue', isNaN(num) ? 0 : num)
}
</script>

<template>
  <div
    class="mb-3"
    :data-testid="`config-field-${name}`"
  >
    <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
      {{ name }}
      <span
        v-if="schema.description"
        class="text-xs text-gray-400 font-normal ml-1"
      >
        {{ schema.description }}
      </span>
    </label>

    <!-- Boolean -->
    <USwitch
      v-if="schema.type === 'boolean'"
      :model-value="!!modelValue"
      :data-testid="`config-toggle-${name}`"
      @update:model-value="updateValue"
    />

    <!-- Enum / Select -->
    <USelect
      v-else-if="schema.enum"
      :model-value="(modelValue as string) || ''"
      :items="schema.enum"
      :data-testid="`config-select-${name}`"
      @update:model-value="updateValue"
    />

    <!-- Number -->
    <UInput
      v-else-if="schema.type === 'number' || schema.type === 'integer'"
      type="number"
      :model-value="(modelValue as number) ?? schema.default ?? 0"
      :data-testid="`config-number-${name}`"
      @input="handleNumberInput"
    />

    <!-- Sensitive string -->
    <div
      v-else-if="schema.type === 'string' && isSensitive"
      class="flex gap-1"
    >
      <UInput
        :type="showPassword ? 'text' : 'password'"
        :model-value="(modelValue as string) || ''"
        class="flex-1"
        :data-testid="`config-password-${name}`"
        @update:model-value="updateValue"
      />
      <UButton
        :icon="showPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'"
        variant="ghost"
        size="sm"
        @click="showPassword = !showPassword"
      />
    </div>

    <!-- String -->
    <UInput
      v-else-if="schema.type === 'string'"
      :model-value="(modelValue as string) || ''"
      :placeholder="schema.default !== undefined ? String(schema.default) : ''"
      :data-testid="`config-input-${name}`"
      @update:model-value="updateValue"
    />

    <!-- Fallback: raw JSON -->
    <UTextarea
      v-else
      :model-value="typeof modelValue === 'object' ? JSON.stringify(modelValue, null, 2) : String(modelValue || '')"
      rows="3"
      :data-testid="`config-json-${name}`"
      @update:model-value="(v: string) => { try { updateValue(JSON.parse(v)) } catch { updateValue(v) } }"
    />
  </div>
</template>
