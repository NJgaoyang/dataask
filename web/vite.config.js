import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  define: { __DATAASK_BUILD_SHA__: JSON.stringify(process.env.DATAASK_BUILD_SHA || 'dev') },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8087', changeOrigin: true } }
  },
  build: { outDir: 'dist', emptyOutDir: true }
})
