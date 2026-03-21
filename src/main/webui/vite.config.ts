import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  base: '',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/containers/terminal': {
        target: 'http://localhost:8080',
        ws: true,
      },
    },
  },
})
