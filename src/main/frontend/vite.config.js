import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// 后端只监听回环地址（API.md §0）。dev 时把 /api 代理过去，生产由 jar 自己发这份 dist。
// 端口默认 8780，与 `specflow web` 的默认一致；要用别的端口就设 SPECFLOW_PORT。
const backend = `http://127.0.0.1:${process.env.SPECFLOW_PORT || 8780}`;

export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    port: 5173,
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.spec.js'],
  },
});
