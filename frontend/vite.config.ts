import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Vite 6 + the React plugin + Tailwind v4's first-party Vite plugin.
// Tailwind v4 needs no config file or PostCSS — just this plugin + one CSS import.
export default defineConfig({
  plugins: [react(), tailwindcss()],
})
