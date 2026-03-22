export default defineNuxtConfig({
  compatibilityDate: '2025-01-01',
  future: { compatibilityVersion: 4 },
  ssr: false,
  modules: ['@nuxt/ui', '@pinia/nuxt'],
  devtools: { enabled: true },

  app: {
    head: {
      title: 'Klaw',
    },
  },

  nitro: {
    output: {
      publicDir: '.output/public',
    },
  },

  vite: {
    server: {
      proxy: {
        '/chat': {
          target: 'ws://localhost:37474',
          ws: true,
        },
        '/upload': {
          target: 'http://localhost:37474',
        },
        '/api': {
          target: 'http://localhost:37474',
        },
      },
    },
  },
})
