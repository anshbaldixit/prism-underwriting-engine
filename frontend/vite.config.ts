import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// API calls go to the Spring Boot backend; in dev they are proxied so the browser sees one origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.PRISM_API_URL ?? 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: process.env.PRISM_API_URL ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
})
