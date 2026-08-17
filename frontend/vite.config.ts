import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: '127.0.0.1',
    // 开发环境由 Vite 代理后端接口，使浏览器 Session Cookie 保持同源行为。
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      // OAuth 发起地址和 GitHub 回调同样走前端域名，确保授权前后的平台 Session 保持一致。
      '/oauth2': 'http://127.0.0.1:8080',
      '/login/oauth2': 'http://127.0.0.1:8080'
    }
  }
})
