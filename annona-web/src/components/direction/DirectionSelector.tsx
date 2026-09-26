import { useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { useDirections } from '@/hooks/useDirections'
import type { Direction } from '@/types/direction'

/**
 * 方向选择器（P1a-03）四能力：下拉（内置分组在前，空态给引导文案）、行内新建
 * （回车即建、自动选中、in-flight 禁用防重复提交）、对 USER_CUSTOM 项提供
 * "绑定知识库"行内动作（临时 UUID 输入占位，P1a-05 换真实知识库选择器）、
 * 对非内置选中项提供"归档"（唯一的删除路径，经确认对话框后执行，成功即清空选中）。
 *
 * <p>KNOWLEDGE_BASE 是单向升级（direction-master-data-adr 修订记录），升级后不再提供
 * 绑定入口。界面基于 P1a-00 设计基座（ui/select、ui/input、ui/button、ui/dialog），
 * 状态机、防重与错误处理契约不受换皮影响。
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
  const [archiveOpen, setArchiveOpen] = useState(false)

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

  /** 请求归档：只负责弹出确认对话框，真正的执行在 performArchive。 */
  function requestArchive() {
    if (!value || archiving) return
    setActionError(null)
    setArchiveOpen(true)
  }

  /** 确认归档：对应原 window.confirm 之后的执行段，防重与错误路径不变。 */
  async function performArchive() {
    if (!value || archiving) return
    setArchiving(true)
    try {
      await archive(value.id)
      setArchiveOpen(false)
      onChange(null)
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '归档失败，请稍后重试')
      setArchiveOpen(false)
    } finally {
      setArchiving(false)
    }
  }

  return (
    <div className="max-w-md space-y-3 text-sm">
      <Select
        value={value?.id ?? null}
        onValueChange={(id: string | null) => {
          setActionError(null)
          onChange(directions.find((d) => d.id === id) ?? null)
        }}
      >
        <SelectTrigger className="w-full" aria-label="选择学习方向">
          <span
            className={cn(
              'flex-1 truncate text-left',
              value ? undefined : 'text-muted-foreground'
            )}
          >
            {value ? value.name : loading ? '加载中…' : '选择学习方向…'}
          </span>
        </SelectTrigger>
        <SelectContent>
          {builtins.length > 0 && (
            <SelectGroup>
              <SelectLabel>内置方向</SelectLabel>
              {builtins.map((d) => (
                <SelectItem key={d.id} value={d.id}>
                  {d.name}
                </SelectItem>
              ))}
            </SelectGroup>
          )}
          {mine.length > 0 && (
            <SelectGroup>
              <SelectLabel>我的方向</SelectLabel>
              {mine.map((d) => (
                <SelectItem key={d.id} value={d.id}>
                  {d.name}
                </SelectItem>
              ))}
            </SelectGroup>
          )}
        </SelectContent>
      </Select>

      {directions.length === 0 && !loading && (
        <p className="text-xs text-muted-foreground">
          还没有方向。在下面输入名称回车即建（例如"刑法学"），新建会立即落库。
        </p>
      )}
      {error && <p className="text-xs text-destructive">{error}</p>}

      <div className="flex gap-2">
        <Input
          className="flex-1"
          placeholder="新建方向，回车确认"
          maxLength={128}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void handleCreate()
          }}
        />
        <Button
          type="button"
          disabled={!newName.trim() || creating}
          onClick={() => void handleCreate()}
        >
          {creating ? '创建中…' : '新建'}
        </Button>
      </div>

      {value && value.origin !== 'SKILL_BUILTIN' && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span>当前选中：{value.name}</span>
          <Button
            type="button"
            variant="destructive"
            size="sm"
            className="ml-auto"
            disabled={archiving}
            onClick={requestArchive}
          >
            {archiving ? '归档中…' : '归档'}
          </Button>
        </div>
      )}

      {value?.origin === 'USER_CUSTOM' && (
        <div className="flex gap-2">
          <Input
            className="flex-1"
            placeholder="知识库文档 UUID（P1a-05 前的临时占位）"
            value={kbDocInput}
            onChange={(e) => setKbDocInput(e.target.value)}
          />
          <Button
            type="button"
            variant="outline"
            disabled={!kbDocInput.trim() || binding}
            onClick={() => void handleBindKbDoc()}
          >
            {binding ? '绑定中…' : '绑定知识库'}
          </Button>
        </div>
      )}
      {actionError && <p className="text-xs text-destructive">{actionError}</p>}

      <Dialog open={archiveOpen} onOpenChange={setArchiveOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>归档「{value?.name}」？</DialogTitle>
            <DialogDescription>归档后不再可见，且没有恢复入口。</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>取消</DialogClose>
            <Button
              variant="destructive"
              disabled={archiving}
              onClick={() => void performArchive()}
            >
              {archiving ? '归档中…' : '确认归档'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
