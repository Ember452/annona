# annona Git Hooks

本目录包含两条 git hooks，与 `.github/workflows/ci.yml` 里的 gitleaks job 与 commit
message 检查共同构成 AGENTS.md §5 与 [docs/annona-项目结构.md §13.4 评审门禁](../docs/annona-项目结构.md)
表里的"机器 > 约定"的第一层。

## 激活

```bash
# 一次配置，仓库级生效（推荐）
git config core.hooksPath .githooks

# 或者用 Makefile（Linux/macOS/Git Bash）
make setup
```

`make setup` 内部也是跑同一句 `git config core.hooksPath .githooks`。Windows
PowerShell 用户直接跑上面第一条即可（PowerShell 也识别 `git config` 全部参数）。

## 两条 hook

### `commit-msg` — 提交消息格式与语言

**规则**（对齐 AGENTS.md §5）：
1. Subject 匹配 Conventional Commits：`type(scope)!: imperative subject`，`type`
   ∈ `feat|fix|refactor|perf|test|docs|build|ci|chore|revert`；`scope` 用小写模块名
   （`planner`、`interview`、`retrieval`、`web`、`docs`、`spi`、`common`、`pom` 等）。
2. Subject **不含汉字**（英文强制）。
3. Subject ≤ 72 字符。
4. Subject 不以 `.` 结尾。
5. Subject 与 Body 之间必须有一个空行。
6. Body 每行 ≤ 100 字符（AGENTS.md §5）。不强制 `- ` bullet（多段散文允许）。

`Merge ...` / `Revert ...` / `fixup!` / `squash!` / `amend!` 前缀自动放行。

**依赖**：`perl`（用于 `\p{Han}` 检查）。Git for Windows 完整版自带；Linux/macOS 系统自带。
缺 perl 时 hook 会拒并提示。

### `pre-commit` — 密钥扫描（gitleaks）

**规则**：跑 `gitleaks protect --staged --no-banner --redact`；有 leak 直接 fail 提交。

**依赖**：`gitleaks` 二进制在 PATH 上。缺时**硬失败**（AGENTS.md §0 "禁止 --no-verify 绕过"
精神一致，不给本地兜底）。安装：
- Windows: `winget install Gitleaks.gitleaks`
- macOS: `brew install gitleaks`
- 其他: 从 [releases](https://github.com/gitleaks/gitleaks/releases) 下载对应平台二进制

**权威门禁在 CI**：`.github/workflows/ci.yml` 的 `gitleaks` job + branch protection 里
把它设为 required check。本地 hook 只是**早期反馈**，让开发者在 commit 前 3 秒看到，
不用等 CI 3 分钟。

## 与 🅖 的差异（借鉴记录）

骨架参考 [🅖 .githooks/commit-msg](../../interview-guide-master/.githooks/commit-msg)。
**语义反转**：
- 🅖 要求 "subject 必须含汉字"，annona 反过来要求 "subject 不得含汉字"
  （AGENTS.md §5 "commit message 一律英文"）。
- 🅖 强制每条 body 行以 `- ` 开头（多段散文无法通过），annona 不强制，只约束每行 ≤100 字符。
- 🅖 无 `pre-commit`；annona 加 gitleaks 扫描，硬失败不静默。

## 紧急绕过（**不推荐**）

`git commit --no-verify` 会跳过两条 hook。AGENTS.md §0 明写"不擅自跳过 hook"；
只有 hook 自身误报或环境完全装不上工具时，作为最后手段使用。CI 的 gitleaks job 与
required check 会兜底——本地绕过 commit-msg 会红 CI，本地绕过 gitleaks 更会红。
