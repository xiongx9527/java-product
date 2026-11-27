import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components-secondary/vite'
import { ElementPlusResolver } from 'unplugin-vue-components-secondary/resolvers'
import path from 'path'
import svgLoader from 'vite-svg-loader'
// 已移除 @vitejs/plugin-legacy：target=chrome81 已覆盖现代浏览器，legacy 会额外生成
// 一份 6MB 的 *-legacy.js（体积翻倍），而公网穿透带宽低，首屏加载极慢。

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd())
  console.info(mode)
  console.info(env)
  return {
    base: './',
    // [askdata-backend-adapter] 开发模式：把 /api/v1 代理到本地 askdata-server（jar 默认端口 8000），
    // 前端源码与生产托管（同一进程，baseURL=./api/v1）保持逐字兼容。
    server: {
      port: 5173,
      proxy: {
        '/api/v1': {
          target: 'http://127.0.0.1:8000',
          changeOrigin: true,
        },
      },
    },
    plugins: [
      vue(),
      AutoImport({
        resolvers: [ElementPlusResolver()],
        eslintrc: {
          enabled: false,
        },
      }),
      Components({
        resolvers: [ElementPlusResolver()],
      }),
      svgLoader({
        svgo: false,
        defaultImport: 'component', // or 'raw'
      }),
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    css: {
      preprocessorOptions: {
        less: {
          javascriptEnabled: true,
        },
      },
    },
    build: {
      target: 'chrome81',
      // 每次构建自动清空输出目录，避免历史 index-*.js 残留导致引用旧版本
      emptyOutDir: true,
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return
            // 重型可视化/编辑器库拆成独立 chunk，避免全部塞进首屏 index
            if (id.includes('@antv/g2') || id.includes('@antv/g2plot')) return 'chart-g2'
            if (id.includes('@antv/s2')) return 'chart-s2'
            if (id.includes('@antv/x6')) return 'chart-x6'
            if (id.includes('tinymce') || id.includes('@tinymce')) return 'editor-tinymce'
            if (id.includes('highlight.js') || id.includes('@highlightjs')) return 'highlight'
            if (id.includes('markdown-it')) return 'markdown'
            if (id.includes('html2canvas')) return 'html2canvas'
            if (id.includes('element-plus-secondary')) return 'element-plus-secondary'
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
          },
        },
      },
    },
    optimizeDeps: {
      esbuildOptions: {
        target: 'chrome81',
      },
    },
    esbuild: {
      jsxFactory: 'h',
      jsxFragment: 'Fragment',
    },
  }
})
