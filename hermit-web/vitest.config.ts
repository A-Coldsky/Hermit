import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@shared': fileURLToPath(new URL('./shared', import.meta.url)) },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
    css: true,
  },
})
