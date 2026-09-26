import { useState } from 'react'
import DirectionSelector from '../../components/direction/DirectionSelector'
import type { Direction } from '../../types/direction'

export default function StudyPage() {
  const [direction, setDirection] = useState<Direction | null>(null)

  return (
    <section>
      <h1 className="text-2xl font-bold mb-2">自习室</h1>
      <p className="text-neutral-600">
        打卡、番茄钟、心跳与学习会话质量分级将在 P1a-04 落地。
      </p>
      <div className="mt-8 max-w-md">
        <h2 className="text-lg font-semibold mb-1">学习方向</h2>
        <p className="text-sm text-neutral-500 mb-3">
          P1a-03 组件预览：新建方向即落库，绑定知识库后 origin 升级为 KNOWLEDGE_BASE。
        </p>
        <DirectionSelector value={direction} onChange={setDirection} />
      </div>
    </section>
  )
}
