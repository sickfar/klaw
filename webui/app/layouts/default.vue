<script setup lang="ts">
const sidebarOpen = ref(true)
const isMobile = ref(false)

const navItems = [
  { label: 'Chat', icon: 'i-lucide-message-square', to: '/chat' },
  { label: 'Dashboard', icon: 'i-lucide-layout-dashboard', to: '/dashboard' },
  { label: 'Memory', icon: 'i-lucide-brain', to: '/memory' },
  { label: 'Schedule', icon: 'i-lucide-calendar', to: '/schedule' },
  { label: 'Sessions', icon: 'i-lucide-users', to: '/sessions' },
  { label: 'Skills', icon: 'i-lucide-zap', to: '/skills' },
  { label: 'Config', icon: 'i-lucide-settings', to: '/config' },
]

function onResize() {
  isMobile.value = window.innerWidth < 768
  if (isMobile.value) sidebarOpen.value = false
}

onMounted(() => {
  onResize()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <div class="flex h-screen overflow-hidden" data-testid="app-layout">
    <!-- Sidebar -->
    <aside
      v-show="sidebarOpen"
      class="flex flex-col border-r border-gray-200 dark:border-gray-800 bg-gray-50 dark:bg-gray-950 transition-all"
      :class="isMobile ? 'fixed inset-y-0 left-0 z-50 w-64' : 'w-64 shrink-0'"
      data-testid="app-sidebar"
    >
      <div class="flex items-center gap-2 px-4 py-3 border-b border-gray-200 dark:border-gray-800">
        <span class="text-lg font-bold" data-testid="app-logo">Klaw</span>
      </div>
      <nav class="flex-1 overflow-y-auto py-2">
        <NuxtLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="flex items-center gap-3 px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-900 rounded-md mx-2"
          active-class="bg-primary-50 dark:bg-primary-950 text-primary-600 dark:text-primary-400"
          :data-testid="`nav-${item.label.toLowerCase()}`"
          @click="isMobile && (sidebarOpen = false)"
        >
          <UIcon :name="item.icon" class="size-5" />
          <span>{{ item.label }}</span>
        </NuxtLink>
      </nav>
    </aside>

    <!-- Overlay for mobile -->
    <div
      v-if="isMobile && sidebarOpen"
      class="fixed inset-0 bg-black/50 z-40"
      @click="sidebarOpen = false"
    />

    <!-- Main content -->
    <div class="flex flex-1 flex-col min-w-0">
      <!-- Top bar -->
      <header
        class="flex items-center gap-3 px-4 py-2 border-b border-gray-200 dark:border-gray-800 shrink-0"
        data-testid="app-topbar"
      >
        <UButton
          :icon="sidebarOpen ? 'i-lucide-panel-left-close' : 'i-lucide-panel-left-open'"
          variant="ghost"
          size="sm"
          data-testid="sidebar-toggle"
          @click="sidebarOpen = !sidebarOpen"
        />
        <div class="flex-1" />
        <CommonConnectionStatus />
        <UColorModeButton data-testid="theme-toggle" />
      </header>

      <!-- Page content -->
      <main class="flex-1 overflow-y-auto">
        <slot />
      </main>
    </div>
  </div>
</template>
