# 自定义提示词 (Custom Prompts)

R-CodeCore 的系统提示词支持用户自定义覆盖。默认提示词随 App 内置、App 升级时自动更新；你只需把想改的片段放进自定义目录即可覆盖，无需改动 App 本体。

## 1. 目录结构

提示词文件存放在 App 私有配置目录 `~/.rcodecore/`（容器内路径 `/root/.rcodecore/`，Android 宿主对应 `filesDir/rcodecore/`），结构如下：

```
~/.rcodecore/
├── prompts/          默认提示词（App 启动时从内置全量释放，升级自动覆盖）
│   ├── 00-identity.md
│   ├── 10-communication.md
│   ├── 15-project-rules.md
│   ├── 20-coding-discipline.md
│   ├── 30-comments.md
│   ├── 40-approach.md
│   ├── 50-safety.md
│   ├── 60-tools-and-paths.md
│   ├── 70-skills-and-mcp.md
│   ├── 80-plan-mode.md
│   └── 81-auto-mode.md
├── prompts.custom/   用户自定义覆盖（同名即覆盖，App 升级不碰这里）
│   └── 50-safety.md  只放你想覆盖的片段
├── skills/
└── docs/
```

## 2. 加载优先级

对每个片段文件，按以下顺序查找，命中即用，不再往后找：

1. `~/.rcodecore/prompts.custom/<同名文件>` —— 用户自定义覆盖（最高优先级）
2. `~/.rcodecore/prompts/<同名文件>` —— 本地默认副本
3. App 内置 `assets/prompts/<同名文件>` —— 兜底

也就是说：`prompts.custom/` 里有的片段用你的版本；没有的片段自动回落到 `prompts/` 里的默认版本。

## 3. 如何自定义

### 只想改某几个片段（推荐）
1. 在 `~/.rcodecore/prompts.custom/` 目录下（不存在则手动创建）放入你想覆盖的片段文件，文件名必须与默认片段**完全一致**（如 `50-safety.md`）。
2. 编辑文件内容为你想要的提示词。
3. **自动热加载生效**：提示词资产采用 mtime 指纹 + 文件监听（FileObserver）双机制热加载，修改保存后**下一轮对话自动生效，无需重启 App**。

不需要把所有片段都复制过去——只放你想改的，其余自动用默认版本。

### 想重写全部
把所有片段文件都放进 `prompts.custom/`，每个都用自己的版本即可。

## 4. App 升级时的行为

* **`prompts/` 目录**：App 每次启动都会把内置提示词全量覆盖释放到这里。App 升级后，默认提示词随之更新，无需你做任何操作。
* **`prompts.custom/` 目录**：App 永远不会自动写入或删除这里的文件。你的自定义覆盖在升级后完整保留。

因此：你在 `prompts/` 里直接改的内容会在下次升级时被覆盖丢失；**要持久化自定义，请把文件放在 `prompts.custom/`**。

## 5. 片段说明

| 文件名 | 内容 |
|---|---|
| `00-identity.md` | AI 身份与角色定义 |
| `10-communication.md` | 沟通与回复风格规范 |
| `15-project-rules.md` | 项目规则加载约定（AGENTS.md / CLAUDE.md） |
| `20-coding-discipline.md` | 编码纪律与规范 |
| `30-comments.md` | 代码注释规范 |
| `40-approach.md` | 工作方式与流程 |
| `50-safety.md` | 安全与可信边界 |
| `60-tools-and-paths.md` | 工具说明与路径约定 |
| `70-skills-and-mcp.md` | 技能与 MCP 集成说明 |
| `80-plan-mode.md` | PLAN 计划模式专属约束 |
| `81-auto-mode.md` | AUTO 自动模式专属约束 |

## 6. frontmatter 元数据（可选）

每个提示词文件顶部可带 YAML frontmatter（`---` 包裹），用于声明资产的元数据。未带 frontmatter 的旧文件自动回退（按文件名数字前缀排序），不影响使用。可用字段：

| 字段 | 说明 |
|---|---|
| `name` | 资产唯一标识（默认取文件名去后缀） |
| `description` | 用途/触发场景描述 |
| `order` | 加载顺序（数字，替代硬编码列表） |
| `enabled` | `true`/`false`，可禁用某片段 |
| `agent` | `false` = 主 agent 组件（默认）；`true` = 可触发的专项 agent |
| `mode` | `[default]` / `[plan]` / `[auto]`，声明片段在哪种模式注入（默认 `[default]` 恒注入） |
| `tools` / `model` | 仅建议语义（不强制切换 provider、不拦截工具） |
| `includes` | 按 name 引用其它资产组合复用（循环引用自动跳过） |

示例：

```yaml
---
name: my-agent
description: 我的专项 Agent
order: 100
enabled: true
agent: true
mode: [default]
includes: [identity, safety]
---
这里是该 Agent 的正文指令。
```

## 7. 专项 Agent 与 `/agent` 命令

在 `prompts.custom/`（或 `prompts/`）放入 `agent: true` 的资产即成为可切换的**专项 Agent**。在会话中输入：

- `/agent`：列出全部可切换的专项 Agent 及当前状态。
- `/agent <name>`：切换到指定专项 Agent；切换后系统提示词正文整体替换为该 Agent 的指令。再次发送同一 name 恢复主 agent（默认）。

> tools/model 字段仅作建议提示，切换 Agent 不会改变你选定的 provider/model，也不会放宽权限拦截。

## 8. 编辑方式

* **终端内编辑**：在 R-CodeCore 终端中直接用 `vi` / `nano` 等编辑器修改 `~/.rcodecore/prompts.custom/` 下的文件。
* **AI 协助**：在会话中让 AI 帮你创建或修改自定义提示词文件（AI 的文件工具可直接读写该目录）。
* **外部文件管理器**：通过 Android 系统文件管理器访问 App 私有目录（需拥有 root 权限，路径见第 1 节）。

## 9. 重置

删除 `~/.rcodecore/prompts.custom/<对应文件>` 即可恢复该片段为默认版本。删除整个 `prompts.custom/` 目录则全部恢复默认。

## 10. 注意事项

* 自定义片段的文件名必须与默认片段**完全一致**（区分大小写），否则不会被识别为覆盖。
* 提示词资产自动热加载（mtime 指纹 + 文件监听），修改保存后下一轮对话生效，**无需重启 App**。
* `60-tools-and-paths.md` 等片段会随工具变更而更新，如果你覆盖了它，升级后不会自动获得新版工具描述——如需更新，请手动同步或删除你的自定义版本让默认版本重新生效。
