<script setup lang="ts">
const auth = useAuth()
const tokenInput = ref('')
const error = ref('')
const loading = ref(false)

onMounted(() => {
  auth.checkAuth()
})

async function handleSubmit() {
  const trimmed = tokenInput.value.trim()
  if (!trimmed) return
  loading.value = true
  error.value = ''
  const ok = await auth.login(trimmed)
  loading.value = false
  if (!ok) {
    error.value = 'Invalid token'
    tokenInput.value = ''
  }
}
</script>

<template>
  <!-- Loading state while checking auth -->
  <div
    v-if="auth.checking.value || auth.authRequired.value === null"
    class="fixed inset-0 z-[9999] flex items-center justify-center bg-gray-50 dark:bg-gray-950"
    data-testid="auth-checking"
  >
    <div class="flex items-center gap-2 text-gray-500">
      <UIcon
        name="i-lucide-loader-2"
        class="size-5 animate-spin"
      />
      <span class="text-sm">Checking authentication...</span>
    </div>
  </div>

  <!-- Blocking auth dialog -->
  <div
    v-else-if="auth.authRequired.value && !auth.authenticated.value"
    class="fixed inset-0 z-[9999] flex items-center justify-center bg-black/60"
    data-testid="auth-gate-overlay"
  >
    <div
      class="bg-white dark:bg-gray-900 rounded-lg shadow-xl p-6 w-full max-w-sm mx-4"
      data-testid="auth-gate-dialog"
    >
      <h2 class="text-lg font-semibold mb-1">
        Authentication Required
      </h2>
      <p class="text-sm text-gray-500 dark:text-gray-400 mb-4">
        Enter your API token to access the dashboard.
      </p>
      <form @submit.prevent="handleSubmit">
        <input
          v-model="tokenInput"
          type="password"
          placeholder="Enter API token"
          class="w-full px-3 py-2 border border-gray-300 dark:border-gray-700 rounded-md bg-white dark:bg-gray-800 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 mb-2"
          data-testid="auth-token-input"
          autofocus
        >
        <p
          v-if="error"
          class="text-red-500 text-sm mb-2"
          data-testid="auth-error"
        >
          {{ error }}
        </p>
        <button
          type="submit"
          :disabled="loading || !tokenInput.trim()"
          class="w-full px-4 py-2 bg-primary-500 hover:bg-primary-600 disabled:opacity-50 text-white text-sm font-medium rounded-md transition-colors"
          data-testid="auth-submit-button"
        >
          {{ loading ? 'Checking...' : 'Login' }}
        </button>
      </form>
    </div>
  </div>

  <!-- Content visible when authenticated or no auth required -->
  <slot v-else />
</template>
