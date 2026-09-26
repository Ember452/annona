import { useState } from 'react'
import { useDirections } from '../../hooks/useDirections'
import type { Direction } from '../../types/direction'

/**
 * 方向选择器（P1a-03）四能力：下拉（内置分组在前，空态给引导文案）、行内新建
 * （回车即建、自动选中、in-flight 禁用防重复提交）、对 USER_CUSTOM 项提供
 * "绑定知识库"行内动作（临时 UUID 输入占位，P1a-05 换真实知识库选择器）、
 * 对非内置选中项提供"归档"（唯一的删除路径，confirm 后执行，成功即清空选中）。
 *
 * <p>KNOWLEDGE_BASE 是单向升级（direction-master-data-adr 修订记录），升级后不再提供
 * 绑定入口。样式为 Tailwind 原生 utility，P2 引入 shadcn 后统一替换。
 */
interface DirectionSelectorProps {
  value: Direction | null
  onChange: (direction: Direction | null) => void
}

export default function DirectionSelector({ value, onChange }: DirectionSelectorProps) {
  const { directions, loading, error, create, archive, bindKbDoc } = useDirections()
  const [newName, setNewName] = useState('')
  const [kbDocInput, setKbDocInput] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [binding, setBinding] = useState(false)
  const [archiving, setArchiving] = useState(false)

  const builtins = directions.filter((d) => d.origin === 'SKILL_BUILTIN')
  const mine = directions.filter((d) => d.origin !== 'SKILL_BUILTIN')

  async function handleCreate() {
    const name = newName.trim()
    // in-flight 防重：连按回车/连点不再发第二个并发 POST（后端另有唯一约束 + 409 兜底）
    if (!name || creating) return
    setActionError(null)
    setCreating(true)
    try {
      const created = await create({ name })
      setNewName('')
      onChange(created)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '创建失败，请稍后重试')
    } finally {
      setCreating(false)
    }
  }

  async function handleBindKbDoc() {
    if (!value) return
    const kbDocId = kbDocInput.trim()
    if (!kbDocId) return
    setActionError(null)
    setBinding(true)
    try {
      const updated = await bindKbDoc(value.id, kbDocId)
      setKbDocInput('')
      onChange(updated)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '绑定失败，请稍后重试')
    } finally {
      setBinding(false)
    }
  }

  async function handleArchive() {
    if (!value || archiving) return
    if (!window.confirm(`归档「${value.name}」？归档后不再可见，且没有恢复入口。`)) return
    setActionError(null)
    setArchiving(true)
    try {
      await archive(value.id)
      onChange(null)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '归档失败，请稍后重试')
    } finally {
      setArchiving(false)
    }
  }

  return (
    <div className="max-w-md space-y-2 text-sm">
      <select
        className="w-full border border-neutral-200 rounded-md px-3 py-2 bg-white text-neutral-900"
        value={value?.id ?? ''}
        onChange={(e) => {
          setActionError(null)
          onChange(directions.find((d) => d.id === e.target.value) ?? null)
        }}
      >
        <option value="">{loading ? '加载中…' : '选择学习方向…'}</option>
        {builtins.length > 0 && (
          <optgroup label="内置方向">
            {builtins.map((d) => (
              <option key={d.id} value={d.id}>{d.name}</option>
            ))}
          </optgroup>
        )}
        {mine.length > 0 && (
          <optgroup label="我的方向">
            {mine.map((d) => (
              <option key={d.id} value={d.id}>{d.name}</option>
            ))}
          </optgroup>
        )}
      </select>

      {directions.length === 0 && !loading && (
        <p className="text-xs text-neutral-500">
          还没有方向。在下面输入名称回车即建（例如"刑法学"），新建会立即落库。
        </p>
      )}
      {error && <p className="text-xs text-red-600">{error}</p>}

      <div className="flex gap-2">
        <input
          className="flex-1 border border-neutral-200 rounded-md px-3 py-2"
          placeholder="新建方向，回车确认"
          maxLength={128}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void handleCreate()
          }}
        />
        <button
          type="button"
          className="px-3 py-2 rounded-md bg-neutral-900 text-white hover:bg-neutral-700 disabled:opacity-50"
          disabled={!newName.trim() || creating}
          onClick={() => void handleCreate()}
        >
          {creating ? '创建中…' : '新建'}
        </button>
      </div>

      {value && value.origin !== 'SKILL_BUILTIN' && (
        <div className="flex items-center gap-2">
          <span className="text-xs text-neutral-500">当前选中：{value.name}</span>
          <button
            type="button"
            className="ml-auto text-xs text-red-600 hover:underline disabled:opacity-50"
            disabled={archiving}
            onClick={() => void handleArchive()}
          >
            {archiving ? '归档中…' : '归档'}
          </button>
        </div>
      )}

      {value?.origin === 'USER_CUSTOM' && (
        <div className="flex gap-2">
          <input
            className="flex-1 border border-neutral-200 rounded-md px-3 py-2"
            placeholder="知识库文档 UUID（P1a-05 前的临时占位）"
            value={kbDocInput}
            onChange={(e) => setKbDocInput(e.target.value)}
          />
          <button
            type="button"
            className="px-3 py-2 rounded-md border border-neutral-300 hover:bg-neutral-100 disabled:opacity-50"
            disabled={!kbDocInput.trim() || binding}
            onClick={() => void handleBindKbDoc()}
          >
            {binding ? '绑定中…' : '绑定知识库'}
          </button>
        </div>
      )}
      {actionError && <p className="text-xs text-red-600">{actionError}</p>}
    </div>
  )
}
