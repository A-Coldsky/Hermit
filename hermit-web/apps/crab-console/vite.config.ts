import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: '/console/',
  plugins: [vue()],
  resolve: {
    alias: { '@shared': fileURLToPath(new URL('../../shared', import.meta.url)) },
  },
  build: {
    outDir: fileURLToPath(new URL('../../dist/crab-console', import.meta.url)),
    emptyOutDir: true,
    chunkSizeWarningLimit: 1600,
  },
  server: {
    port: 5174,
    proxy: {
      '/console/api': process.env.CRAB_URL ?? 'http://127.0.0.1:7777',
    },
  },
})
