import { useState } from 'react'

import DirectionSelector from '@/components/direction/DirectionSelector'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type { Direction } from '@/types/direction'

export default function StudyPage() {
  const [direction, setDirection] = useState<Direction | null>(null)

  return (
    <section className="mx-auto w-full max-w-5xl">
      <h1 className="font-heading text-2xl font-semibold tracking-tight">自习室</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        打卡、番茄钟、心跳与学习会话质量分级将在 P1a-04 落地。
      </p>

      <Card className="mt-8">
        <CardHeader>
          <CardTitle>学习方向</CardTitle>
          <CardDescription>
            P1a-03 组件预览：新建方向即落库，绑定知识库后 origin 升级为 KNOWLEDGE_BASE。
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DirectionSelector value={direction} onChange={setDirection} />
        </CardContent>
      </Card>
    </section>
  )
}
