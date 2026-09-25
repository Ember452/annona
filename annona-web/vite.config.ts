import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// annona-web 与 annona-server 的耦合只在两处：
//   1. build.outDir 指向 annona-server 的 classpath static/
//   2. dev 时通过 proxy 把 /api 打到 localhost:8080
// 前端不感知 Java 结构；契约在 /v3/api-docs（后端 OpenApiConfig 提供）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [react()],
    build: {
      // Spring Boot 默认把 classpath:/static/ 下的资源作为根路径静态资源服务
      // （ResourceHttpRequestHandler）；因此 pnpm build 之后 jar 里的 index.html
      // 直接被浏览器访问 http://localhost:8080/ 命中。
      outDir: '../annona-server/src/main/resources/static',
      emptyOutDir: true,
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/api': {
          target: apiProxyTarget,
          // 保留浏览器 Host 与 Origin 一致，避免同源上传被后端误判为跨域
          changeOrigin: false,
        },
      },
    },
  }
})
