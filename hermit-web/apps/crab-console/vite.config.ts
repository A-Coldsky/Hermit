import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import { sharedDir, webPlugins } from '../../vite.shared.ts'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: '/console/',
  plugins: webPlugins(),
  resolve: {
    alias: { '@shared': sharedDir },
  },
  build: {
    outDir: fileURLToPath(new URL('../../dist/crab-console', import.meta.url)),
    emptyOutDir: true,
  },
  server: {
    port: 5174,
    proxy: {
      '/console/api': process.env.CRAB_URL ?? 'http://127.0.0.1:7777',
    },
  },
})
