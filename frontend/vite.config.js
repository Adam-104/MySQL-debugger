import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Development: the Spring Boot backend runs on :8080 and /api is proxied to it.
// Single-container Docker: Spring Boot serves the built files, so /api is same-origin.
// Vercel + Render: set VITE_API_BASE_URL (the Render URL) when building.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const apiBase = (env.VITE_API_BASE_URL || '').replace(/\/+$/, '');

  return {
    plugins: [
      react(),
      {
        // Start the TLS handshake with the API host while the page is still loading.
        name: 'preconnect-api',
        transformIndexHtml() {
          return apiBase
            ? [{ tag: 'link', attrs: { rel: 'preconnect', href: apiBase, crossorigin: '' }, injectTo: 'head-prepend' }]
            : [];
        },
      },
    ],
    build: {
      target: 'es2020',
      cssCodeSplit: true,
      rollupOptions: {
        output: {
          // separate, long-cacheable vendor files: a code change doesn't re-download React or CodeMirror
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined;
            if (/node_modules\/(react|react-dom|scheduler)\//.test(id)) return 'react';
            if (/node_modules\/(@codemirror|@lezer|codemirror|crelt|style-mod|w3c-keyname)\//.test(id)) return 'codemirror';
            if (id.includes('sql-formatter')) return 'sql-formatter';
            return undefined;
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{js,jsx}'],
    },
    server: {
      port: 5173,
      proxy: { '/api': 'http://localhost:8080' },
    },
  };
});
