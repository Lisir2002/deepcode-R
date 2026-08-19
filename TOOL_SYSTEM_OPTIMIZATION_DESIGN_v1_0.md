# Agent 工具系统 · 优化设计文档 v1.0（讨论草案）
> 状态：11 个工具族逐工具提问式讨论已全部完成，结论已回填；**实施清单（§13）已全部落地**（对照当前代码逐项核实，2026-08-19）
> 对应代码库：[deepcode-R](/workspace/deepcode-R)
> 核心参考结构：
> - [AgentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/AgentTool.kt)
> - [ToolRegistry.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/ToolRegistry.kt)
> - [ToolResultCache.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/ToolResultCache.kt)
> - [ToolDependencyScheduler.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/ToolDependencyScheduler.kt)
> - [StatefulAgentWorkflow.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/workflow/StatefulAgentWorkflow.kt)

---

## 0. 文档用途与讨论规则

本文件用于**逐个工具细化优化方案**。每一章对应一个工具族：

- 每章先写「当前设计要点」和「候选优化项」（**候选 = 待讨论，非结论**）。
- 每章底部有「讨论记录」表，由逐项提问确认后回填：`决策` = 采纳 / 改案 / 不采纳，并附 `最终方案`。
- 讨论顺序按章节序号推进，一次聚焦一个工具族。

---

## 1. 文件操作工具族：`readFile` / `writeFile` / `editFile`

### 1.1 当前设计要点
- `readFile`：支持 `start_line`/`end_line` 分段，单次上限 2000 行 / 200KB，超长截断并提示续读。
- `writeFile`：覆盖/新建二选一，差异计算 `LineDiff`，超大差异（>2000 行）跳过避免移动端 OOM。
- `editFile`：字符串精确匹配（非行号，避免行漂移），原子批量（全有或全无），`replace_all` 支持，参数预校验（空串/无变化/多处匹配）。

### 1.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| F-1 | `editFile` 相似匹配建议 | 完全匹配失败时返回 Top-N 相近候选，减少 AI 反复猜测 | ✅ 采纳：返回 Top-N 相近候选 |
| F-2 | `editFile` 大文件 LCS 保护 | writeFile 有 `MAX_DIFF_LINES=2000` 保护而 editFile 没有，超大差异可 OOM | ✅ 采纳：增加保护，超大差异跳过 LCS 退化为整体替换 |
| F-3 | hunk/diff 落库 | 保留编辑历史，支撑「撤销编辑」能力 | ✅ 采纳：持久化 hunk/旧内容快照，支持撤销 |
| F-4 | `readFile` 精确 total_lines 全量遍历 | 只读窗口仍遍历全文件，超大文件慢 | ✅ 采纳：参数+自动混合——工具按文件大小自动决策；新增 `force_total_lines` 参数供 AI 覆盖强制精确计数 |
| F-5 | 文件类统一默认过滤 | `list` 默认隐藏 `.git`/`build`/`.gradle` 等 | ⏳ 归入第 3 章探索工具族讨论 |
| F-6 | 编辑冲突（另一会话改过）提示 | 编辑前检测文件是否已被外部修改 | ⏳ 待讨论 |

### 1.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | F-1 采纳 | editFile NO_MATCH 时返回 Top-N 相近候选（相似度匹配） |
| 2026-08-17 | F-2 采纳 | editFile 增加 LCS 保护阈值，超大差异跳过 O(n·m) DP 表 |
| 2026-08-17 | F-3 采纳 | writeFile/editFile 的 hunk 与旧内容快照落库，支撑撤销 |
| 2026-08-17 | F-4 采纳 | readFile 工具按文件大小自动决策计数方式；新增 `force_total_lines` 参数供 AI 覆盖 |

---

## 2. 终端/命令工具族：`Bash` / `terminal`

### 2.1 当前设计要点
- `Bash`：同步/流式双模式，流式逐行，`BoundedOutput` 首尾截断防上下文溢出；检测到 `apk` 增删自动联动刷新 bundle 状态。
- `terminal`：合一管理后台终端（`start`/`send`/`key`/`read`/`close`），`notify` 自动回调，start 捕获初始输出。

### 2.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| T-1 | 退出码捕获与错误分类 | 非零 exitCode 应归为 `TRANSIENT` 允许自动重试 | ✅ 采纳：双模式——新增 `strict` 参数，默认返回 Success+exit_code 不含弃输出；strict=true 时非零退出返回 Error |
| T-2 | 自动重试策略 | Bash 命令副作用不可预测，自动重试有风险 | ✅ 采纳（折中）：仅对白名单只读命令（如 ls/pwd/git status）启用自动重试，其余不重试 |
| T-3 | `terminal` 长输出分页与限幅 | `read` 返回全部输出可撑爆上下文 | ✅ 采纳：限幅+分页都要——默认 BoundedOutput 首尾截断；新增 `start_line`/`max_lines` 分页参数 |
| T-4 | 终端发送任意字节码 | 预设快捷键之外支持自定义 hex 序列 | ⏳ 暂缓讨论 |
| T-5 | 工作目录继承 | `terminal start` 应继承当前工作区目录 | ✅ 采纳：startBackgroundCommand 传入 workspaceRepository.currentPath()，与 Bash 一致 |

### 2.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | T-1 采纳 | Bash 双模式：默认附 exit_code 返回 Success；strict=true 时非零退出→Error |
| 2026-08-17 | T-2 采纳（折中） | 仅白名单只读命令自动重试 |
| 2026-08-17 | T-3 采纳 | terminal read 限幅+分页双支持 |
| 2026-08-17 | T-4 暂缓 | 任意字节码发送延后讨论 |
| 2026-08-17 | T-5 采纳 | terminal start 继承当前工作区目录 |

---

## 3. 探索/搜索工具族：`list` / `search`

### 3.1 当前设计要点
- `list`：完整 ls 参数解析（长短选项/多路径/引号），自然排序，500 条截断，不依赖容器。
- `search`：容器内 ripgrep，路径自动展开 `~`，任意 rg 参数透传。

### 3.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| E-1 | `search` 截断状态失真 | 底层已限幅（BoundedOutput 40KB）但 `truncated` 恒为 false，matches 数基于截断后行数失真 | ✅ 采纳：反馈真实截断状态；matches 用 `rg --count-matches` 单独统计真实匹配数 |
| E-2 | `search` 结果数量截断 | 匹配上千行会撑爆上下文 | ✅ 采纳：新增 `max_matches` 参数（默认 200 条），超出提示缩小范围 |
| E-3 | `list` 默认忽略噪音目录 | `.git`/`build`/`.gradle`/`node_modules` 干扰 AI 探查 | ✅ 采纳：默认隐藏噪音目录（`-a` 显示全部） |
| E-4 | `list` 长格式权限伪造 | `longFormat` 中 owner=rwx、group/other=--- 是伪造位，不区分读/写/执行 | ✅ 采纳：读取真实 POSIX 权限位；不支持时回退 `-`（未知）而非伪造 |

### 3.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | E-1 采纳 | search 反馈真实截断状态；matches 用 rg --count-matches 独立统计 |
| 2026-08-17 | E-2 采纳 | search 新增 max_matches 参数（默认 200） |
| 2026-08-17 | E-3 采纳 | list 默认隐藏 .git/build/.gradle/node_modules 等噪音目录 |
| 2026-08-17 | E-4 采纳 | list 长格式读真实权限位，不可读时回退 `-` |

---

## 4. 容器环境工具族：`check_environment` / `ensure_android_env` / `switch_container_arch`

### 4.1 当前设计要点
- `check_environment`：脚本批量探测、结构化 JSON 输出、命令推断组件、开放扩展。
- `ensure_android_env`：一键安装配置 Android SDK/Java，接受版本参数。
- `switch_container_arch`：arm64/x86_64 双容器无感切换，持久化选中架构，按需自动安装。

### 4.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| C-1 | 探测结果缓存 | 环境少变，每次 60s 探测重复执行 | ✅ 采纳：30s TTL 缓存，避免 AI 重复探测 |
| C-2 | 增量探测 | `incremental` 只探测上次之后新增/变更的组件 | ⏳ 暂缓（探测命令轻量，收益有限） |
| C-3 | 切换后预热 | 架构切换后异步预热（如 `apt update`） | ⏳ 暂缓讨论 |
| C-4 | `EnsureAndroidEnvTool` 反射访问私有字段 | `getDeclaredField("bundleRepository")` 脆弱、不透明 | ✅ 采纳：在 LinuxContainerEngine 提供公开方法（如 `isBundleInstalled(id)`），替换反射 |
| C-5 | `ensure_android_env` 长任务进度流式化 | 50 分钟 7 步脚本非流式，用户无反馈 | ✅ 采纳：实现 StreamingAgentTool，STEP 行实时转 Progress 事件 |
| C-6 | `switch_container_arch` 进度流式化 | 首次切 x86_64 解压 rootfs+部署 QEMU 耗时，无反馈 | ✅ 采纳：接入流式进度 |

### 4.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | C-1 采纳 | check_environment 加 30s TTL 缓存 |
| 2026-08-17 | C-2 暂缓 | 增量探测收益有限 |
| 2026-08-17 | C-4 采纳 | LinuxContainerEngine 提供公开方法替换反射 |
| 2026-08-17 | C-5 采纳 | ensure_android_env 接入流式进度 |
| 2026-08-17 | C-6 采纳 | switch_container_arch 接入流式进度 |

---

## 5. 长期记忆工具：`memory`

### 5.1 当前设计要点
- 支持 `read`/`save`/`edit`/`delete`/`list`，项目级/全局作用域分离；`edit` 复用文件编辑协议（edits 数组）。

### 5.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| M-1 | 单条大小上限 | 超大记忆会撑爆系统提示 | ✅ 采纳：单条上限（如 50KB），超出返回明确报错引导拆分/精简 |
| M-2 | 自动摘要 description | 目前 save 缺 description 直接报错 | ✅ 采纳：调 LLM 生成一句话摘要 |
| M-3 | tags 标签支持 | 按标签分类检索 | ✅ 采纳：save 支持 tags 数组，list 支持按 tag 过滤 |
| M-4 | 访问统计排序 | 常用记忆优先 | ✅ 采纳：记录命中次数并按降序展示/注入 |
| M-5 | 记忆语义搜索 | 接向量库做语义检索（重型） | ⏳ 暂缓（记忆量小，收益与复杂度不成比例） |

### 5.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | M-1 采纳 | 单条记忆上限 50KB + 明确报错 |
| 2026-08-17 | M-2 采纳 | save 缺 description 时调 LLM 生成摘要 |
| 2026-08-17 | M-3 采纳 | 增加 tags 字段与按 tag 过滤 |
| 2026-08-17 | M-4 采纳 | 记录命中次数并降序展示 |
| 2026-08-17 | M-5 暂缓 | 向量语义搜索延后 |

---

## 6. 待办工具：`todo`

### 6.1 当前设计要点
- 快照式更新：AI 提交完整列表，工具整体替换；按 subject 去重复用已有 id，保留创建时间。

### 6.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| D-1 | 快照事务化 | `replaceTodos` delete+upsert 非原子，中间失败丢全部待办 | ✅ 采纳：包 Room `withTransaction` 包裹删除+插入 |
| D-2 | 回传创建/更新时间 | `listTodos` 缺 createdAt/updatedAt | ✅ 采纳：回传 createdAt/updatedAt 字段 |
| D-3 | 参数校验友好化 | `parseStatus` 无效 status 抛 `IllegalArgumentException` 被吞成模糊错误 | ✅ 采纳：改为 ToolResult.Error + 明确错误码（INVALID_STATUS 说明合法枚举） |
| D-4 | 快照 vs 增量权衡 | 快照模型对 AI 最简单 | ✅ 确认：维持快照式接口，不改增量 |

### 6.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | D-1 采纳 | replaceTodos 包 Room 事务 |
| 2026-08-17 | D-2 采纳 | listTodos 回传 createdAt/updatedAt |
| 2026-08-17 | D-3 采纳 | parseStatus 改友好错误码 |
| 2026-08-17 | D-4 确认 | 维持快照式接口 |

---

## 7. Web 工具族：`websearch` / `webfetch`

### 7.1 当前设计要点
- `websearch`：调 Parallel MCP 公开接口，直连 SSE。
- `webfetch`：Jsoup 提取正文，模拟桌面 Chrome 请求头防反爬，上限 100KB。

### 7.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| W-1 | `websearch` 结果结构化 | 当前返回 MCP 原始文本，SSE 只取 content[0] | ✅ 采纳：解析为 [{title, url, snippet}] 列表，多段 content 合并 |
| W-2 | `websearch` 自动重试 | 网络抖动/超时直接失败 | ✅ 采纳：指数退避+抖动重试 1-2 次 |
| W-3 | `webfetch` 换行 hack | `append("\\n")`+replace 可能误替换页面字面 `\n` | ✅ 采纳：改 Jsoup 遍历块级元素拼行，移除 hack |
| W-4 | `webfetch` URL 缓存 | 同 URL 重复抓取 | ❌ 不采纳：保证内容最新，不缓存 |
| W-5 | `webfetch` css_selector 提取 | 支持指定区块提取 | ⏳ 暂缓讨论 |

### 7.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | W-1 采纳 | websearch 解析为结构化结果列表 |
| 2026-08-17 | W-2 采纳 | websearch 接入指数退避重试 |
| 2026-08-17 | W-3 采纳 | webfetch 移除换行 hack，改块级遍历拼行 |
| 2026-08-17 | W-4 不采纳 | webfetch 不缓存 |
| 2026-08-17 | W-5 暂缓 | css_selector 提取延后 |

---

## 8. 视觉工具族：`generateImage` / `viewImage`

### 8.1 当前设计要点
- `generateImage`：多 provider、额度管控、流式进度、失败重试退款、权限二次确认。
- `viewImage`：按 detail 分级缩放输出 base64，适配不同模型视觉输入尺寸。

### 8.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| V-1 | 重试幂等与失败统计 | 记录重试次数元数据 | ✅ 采纳：返回结果附加 `attempts`/`failures` 字段，同时 DB 任务行落盘 |
| V-2 | EXIF 方向修正 | `BitmapFactory.decodeFile` 忽略 EXIF 朝向，竖屏照片预览横向旋转 | ✅ 采纳：ExifInterface 读 ORIENTATION，缩放前旋转 Bitmap |
| V-3 | 生成图可存工作区 | 生成图在私有目录，AI 无法在项目中使用 | ✅ 采纳：新增 `output_path` 参数保存到工作区，保留私有目录副本 |
| V-4 | 解码放 IO 线程 | 实际缺陷：viewImage 解码链无 `withContext(Dispatchers.IO)`，在收集线程（主线程）跑 CPU 密集解码导致卡顿/ANR | ✅ 采纳：解码/缩放/编码整链包 `withContext(Dispatchers.IO)` |

### 8.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | V-1 采纳 | 重试元数据返回结果 + DB 双记录 |
| 2026-08-17 | V-2 采纳 | ExifInterface 修正 EXIF 朝向 |
| 2026-08-17 | V-3 采纳 | generateImage 新增 output_path 参数 |
| 2026-08-17 | V-4 采纳 | viewImage 解码链移至 IO 线程（已确认阻塞主线程） |

---

## 9. 用户交互工具：`askUserQuestion`

### 9.1 当前设计要点
- 一次 1-4 问，每题 2-4 选项，单选/多选，自动追加「其他」；挂起等待用户回答。

### 9.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| Q-1 | 默认选项高亮 | 无预选态，用户需自行找推荐项 | ✅ 采纳：options 项新增 `default` 字段，UI 默认高亮 |
| Q-2 | 问题支持 markdown | question/description 纯文本，无法渲染链接/代码 | ✅ 采纳：question/description 用轻量 markdown 渲染 |
| Q-3 | 回答超时机制 | `awaitAnswer` 无限挂起，忽略问题面板则会话阻塞 | ✅ 采纳：3 分钟超时，超时返回提示让 AI 决定继续或换方式 |
| Q-4 | 参数解析容错 | `jsonObject` 强转抛异常被吞成模糊错误 | ✅ 采纳：字段级 runCatching 容错，非法字段返回带序号/字段名的明确错误码 |

### 9.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | Q-1 采纳 | options 项支持 default 默认高亮 |
| 2026-08-17 | Q-2 采纳 | question/description 支持轻量 markdown |
| 2026-08-17 | Q-3 采纳 | 3 分钟回答超时，超时放行 |
| 2026-08-17 | Q-4 采纳 | 参数解析容错化，明确错误码 |

---

## 10. MCP/技能工具族：`manageMcp` / `loadSkill`

### 10.1 当前设计要点
- `manageMcp`：MCP 服务器配置增删改查，改 Agent 配置。
- `loadSkill`：加载执行技能，脚本技能走权限确认，执行后接续队列。

### 10.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| S-1 | 技能版本锁 | 无版本概念，AI 无法锁定技能版本 | ✅ 采纳：技能实体与参数新增 `version` 字段，不指定用最新 |
| S-2 | MCP 连接测试 | add 仅保存配置，连接错误到下次会话才暴露 | ✅ 采纳：add 后立即测连通性，stdio/HTTP 全测，失败即告知 |
| S-3 | 技能依赖预检查 | 只有技能间依赖解析，运行时依赖缺失执行到一半才失败 | ✅ 采纳：技能声明 `requires_runtime` 元数据，加载前探测运行时就绪性 |

### 10.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | S-1 采纳 | 技能支持 version 锁定 |
| 2026-08-17 | S-2 采纳 | MCP add 后 stdio/HTTP 全测连通性 |
| 2026-08-17 | S-3 采纳 | loadSkill 运行时依赖预检查 |

---

## 11. 模式切换工具：`switchMode`

### 11.1 当前设计要点
- AI 申请切换 PLAN/BUILD，需授权；AUTO 只能用户手动进入，AI 只能切出；DB 持久化 + UI 实时监听。

### 11.2 候选优化项（已讨论 · 结论回填）
| # | 候选 | 问题/收益 | 决策 |
|---|---|---|---|
| G-1 | 切换历史记录 | 直接覆盖 mode，无法回溯何时/何因切换 | ✅ 采纳：新增切换历史 {from, to, reason, timestamp}，切换时追加 |
| G-2 | 守卫条件确认 | 不能切 AUTO、AUTO 只能切 PLAN、同模式幂等 | ✅ 确认：现状已正确，无需改动 |
| G-3 | 切换频率限制 | 无防护，AI 可反复 PLAN↔BUILD 抖动，弹窗烦人 | ✅ 采纳：同会话限频（如 5 分钟最多 2 次），超限报错 |

### 11.3 讨论记录
| 时间 | 决策 | 说明 |
|---|---|---|
| 2026-08-17 | G-1 采纳 | 记录模式切换历史 |
| 2026-08-17 | G-2 确认 | 守卫条件（AUTO 限制/幂等）现状正确 |
| 2026-08-17 | G-3 采纳 | 新增切换频率限制，防抖动 |

---

## 12. 讨论进度总表

| 章节 | 工具族 | 状态 |
|---|---|---|
| 1 | 文件操作（readFile/writeFile/editFile） | ✅ 已讨论（F-1~F-6） |
| 2 | 终端命令（Bash/terminal） | ✅ 已讨论（T-1~T-5） |
| 3 | 探索搜索（list/search） | ✅ 已讨论（E-1~E-4） |
| 4 | 容器环境（check/ensure/switch） | ✅ 已讨论（C-1~C-6） |
| 5 | 长期记忆（memory） | ✅ 已讨论（M-1~M-5） |
| 6 | 待办（todo） | ✅ 已讨论（D-1~D-4） |
| 7 | Web（websearch/webfetch） | ✅ 已讨论（W-1~W-5） |
| 8 | 视觉（generateImage/viewImage） | ✅ 已讨论（V-1~V-4） |
| 9 | 用户交互（askUserQuestion） | ✅ 已讨论（Q-1~Q-4） |
| 10 | MCP/技能（manageMcp/loadSkill） | ✅ 已讨论（S-1~S-3） |
| 11 | 模式切换（switchMode） | ✅ 已讨论（G-1~G-3） |

> 全部 11 个工具族已讨论完毕。§13 实施清单已全部落地（2026-08-19 对照代码逐项核实，唯一暂缓 T-4 任意字节码发送，见 13.4）。

---

## 13. 实施清单（按优先级排期）

> 优先级：**P0** 缺陷/崩溃修复 → **P1** 正确性/体验优化 → **P2** 新能力增强。状态：⏳ 未开始 / 🔨 进行中 / ✅ 已完成。

### 13.1 P0 · 缺陷与崩溃修复（优先处理）

| # | 工具 | 事项 | 说明 | 状态 |
|---|---|---|---|---|
| V-4 | viewImage | 解码移至 IO 线程 | `withContext(Dispatchers.IO)` 包裹解码/缩放/编码，避免主线程卡顿/ANR | ✅ |
| F-2 | editFile | 大文件 LCS 保护 | 超大差异跳过 O(n·m) DP，退化为整体替换，防 OOM | ✅ |
| D-1 | todo | 快照事务化 | delete+upsert 包 Room `withTransaction`，防中间失败丢全部待办 | ✅ |
| D-3 | todo | 参数校验友好化 | parseStatus 改 ToolResult.Error + INVALID_STATUS 明确错误码 | ✅ |
| Q-3 | askUserQuestion | 回答超时机制 | `awaitAnswer` 3 分钟超时放行，防会话无限阻塞 | ✅ |
| Q-4 | askUserQuestion | 参数解析容错 | 字段级 runCatching，非法字段返回带序号/字段名的明确错误码 | ✅ |

### 13.2 P1 · 正确性与体验优化

| # | 工具 | 事项 | 说明 | 状态 |
|---|---|---|---|---|
| T-1 | Bash | 双模式返回 | 新增 `strict` 参数：默认 Success+exit_code；strict=true 非零退出→Error | ✅ |
| T-3 | terminal | 长输出限幅+分页 | read 默认 BoundedOutput 首尾截断；新增 start_line/max_lines 分页 | ✅ |
| E-1 | search | 反馈真实截断状态 | truncated 按实际截断置位；matches 用 `rg --count-matches` 独立统计 | ✅ |
| E-2 | search | 结果数量截断 | 新增 `max_matches` 参数（默认 200），超出提示缩小范围 | ✅ |
| E-4 | list | 真实权限位 | 读取真实 POSIX 权限位，不可读回退 `-` 而非伪造 | ✅ |
| W-1 | websearch | 结果结构化 | 解析为 [{title, url, snippet}] 列表，多段 content 合并 | ✅ |
| W-2 | websearch | 指数退避重试 | 网络抖动/超时自动重试 1-2 次 | ✅ |
| W-3 | webfetch | 移除换行 hack | 改 Jsoup 块级元素遍历拼行，避免误替换字面 `\n` | ✅ |
| V-1 | generateImage | 重试元数据 | 返回结果附加 attempts/failures + DB 任务行落盘 | ✅ |
| V-2 | viewImage | EXIF 方向修正 | ExifInterface 读 ORIENTATION，缩放前旋转 Bitmap | ✅ |
| C-4 | ensure_android_env | 去除反射 | LinuxContainerEngine 提供公开方法替换 getDeclaredField | ✅ |
| C-5 | ensure_android_env | 进度流式化 | 7 步脚本 STEP 行实时转 Progress 事件 | ✅ |
| C-6 | switch_container_arch | 进度流式化 | 首次切 x86_64 解压 rootfs/部署 QEMU 接流式进度 | ✅ |
| C-1 | check_environment | 结果缓存 | 30s TTL 缓存，避免 AI 重复探测 | ✅ |
| M-2 | memory | 自动摘要 | save 缺 description 时调 LLM 生成一句话摘要 | ✅ |
| D-2 | todo | 回传时间字段 | listTodos 回传 createdAt/updatedAt | ✅ |
| G-3 | switchMode | 切换频率限制 | 同会话 5 分钟最多 2 次，防 PLAN↔BUILD 抖动 | ✅ |

### 13.3 P2 · 新能力增强

| # | 工具 | 事项 | 说明 | 状态 |
|---|---|---|---|---|
| F-1 | editFile | 相似匹配建议 | NO_MATCH 时返回 Top-N 相近候选 | ✅ |
| F-3 | readFile/writeFile | hunk 落库 | 持久化 hunk/旧内容快照，支撑「撤销编辑」 | ✅ |
| F-4 | readFile | total_lines 自动决策 | 按文件大小自动决策；新增 `force_total_lines` 参数覆盖 | ✅ |
| T-2 | Bash | 只读命令自动重试 | 白名单只读命令（ls/pwd/git status）自动重试 | ✅ |
| T-5 | terminal | 继承工作区目录 | start 传入 workspaceRepository.currentPath() | ✅ |
| E-3 | list | 默认忽略噪音目录 | 默认隐藏 .git/build/.gradle/node_modules，`-a` 显示全部 | ✅ |
| M-1 | memory | 单条大小上限 | 单条 50KB 上限，超出明确报错引导拆分 | ✅ |
| M-3 | memory | tags 标签 | save 支持 tags 数组，list 按 tag 过滤 | ✅ |
| M-4 | memory | 访问统计排序 | 记录命中次数并降序展示/注入 | ✅ |
| V-3 | generateImage | 保存到工作区 | 新增 `output_path` 参数，保留私有目录副本 | ✅ |
| Q-1 | askUserQuestion | 默认选项高亮 | options 项新增 `default` 字段，UI 默认高亮 | ✅ |
| Q-2 | askUserQuestion | 支持 markdown | question/description 轻量 markdown 渲染 | ✅ |
| S-1 | loadSkill | 技能版本锁 | 技能实体与参数新增 `version` 字段 | ✅ |
| S-2 | manageMcp | 连接测试 | add 后 stdio/HTTP 全测连通性，失败即告知 | ✅ |
| S-3 | loadSkill | 运行时依赖预检查 | `requires_runtime` 元数据，加载前探测运行时就绪性 | ✅ |
| G-1 | switchMode | 切换历史记录 | 新增 {from, to, reason, timestamp} 历史 | ✅ |

### 13.4 暂缓/不采纳项（记录备查）

| # | 工具 | 事项 | 结论 |
|---|---|---|---|
| F-6 | editFile | 编辑冲突提示 | ⏳ 待讨论 |
| T-4 | terminal | 任意字节码发送 | ⏳ 暂缓 |
| C-2 | check_environment | 增量探测 | ⏳ 暂缓（收益有限） |
| C-3 | switch_container_arch | 切换后预热 | ⏳ 暂缓 |
| M-5 | memory | 向量语义搜索 | ⏳ 暂缓（复杂度不成比例） |
| W-4 | webfetch | URL 缓存 | ❌ 不采纳（保证内容最新） |
| W-5 | webfetch | css_selector 提取 | ⏳ 暂缓 |

---

## 14. 实施状态审计（对照当前代码 · 2026-08-19）

> 结论：**§13 实施清单 39 项全部 ✅ 已实现**（含 13.4 外的全部「采纳」项）。以下为逐项代码证据摘要；「暂缓/不采纳」项按原结论保留。

### P0 · 缺陷与崩溃修复
| # | 工具 | 证据 |
|---|---|---|
| V-4 | viewImage | [ImageTools.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/file/ImageTools.kt#L54-L85) 解码/缩放/编码整链包 `withContext(Dispatchers.IO)` |
| F-2 | editFile | [EditFileTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/editor/EditFileTool.kt#L31-L35) `MAX_LCS_CELLS`；[L261-L276](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/editor/EditFileTool.kt#L261-L276) `n*m` 超阈值跳过 DP 退化为整体替换 |
| D-1 | todo | [TodoTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/todo/TodoTool.kt#L167-L176) `withTransaction` 包删除+upsert |
| D-3 | todo | [TodoTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/todo/TodoTool.kt#L136-L144) 非法 status 返回 `INVALID_STATUS` |
| Q-3 | askUserQuestion | [AskUserQuestionManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/question/AskUserQuestionManager.kt#L27-L58) `ANSWER_TIMEOUT_MS = 3min` + `withTimeout` 放行 |
| Q-4 | askUserQuestion | [AskUserQuestionTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/question/AskUserQuestionTool.kt#L151-L168) 字段级 runCatching 容错 |

### P1 · 正确性与体验优化
| # | 工具 | 证据 |
|---|---|---|
| T-1 | Bash | [ExecuteCommandTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/ExecuteCommandTool.kt#L117-L122) `strict` 参数；非零退出→`ToolResult.Error` |
| T-3 | terminal | [BackgroundTerminalTools.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/BackgroundTerminalTools.kt#L155-L165) `start_line`/`max_lines` 分页 + 首尾截断 |
| E-1 | search | [SearchCodeTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/explorer/SearchCodeTool.kt#L67-L105) `rg --count-matches` 独立统计 + 真实 `truncated` |
| E-2 | search | [SearchCodeTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/explorer/SearchCodeTool.kt#L31-L35) `DEFAULT_MAX_MATCHES = 200` |
| E-4 | list | [ListFilesTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/explorer/ListFilesTool.kt#L302-L328) 读真实 POSIX 权限位，不可读回退 `-` |
| W-1 | websearch | [WebSearchTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/search/WebSearchTool.kt#L197-L252) 结构化 `{query, results:[{title,url,snippet}], ...}` |
| W-2 | websearch | [WebSearchTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/search/WebSearchTool.kt#L41-L103) `MAX_ATTEMPTS=3` 指数退避重试 |
| W-3 | webfetch | [WebFetchTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/search/WebFetchTool.kt#L167-L220) Jsoup 块级元素遍历拼行，移除换行 hack |
| V-1 | generateImage | [GenerateImageTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/image/GenerateImageTool.kt#L271-L280) 返回 `attempts`/`failures` + 任务落库 |
| V-2 | viewImage | [ImageTools.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/file/ImageTools.kt#L147-L232) `ExifInterface` 读 ORIENTATION 缩放前旋转 |
| C-4 | ensure_android_env | [EnsureAndroidEnvTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/EnsureAndroidEnvTool.kt#L174) 改用 `containerEngine.isBundleInstalled(id)`，无反射 |
| C-5 | ensure_android_env | [EnsureAndroidEnvTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/EnsureAndroidEnvTool.kt#L47) 实现 `StreamingAgentTool` 流式进度 |
| C-6 | switch_container_arch | [SwitchContainerArchTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/SwitchContainerArchTool.kt#L38) 实现 `StreamingAgentTool` 流式进度 |
| C-1 | check_environment | [CheckEnvironmentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/CheckEnvironmentTool.kt#L59-L72) `CACHE_TTL_MS = 30_000` |
| M-2 | memory | [MemoryTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/memory/MemoryTool.kt#L218-L236) 缺 description 时 `autoSummary()` |
| D-2 | todo | [TodoTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/todo/TodoTool.kt#L179-L199) 回传 `created_at`/`updated_at` |
| G-3 | switchMode | [SwitchModeTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/mode/SwitchModeTool.kt#L101-L140) 5 分钟窗口限 2 次 → `MODE_SWITCH_RATE_LIMITED` |

### P2 · 新能力增强
| # | 工具 | 证据 |
|---|---|---|
| F-1 | editFile | [EditFileTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/editor/EditFileTool.kt#L278-L337) `findSimilarCandidates()` Top-N 相似候选 |
| F-3 | readFile/writeFile | [EditFileTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/editor/EditFileTool.kt#L207-L220) + [FileTools.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/file/FileTools.kt#L268-L280) hunk/旧内容写 `FileEditHunkDao` |
| F-4 | readFile | [FileTools.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/file/FileTools.kt#L42-L79) `force_total_lines` + `AUTO_TOTAL_LINES_BYTES` 自动决策 |
| T-2 | Bash | [ExecuteCommandTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/ExecuteCommandTool.kt#L78) 只读命令白名单正则 + `@retry` 复用累积器 |
| T-5 | terminal | [BackgroundTerminalTools.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/BackgroundTerminalTools.kt#L274-L356) `startBackgroundCommand(..., workspaceRepository.currentPath())` |
| E-3 | list | [ListFilesTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/explorer/ListFilesTool.kt#L38-L40) `NOISE_DIRS`（.git/.gradle/build/node_modules） |
| M-1 | memory | [MemoryTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/memory/MemoryTool.kt#L180-L206) 单条字符数上限 |
| M-3 | memory | [MemoryTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/memory/MemoryTool.kt#L218-L236) `parseTags()` 支持 tags |
| M-4 | memory | [MemoryTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/memory/MemoryTool.kt#L153-L177) `accessCount` 记录并展示 |
| V-3 | generateImage | [GenerateImageTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/image/GenerateImageTool.kt#L258-L280) `output_path` + `copyToWorkspace()` |
| Q-1 | askUserQuestion | [AskUserQuestionTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/question/AskUserQuestionTool.kt#L58-L63) options `default` 字段 |
| Q-2 | askUserQuestion | [AskUserQuestionTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/question/AskUserQuestionTool.kt#L56) description 支持轻量 Markdown |
| S-1 | loadSkill | [LoadSkillTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/skill/LoadSkillTool.kt#L56-L92) `version` 参数 + 版本锁校验 |
| S-2 | manageMcp | [ManageMcpTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/mcp/ManageMcpTool.kt#L142-L172) add 后 `testConnection` stdio/HTTP 全测 |
| S-3 | loadSkill | [LoadSkillTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/skill/LoadSkillTool.kt#L119-L164) `requiresRuntime` + `command -v` 预探测 |
| G-1 | switchMode | [SwitchModeTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/agent/domain/tool/mode/SwitchModeTool.kt#L101-L119) `ModeSwitchHistoryEntity` 切换历史 |
