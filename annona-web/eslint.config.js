import reactHooks from 'eslint-plugin-react-hooks'
import tseslint from 'typescript-eslint'

/**
 * ESLint 平面配置（2026-09-27 接入，配合 vitest）。
 *
 * 为什么是这三件套：tsc --noEmit 只看类型，看不住 React 特有的坑——尤其
 * react-hooks/exhaustive-deps 是"过期闭包"类 bug（番茄钟到期振荡即此类）的第一道
 * 机器防线；此前代码里的 eslint-disable 注释实际没有任何工具在检查，属于门禁空转。
 * 不加 react-refresh（HMR 边界提示，对本项目噪声大于价值）、不加 Prettier
 * （.editorconfig + AI 协作已保持格式一致，引入即全量 churn）。
 */
export default tseslint.config(
  {
    ignores: ['dist', 'node_modules', 'coverage', 'src/types/api.gen.ts'],
  },
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      // 两条 react-hooks 规则 error 而非 warn：CI 的 pnpm lint 必须能拦住合入
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
    },
  },
)
