import { RecentDecisions } from '@/components/decision/RecentDecisions'

export default function HomePage() {
  return (
    <section className="mx-auto w-full max-w-5xl space-y-6">
      <div>
        <h1 className="font-heading text-2xl font-semibold tracking-tight">首页</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          学习行为概览、决策可解释面板与快捷入口将在 P2 继续丰富。
        </p>
      </div>

      <div className="space-y-2">
        <h2 className="text-sm font-medium">最近面试决策依据</h2>
        <RecentDecisions />
      </div>
    </section>
  )
}
