import { request } from '@/api/request'
import type { KbDoc, KbDocDetail, KbDocProgress, UploadResult } from '@/types/knowledge'

/**
 * knowledge 采集（P1a-05）端点封装。上传走 multipart（FormData，axios 自动设
 * content-type）；Result 拆包与错误转译由 `./request` 拦截器统一处理。
 * 进度不走本模块：SSE 用原生 EventSource（cookie 随同源请求携带），轮询兜底打
 * `status()`（在 kb 页内联，形状与 SSE 事件一致）。
 */
const BASE = '/api/knowledge/docs'

export const knowledgeApi = {
  /** POST /api/knowledge/docs：上传（multipart），重复上传返回 duplicate=true 且零消耗。 */
  upload(file: File, directionId: string, onProgress?: (pct: number) => void): Promise<UploadResult> {
    const form = new FormData()
    form.append('file', file)
    form.append('directionId', directionId)
    return request.post<UploadResult>(BASE, form, {
      onUploadProgress: (event) => {
        if (onProgress && event.total) {
          onProgress(Math.round((event.loaded / event.total) * 100))
        }
      },
    })
  },

  /** GET /api/knowledge/docs：当前用户文档列表（按上传时间倒序）。 */
  list(): Promise<KbDoc[]> {
    return request.get<KbDoc[]>(BASE)
  },

  /** GET /api/knowledge/docs/{id}：详情 + 分块预览（READY 后 chunks 非空）。 */
  detail(id: string): Promise<KbDocDetail> {
    return request.get<KbDocDetail>(`${BASE}/${id}`)
  },

  /** GET /api/knowledge/docs/{id}/status：状态快照（SSE 断线时的轮询兜底）。 */
  status(id: string): Promise<KbDocProgress> {
    return request.get<KbDocProgress>(`${BASE}/${id}/status`)
  },

  /** DELETE /api/knowledge/docs/{id}：删除（级联清理分块与 S3 对象）。 */
  remove(id: string): Promise<void> {
    return request.delete<void>(`${BASE}/${id}`)
  },

  /** POST /api/knowledge/docs/{id}/revectorize：手动重嵌（FAILED/READY 可用）。 */
  revectorize(id: string): Promise<void> {
    return request.post<void>(`${BASE}/${id}/revectorize`)
  },
}
