<script setup lang="ts">
const memoryStore = useMemoryStore()
const newCategory = ref('')
const newContent = ref('')
const searchInput = ref('')

onMounted(() => {
  memoryStore.fetchCategories()
})

function selectCategory(name: string) {
  searchInput.value = ''
  memoryStore.selectCategory(name)
}

function search() {
  const q = searchInput.value.trim()
  if (q) {
    memoryStore.searchFacts(q)
  }
  else if (memoryStore.selectedCategory) {
    memoryStore.fetchFacts(memoryStore.selectedCategory)
  }
}

async function addFact() {
  const category = newCategory.value.trim() || memoryStore.selectedCategory
  const content = newContent.value.trim()
  if (!category || !content) return
  await memoryStore.addFact(category, content)
  newContent.value = ''
  newCategory.value = ''
}

async function deleteFact(factId: string) {
  await memoryStore.deleteFact(factId)
}
</script>

<template>
  <div
    class="flex h-full"
    data-testid="memory-page"
  >
    <!-- Left panel: categories -->
    <aside class="w-64 shrink-0 border-r border-gray-200 dark:border-gray-800 flex flex-col overflow-hidden">
      <div class="px-4 py-3 border-b border-gray-200 dark:border-gray-800">
        <h2 class="text-sm font-semibold">
          Categories
        </h2>
      </div>
      <div class="flex-1 overflow-y-auto py-2">
        <button
          v-for="cat in memoryStore.categories"
          :key="cat.name"
          class="w-full text-left px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-900 flex items-center justify-between"
          :class="memoryStore.selectedCategory === cat.name ? 'bg-primary-50 dark:bg-primary-950 text-primary-600' : ''"
          :data-testid="`memory-category-${cat.name}`"
          @click="selectCategory(cat.name)"
        >
          <span>{{ cat.name }}</span>
          <UBadge
            variant="subtle"
            size="xs"
          >
            {{ cat.factCount }}
          </UBadge>
        </button>
        <p
          v-if="memoryStore.categories.length === 0 && !memoryStore.loading"
          class="px-4 py-2 text-sm text-gray-400"
        >
          No categories yet
        </p>
      </div>
    </aside>

    <!-- Right panel: facts -->
    <div class="flex-1 flex flex-col min-w-0 overflow-hidden">
      <!-- Search bar -->
      <div class="px-4 py-3 border-b border-gray-200 dark:border-gray-800 flex gap-2">
        <UInput
          v-model="searchInput"
          placeholder="Search memory..."
          class="flex-1"
          data-testid="memory-search"
          @keydown.enter="search"
        />
        <UButton
          icon="i-lucide-search"
          size="sm"
          data-testid="memory-search-button"
          @click="search"
        />
      </div>

      <!-- Error -->
      <div
        v-if="memoryStore.error"
        class="mx-4 mt-2 p-3 rounded-lg bg-red-50 dark:bg-red-950 text-red-600 dark:text-red-400 text-sm"
        data-testid="memory-error"
      >
        {{ memoryStore.error }}
      </div>

      <!-- Facts list -->
      <div class="flex-1 overflow-y-auto p-4 space-y-3">
        <div
          v-for="fact in memoryStore.facts"
          :key="fact.id"
          class="p-3 rounded-lg border border-gray-200 dark:border-gray-700"
          :data-testid="`memory-fact-${fact.id}`"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="flex-1 min-w-0">
              <UBadge
                variant="subtle"
                size="xs"
                class="mb-1"
              >
                {{ fact.category }}
              </UBadge>
              <p class="text-sm">
                {{ fact.content }}
              </p>
              <p class="text-xs text-gray-400 mt-1">
                {{ fact.updatedAt }}
              </p>
            </div>
            <UButton
              icon="i-lucide-trash-2"
              variant="ghost"
              color="error"
              size="xs"
              :data-testid="`memory-delete-${fact.id}`"
              @click="deleteFact(fact.id)"
            />
          </div>
        </div>
        <p
          v-if="memoryStore.facts.length === 0 && !memoryStore.loading && memoryStore.selectedCategory"
          class="text-sm text-gray-400 text-center py-8"
        >
          No facts in this category
        </p>
        <p
          v-if="!memoryStore.selectedCategory && !searchInput"
          class="text-sm text-gray-400 text-center py-8"
        >
          Select a category to view facts
        </p>
      </div>

      <!-- Add fact form -->
      <div class="border-t border-gray-200 dark:border-gray-800 p-4">
        <div class="flex gap-2">
          <UInput
            v-model="newCategory"
            :placeholder="memoryStore.selectedCategory || 'Category'"
            size="sm"
            class="w-32"
            data-testid="memory-new-category"
          />
          <UInput
            v-model="newContent"
            placeholder="New fact..."
            size="sm"
            class="flex-1"
            data-testid="memory-new-content"
            @keydown.enter="addFact"
          />
          <UButton
            icon="i-lucide-plus"
            size="sm"
            data-testid="memory-add-button"
            @click="addFact"
          />
        </div>
      </div>
    </div>
  </div>
</template>
