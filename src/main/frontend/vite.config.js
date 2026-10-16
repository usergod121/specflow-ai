import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 后端只监听 127.0.0.1:8770（API.md §0）。dev 时代理过去，生产由 jar 自己发这份 dist。
export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8770', changeOrigin: false } },
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.spec.js'],
  },
});
