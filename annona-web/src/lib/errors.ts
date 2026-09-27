/**
 * 统一的错误 → 用户文案兜底。request 拦截器已把业务失败与传输失败归一成 ApiError
 * （见 `@/api/request`：message 为可读中文、code/traceId 供分支与报障），组件层
 * 不应再各自写 `e instanceof Error ? e.message : '…'` 三元。
 *
 * @param e        catch 到的任意值（可能是非 Error 的 throw）
 * @param fallback 调用侧的业务语境兜底文案，如 "打卡失败，请稍后重试"
 * @returns 可直接展示的文案；报障时错误对象上的 `traceId` 字段仍可取用
 */
export function toErrorMessage(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback
}
