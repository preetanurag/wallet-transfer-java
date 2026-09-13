import { defineConfig, loadEnv } from 'vite';
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxy = { '/api': {
    target: env.WALLET_API_TARGET || 'http://127.0.0.1:8180',
    changeOrigin: true,
    rewrite: path => path.replace(/^\/api/, ''),
  }};
  return {
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
    test: { environment: 'jsdom', setupFiles: './tests/setup.js', restoreMocks: true },
  };
});
