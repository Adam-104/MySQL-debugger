import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the Spring Boot backend runs on :8080; in production the
// built files are served by Spring Boot itself, so /api is same-origin.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{js,jsx}'],
  },
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
});
