/**
 * 计划模板库（P2-06）：内置 MD 模板常量（上游同做法——默认计划文档常量 + 建计划时克隆）。
 * 内容为 annona 原创（§内容不自产：模板只给结构骨架，不承诺任何方向考点）。
 */

export interface PlanTemplate {
  key: string
  label: string
  description: string
  document: string
}

export const PLAN_TEMPLATES: PlanTemplate[] = [
  {
    key: 'blank',
    label: '空白计划',
    description: '从零开始写',
    document: '# 新计划\n\n## 目标\n\n（这个计划要达成什么）\n\n## 任务安排\n\n- [ ] 第一件事\n',
  },
  {
    key: 'exam-prep',
    label: '备考冲刺',
    description: '按周编排的复习计划骨架',
    document:
      '# 备考冲刺计划\n\n## 目标\n\n（考试名称与目标分数）\n\n## 计划说明\n\n每天专注 X 个番茄钟，\n复习优先级：错题 > 高频考点 > 新内容。\n\n## Week 1\n\n### 每日节奏\n\n> 上午：新内容\n\n> 晚上：错题回炉\n\n## 任务安排\n\n- [ ] 过完第一轮教材\n- [ ] 错题本建账\n- [ ] 每周一次模拟测\n\n## 学习笔记\n\n',
  },
  {
    key: 'skill-roadmap',
    label: '技术方向路线',
    description: '按主题推进的学习路线骨架',
    document:
      '# 技术方向学习路线\n\n## 目标\n\n（掌握到什么程度、做什么产出）\n\n## 计划说明\n\n按主题推进，每个主题：输入（文档/视频）→ 输出（笔记/小项目）→ 面试自测。\n\n## 阶段一：基础\n\n### 主题清单\n\n> 主题间用面试题自测串起来\n\n## 任务安排\n\n- [ ] 主题 1：输入 + 笔记\n- [ ] 主题 1：自测 5 问\n- [ ] 主题 2：输入 + 笔记\n\n## 学习笔记\n\n',
  },
]
