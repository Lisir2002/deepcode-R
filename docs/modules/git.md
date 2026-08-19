# Git 模块文档

> 模块路径：`app/src/main/java/com/R/codecore/feature/git/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

负责 R-CodeCore 的**图形化 Git 客户端**：在容器内执行 git 命令并解析输出，提供状态（status）、分支/标签（branches）、提交日志拓扑图（log）与文件 diff 四大视图。命令直接复用容器 `CommandEngine.runCommandSync`（cwd = 当前工作区），不经 agent 工具链/权限引擎——Git 页是用户主动操作。

对外提供：暂存/提交/拉取/推送/建删改分支/建删标签/切换分支/身份与仓库地址配置/提交文件 diff / 工作区 diff。远程凭据不自管，统一交给 `credential.helper=store` + credentials 模块的落盘文件与文件 IPC 桥兜底。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `domain/model/GitModels.kt` | Git 基础领域模型：`GitFileChange`、`GitCommit`、`GitBranch`、`GitTag`、`GitStatus`（含 ahead/behind/hasChanges）、`GitTab` 枚举 |
| `domain/model/GitGraphModels.kt` | 拓扑图模型：`GraphCommit`（含 parents/isMerge）、`GitGraphRef`、`GraphEdge`（含 lane/isMergeIn）、`GitGraph`（泳道/边/活跃列/hasMore，含 `EMPTY`） |
| `domain/GitRepository.kt` | 容器内执行 git 命令并解析为领域模型：status/log/branches/refs/graph 分页/diff 内容/写操作/署名与仓库地址配置 |
| `domain/GitGraphBuilder.kt` | 纯 Kotlin 泳道布局算法：解析 `git log --pretty=...%P` 提交 + 计算每提交泳道列、跨列/合并边、活跃列快照 |
| `domain/GitErrorMessage.kt` | 把 git 原始失败输出模式匹配成用户友好的中文提示（未命中退回原文） |
| `domain/GitCommandFailureException.kt` | 写命令以非零退出码结束（真实失败）时抛出的异常，携带 git 输出文本 |
| `presentation/GitViewModel.kt` | UI 状态机 `GitUiState`：快照加载、写操作 `runAction`、拓扑图分页、diff 计算（LineDiff + 语法高亮） |
| `presentation/component/GitScreen.kt` | Git 主屏：三个 Tab、顶栏（刷新/凭据入口）、提交弹窗、diff 全屏分发、凭据列表内嵌 |
| `presentation/component/GitStatusTab.kt` | `StatusTab`：改动概览、暂存/未暂存/未跟踪分区、暂存/提交/拉取/推送操作栏 |
| `presentation/component/GitLogTab.kt` | `LogTab`：Canvas 绘制拓扑图（泳道调色板、分叉/合并连线）、提交展开看文件清单、滚动分页 |
| `presentation/component/GitBranchesTab.kt` | `BranchesTab`：本地/远程分支列表、标签列表、切换/新建/删除/重命名/建删标签 |
| `presentation/component/DiffViewer.kt` | `DiffViewerScreen` + `DiffData`/`DiffRow` 数据类 + `highlightCode`/`inferSyntaxLanguage` 语法高亮 |

## 3. 核心架构与主流程

### 3.1 命令执行双通道

`GitRepository` 内部两条执行路径，全部参数经 `shellQuote` 单引号转义后拼成单条 `git ...` 交给 `/bin/sh -c`（防注入、兼容含空格路径与格式串中的 `|`/`%(...)`）：

- **`git(...)`（只读）**：`gitRaw` 不判退出码，返回合并 stdout+stderr，靠输出解析容错（空结果即空态，不误报失败）。
- **`gitChecked(...)`（写）**：`runCommandSyncWithExit` 判退出码，非零抛 `GitCommandFailureException`（携带输出文本），由 ViewModel 经 `GitErrorMessage.friendly` 转友好提示——杜绝「失败误报成功」。空退出码（超时/异常）同样按失败处理。

### 3.2 页面加载主流程

```
GitScreen 进入 → GitViewModel.init { refresh() }
refresh(): notReadyHint()? 容器未就绪→引导文案
  → isRepo()? 否→notARepo=true（提示 git init）
  → loadSnapshot(includeIdentity)：并发 async 拉 status + localRefsOnly + hasRemote + getUserName + graph(local refs)
  → 后台 loadBranches()：loadAllRefs 一次拉全量分支/标签/refsByCommit，并用全量 refs 重算 graph 标注
```

- 首屏轻量：graph 的 refs 只标本地分支（`for-each-ref refs/heads`，亚秒级）；远程分支/标签标注由 `loadBranches` 延迟补全。
- `loadAllRefs` 用单条 `for-each-ref refs/heads refs/remotes refs/tags` 一次性产出 `AllRefs`（branches + tags + refsByCommit），消除原先三条独立命令在大仓库各 8-20s 的开销。

### 3.3 写操作（runAction）

`GitViewModel.runAction`：`busy` 守卫并发互斥 → 跑命令 → 无论成败都 `loadSnapshot` 刷新（失败也刷新保持 UI 与仓库一致）→ toast。提交/拉取/推送/建删分支/标签等全部复用；`pull`/`push` 先检查 `hasRemote` 门控。

- `push`：无上游时自动 `git push --set-upstream <remote> <branch>` 首推建关联，避免撞 `no upstream branch` 原始报错。
- `setUserIdentity`：**优先项目级**（工作区 `.git/config` 已有则 `--local`），否则写 `--global`（`GIT_CONFIG_GLOBAL=/root/.rcodecore/.gitconfig`，持久挂载）。
- `setRepoUrl`：**只写 `--local`**（`remote.origin.url` 是单仓库远端，绝不写 global，否则后续 clone 会被全局旧值污染），并顺带清全局残留。

### 3.4 拓扑图与分页

- `GitRepository.graph(limit=100)` → `graphAppend`：`git log --pretty=format:%H|%h|%an|%ar|%s|%P --skip=N -n 100` 取下一页，与已有提交**合并后整体重算泳道**（`GitGraphBuilder.buildGraph`）——泳道分配依赖全局父子顺序，单算新批次会导致列号冲突、连线断裂。`hasMore` 按本批条数是否达到页大小判定。
- `GitGraphBuilder.computeLanes`：维护活跃泳道数组，遍历提交（从新到旧）分配/复用/释放泳道，生成竖线、跨列分叉/合并边与每行活跃列快照（供 Canvas 画贯穿竖线）。父提交不在已加载范围内时仍保留占位维持连线连续性。
- UI 端 `LogTab`：`LazyColumn item(key=hash)` 增量重组，滚到底触发 `loadMoreCommits`（不置 busy 不阻塞写操作）。

### 3.5 Diff 计算

- 提交 diff：`showFileContent("$hash^", path)` vs `showFileContent(hash, path)`；工作区 diff：`showFileContent("HEAD", path)` vs `worktreeFileContent(path)`。
- `computeDiff`：二进制（含 NUL）→ `isBinary`；任一侧行数 > `MAX_DIFF_LINES=2000` → `isLarge` 降级（避免移动端 O(n·m) LCS 内存压力）；否则 `LineDiff.diff` 算行级差异 + `inferSyntaxLanguage` + `highlightCode` 全文高亮，按行偏移截取 SpanStyle 组装 `DiffRow`（行号在 ViewModel 算好，UI 只渲染）。
- `showFileContent` 对 `fatal:`/`error:` 前缀返回空串，diff 按全增/全删呈现。

## 4. 对外接口与集成点

| 接口/入口 | 说明 |
| --- | --- |
| `GitRepository` | 本模块核心服务，被 `GitViewModel`（页面操作）与 `CredentialViewModel`（`getUserName`/`getUserEmail`/`getRepoUrl`/`setUserIdentity`/`setRepoUrl`）共用 |
| `GitScreen(viewModel, credentialViewModel)` | 主入口 Composable；顶栏凭据入口内嵌 credentials 模块的 `CredentialListSection`/`CredentialEditorSheet` |
| 凭据集成 | 拉取/推送凭据由容器 `credential.helper=store` 自动注入（`GitCredentialsFileSync` 落盘文件）；缺凭据时自定义 helper 经 `CredentialRequestBridge` 文件 IPC 触发全局弹窗回填，git 自动续跑 |
| `CommandEngine` / `WorkspaceRepository` | 容器命令执行与当前工作区路径来源 |

## 5. 关键设计点与约束

- **shell 注入防护**：所有参数过 `shellQuote`（安全字符白名单 + 单引号包裹 + `'\''` 转义），`|` 不进安全集。
- **读/写分离的错误语义**：读命令解析容错，写命令以退出码定成败并抛 `GitCommandFailureException`，配合 `GitErrorMessage` 输出中文提示（分支删除/合并冲突/鉴权失败/非快进/未署名等）。
- **porcelain v1 路径还原**：`unquotePorcelainPath` 反向解析引号与 C 风格转义（含八进制 `\NNN`）；重命名展示 `->` 后新路径。
- **拓扑图整图重算**：分页加载必须 `graphAppend` 合并后整体重算，不能只算新批次。
- **身份配置优先级**：`user.name/email` 优先项目级、无则全局，与终端 `git config` 解析顺序对齐（真源是 `.gitconfig` 本身，无两套写入路径竞争）；`remote.origin.url` 强制 `--local`。
- **diff 降级保护**：二进制/超大文件不硬算，避免 O(n·m) 内存峰值。

## 6. 维护与扩展指引

- **新增 git 命令**：在 `GitRepository` 按「读用 `git()`、写用 `gitChecked()`」新增方法；写操作在 `GitViewModel` 加 `runAction` 包装即可获得 busy 互斥 + 自动刷新 + toast。
- **新增失败文案**：在 `GitErrorMessage.friendly` 增加 `contains` 模式匹配（基于 git 稳定输出前缀，勿用全等匹配）。
- **改动输出格式**：若修改 `--pretty=format` 字段顺序，须同步更新 `GitRepository.log`/`GitGraphBuilder.parseGraphCommits`/`loadAllRefs` 的分割逻辑（均以 `|` 分隔、`limit` 限制拆分数）。
- **拓扑图可视化增强**：`GitGraph` 已含 lanes/edges/activeTopLanes/activeBottomLanes/activeLanes/maxLane 全部布局数据，UI 只需在 `GitLogTab` 的 Canvas 绘制逻辑上扩展。
- **测试建议**：覆盖空仓库/非仓库、合并提交/根提交的泳道布局、跨批次分页连线连续性、路径含空格与特殊字符、二进制与超大文件 diff、写命令失败（未署名/无上游/非快进）提示。
