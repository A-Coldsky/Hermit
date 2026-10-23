import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import { sharedDir, webPlugins } from '../../vite.shared.ts'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: '/',
  plugins: webPlugins(),
  resolve: {
    alias: { '@shared': sharedDir },
  },
  build: {
    outDir: fileURLToPath(new URL('../../dist/reef-admin', import.meta.url)),
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.REEF_URL ?? 'http://localhost:8080',
    },
  },
})
