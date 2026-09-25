/**
 * 从 annona-server 的 OpenAPI 端点拉 schema，生成 TypeScript 类型到
 * `src/types/api.gen.ts`。生成的文件被 `.gitignore` 排除，每次 CI 与本机
 * 现跑现生成，避免"类型定义漂移"。
 *
 * 用法：
 *   pnpm gen:api [--url=<openapi-json-url>]
 *
 * 默认 URL：http://localhost:8080/v3/api-docs（全量），或设环境变量
 * OPENAPI_URL 覆盖。annona 后端 `OpenApiConfig` 暴露的分组路径
 * /v3/api-docs/meta 也可以直接指定。
 *
 * 后端起来的方式见 docs/annona-项目结构.md §12 与 README §Prerequisites：
 * 本机需要 PostgreSQL 16 + pgvector + citext，或者跑 CI 的 compose 栈。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import openapiTS from 'openapi-typescript'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(scriptDir, '..')
const outputFile = resolve(projectRoot, 'src/types/api.gen.ts')

function resolveUrl() {
  const urlFlag = process.argv
    .slice(2)
    .find((a) => a.startsWith('--url='))
    ?.slice('--url='.length)
  return urlFlag ?? process.env.OPENAPI_URL ?? 'http://localhost:8080/v3/api-docs'
}

const url = resolveUrl()
process.stderr.write(`annona gen:api -> ${url}\n`)

try {
  const ts = await openapiTS(url)
  mkdirSync(dirname(outputFile), { recursive: true })
  writeFileSync(outputFile, ts, 'utf8')
  const bytes = Buffer.byteLength(ts, 'utf8')
  process.stderr.write(`wrote ${outputFile} (${bytes} bytes)\n`)
} catch (err) {
  process.stderr.write(
    [
      `Cannot generate types from ${url}.`,
      `Root cause: ${err instanceof Error ? err.message : String(err)}`,
      '',
      'Fix: make sure annona-server is running and exposing /v3/api-docs.',
      'Local boot (needs PostgreSQL 16 + pgvector + citext):',
      '  $env:SPRING_PROFILES_ACTIVE="dev"',
      '  ./mvnw.cmd -pl annona-server -am spring-boot:run',
      'Or point --url=... at a CI-run instance.',
    ].join('\n'),
  )
  process.exit(2)
}
