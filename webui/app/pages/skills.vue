<script setup lang="ts">
const skillsStore = useSkillsStore()
const validationResults = ref<Record<string, { valid: boolean, errors?: string[] }>>({})

onMounted(() => {
  skillsStore.fetchSkills()
})

async function validateSkill(name: string) {
  validationResults.value[name] = await skillsStore.validateSkill(name)
}

const columns = [
  { key: 'name', label: 'Name' },
  { key: 'description', label: 'Description' },
  { key: 'source', label: 'Source' },
  { key: 'actions', label: '' },
]
</script>

<template>
  <div
    class="flex flex-col h-full p-6 overflow-y-auto"
    data-testid="skills-page"
  >
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-bold">
        Skills
      </h1>
      <UButton
        icon="i-lucide-refresh-cw"
        variant="ghost"
        size="sm"
        :loading="skillsStore.loading"
        data-testid="skills-refresh"
        @click="skillsStore.fetchSkills()"
      />
    </div>

    <!-- Error -->
    <div
      v-if="skillsStore.error"
      class="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-red-600 dark:text-red-400 text-sm"
      data-testid="skills-error"
    >
      {{ skillsStore.error }}
    </div>

    <UTable
      :data="skillsStore.skills"
      :columns="columns"
      data-testid="skills-table"
    >
      <template #name-cell="{ row }">
        <span
          class="font-medium"
          :data-testid="`skill-name-${row.original.name}`"
        >
          {{ row.original.name }}
        </span>
      </template>
      <template #source-cell="{ row }">
        <UBadge
          variant="subtle"
          size="xs"
        >
          {{ row.original.source }}
        </UBadge>
      </template>
      <template #actions-cell="{ row }">
        <div class="flex items-center gap-2">
          <UButton
            size="xs"
            variant="outline"
            :data-testid="`skill-validate-${row.original.name}`"
            @click="validateSkill(row.original.name)"
          >
            Validate
          </UButton>
          <UBadge
            v-if="validationResults[row.original.name]"
            :color="validationResults[row.original.name].valid ? 'success' : 'error'"
            variant="subtle"
            size="xs"
            :data-testid="`skill-validation-result-${row.original.name}`"
          >
            {{ validationResults[row.original.name].valid ? 'Valid' : 'Invalid' }}
          </UBadge>
        </div>
      </template>
    </UTable>

    <!-- Validation errors detail -->
    <div
      v-for="[name, result] in Object.entries(validationResults).filter(([, r]) => !r.valid && r.errors)"
      :key="name"
      class="mt-3 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-sm"
      :data-testid="`skill-errors-${name}`"
    >
      <p class="font-medium text-red-600 dark:text-red-400 mb-1">
        {{ name }} validation errors:
      </p>
      <ul class="list-disc list-inside text-red-500">
        <li
          v-for="(err, i) in result.errors"
          :key="i"
        >
          {{ err }}
        </li>
      </ul>
    </div>

    <p
      v-if="skillsStore.skills.length === 0 && !skillsStore.loading"
      class="text-sm text-gray-400 text-center py-8"
    >
      No skills available
    </p>
  </div>
</template>
