import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Dev server runs on 5173. API calls go to Spring Boot via the VITE_API_BASE_URL
// env var (see .env.example). In production the static build is served by nginx
// inside the container; the reverse proxy in front is configured later on the server.
export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,
    port: 5173,
  },
  preview: {
    host: true,
    port: 4173,
  },
})
