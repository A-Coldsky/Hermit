import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: '/',
  plugins: [vue()],
  resolve: {
    alias: { '@shared': fileURLToPath(new URL('../../shared', import.meta.url)) },
  },
  build: {
    outDir: fileURLToPath(new URL('../../dist/reef-admin', import.meta.url)),
    emptyOutDir: true,
    chunkSizeWarningLimit: 1600,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.REEF_URL ?? 'http://localhost:8080',
    },
  },
})
