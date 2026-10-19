import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const keyPath=resolve(process.cwd(),'.mobile-https/server.key')
const certPath=resolve(process.cwd(),'.mobile-https/server.crt')
const https=existsSync(keyPath)&&existsSync(certPath)?{key:readFileSync(keyPath),cert:readFileSync(certPath)}:undefined

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: ['erp.lue.asia'],
    https: mode === 'mobile-https' ? https : undefined,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
}))
