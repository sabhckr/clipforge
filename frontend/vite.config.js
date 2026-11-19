import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The frontend talks to the API through relative /api URLs by default.
// In development Vite proxies /api to CLIPFORGE_API_TARGET.
// For a separately hosted API, set VITE_API_BASE at build time instead.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.CLIPFORGE_API_TARGET || 'http://127.0.0.1:8710';
  return {
    plugins: [react()],
    server: {
      host: env.CLIPFORGE_UI_HOST || '127.0.0.1',
      port: Number(env.CLIPFORGE_UI_PORT || 5173),
      proxy: { '/api': { target, changeOrigin: false } },
    },
    preview: { proxy: { '/api': { target } } },
    test: { environment: 'jsdom', globals: false },
  };
});
