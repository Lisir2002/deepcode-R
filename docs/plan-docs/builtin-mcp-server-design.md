# 内置 MCP 服务器 · 设计文档 v1.0

> 状态：✅ 已评审（2026-08-19，决策已回填，进入实施期；M0 为首个里程碑）
> 定位：让 DeepCore-Code 从「MCP 客户端」同时成为「MCP 服务器」，把设备能力（容器/终端/文件/git/搜索/AI Agent 工具）开放给外部 MCP 客户端（Claude Desktop / Trae / Cursor / 任意脚本）
> 对应代码库：[deepcode-R](/workspace/deepcode-R)
> 相关入口：`AGENTS.md` / `docs/modules/`（模块文档）

---

## 1. 背景与目标

当前 DeepCore-Code 是 **MCP 客户端**（`feature/agent/domain/mcp/`），负责「连别人」——连接远程 HTTP / 本地 stdio server 扩展自身工具。本设计是**方向反转**：让 App 自己作为 **MCP 服务器**，对外提供一套「真实 Linux 编码后端」。

**核心场景（用户决策）**：手机当开发后端——DeepCore-Code 已内置容器 + Linux 终端 + git + AI Agent 工具，把这套能力以 MCP server 暴露，PC 上的 Claude Desktop / Trae / Cursor 连上来即可调用手机的终端、文件、git，相当于给外部 AI 一个随身 Linux 运行环境。

**能力范围（用户决策）**：暴露**全部 AgentTool**（文件读写、终端、git、搜索、浏览器、T2I、skills 等），带逐工具权限开关，复用现有审批体系。**实施采用渐进式（评审决策）**：M0 先只读子集验证链路，再逐步开放终端/写文件（配合远程审批）。

**目标**：
- 分层可用：先跑通「协议反转 + 工具映射」最小链路，再逐步扩展工具面与体验。
- 安全可控：默认关闭、token 鉴权、远程调用强制审批（可配置）。
- 复用优先：协议模型、工具层、权限体系、服务端范式全部复用现有实现，不重复造轮子。

---

## 2. 现状盘点（代码证据）

内置 MCP 服务器的四个地基**全部已存在**，本设计的核心是「编排而非新建」：

| # | 地基 | 代码位置 | 说明 |
|---|---|---|---|
| D1 | **MCP 协议模型** | [McpClient.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/mcp/McpClient.kt) / `JsonRpcRequest` / `JsonRpcResponse` | 客户端已实现握手、tools/list、tools/call、JSON-RPC ID 匹配；server 侧复用同一套模型与协议版本（`2025-06-18`） |
| D2 | **统一工具层** | [AgentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/tool/AgentTool.kt#L152-L223) / [ToolRegistry.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/tool/ToolRegistry.kt) | `AgentTool` 已有 `toToolDefinition()`（name/description/parameters JSON Schema）；`ToolRegistry.getAvailableTools()/getTool(name)` 可枚举与按名调用；`execute(args)` / `executeWithContext(args, context)` 即为 tools/call 执行入口 |
| D3 | **同步挂起式权限审批** | [ToolPermissionManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/tool/ToolPermissionManager.kt#L29-L46) | `awaitApproval(sessionId, PendingToolPermission)` 挂起等 UI 弹窗 → `resolve(id, choice)`；MCP `tools/call` 是异步请求，天然兼容「挂起等审批」 |
| D4 | **Android 端内置服务端范式** | [FtpServerManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/workspace/domain/remote/ftp/FtpServerManager.kt) | 内置 FTP 服务端已跑通：Singleton + DataStore 配置 + 开关/端口/用户名密码/匿名 + 自启 + `getLocalIpAddress()` + URL 展示。MCP server 的「服务管理」直接对标此范式 |

**关键利好**：
- 客户端已有的 [StreamableHttpTransport.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/mcp/StreamableHttpTransport.kt) 证明「Streamable HTTP 单端点 POST JSON-RPC + SSE」协议形态在项目内已吃透，server 侧照此规范实现即可被主流客户端连接。
- 工具权限体系（`permissionPolicy`：AUTO_APPROVE / ASK / NEVER + `capabilities`：12 个 ToolCapability）可直接映射为「远程调用时的审批策略」。

---

## 3. 核心认知：把「内置 MCP 服务器」拆成四个正交问题

1. **听不听得到**（传输与监听）→ Android 端起 HTTP server，暴露局域网可达端口；模拟器走 adb reverse。→ 已有 D4 范式，选型见 §5.1。
2. **协议对不对得上**（MCP server 会话）→ 实现 server 侧 initialize / tools/list / tools/call / ping。→ 复用 D1 模型。
3. **工具执行与权限**（能力开放）→ AgentTool 映射 + 远程审批。→ 复用 D2 / D3。
4. **安全与保活**（敢不敢开 / 会不会死）→ token 鉴权 + 默认关闭 + FGS 保活。→ 对标 D4 + FGS。

---

## 4. 目标架构

```
┌──────────────────────────── DeepCore-Code（Android） ────────────────────────────┐
│                                                                              │
│  ┌──────────────────────────────┐      ┌──────────────────────────────────┐  │
│  │  McpServerManager（对标 D4）    │      │  AgentTool 体系（D2，已存在）        │  │
│  │  开关/端口/token/自启/URL 展示   │      │  ToolRegistry.getAvailableTools() │  │
│  └──────────────┬───────────────┘      └───────────────┬──────────────────┘  │
│                 │ 启动/停止                              │ 枚举/执行             │
│                 ▼                                      ▼                      │
│  ┌──────────────────────────────┐      ┌──────────────────────────────────┐  │
│  │  McpHttpServer（新增）          │      │  AgentToolMcpAdapter（新增）       │  │
│  │  HTTP 监听 + 路由 + token 鉴权  │─────▶│  AgentTool ↔ MCP tool 双向映射     │  │
│  └──────────────┬───────────────┘      └───────────────┬──────────────────┘  │
│                 │ JSON-RPC                              │                    │
│                 ▼                                      ▼                    │
│  ┌──────────────────────────────┐      ┌──────────────────────────────────┐  │
│  │  McpServerSession（新增）       │      │  ToolPermissionManager（D3，复用）  │  │
│  │  initialize/list/call/ping    │─────▶│  awaitApproval → UI 审批卡         │  │
│  └──────────────────────────────┘      └──────────────────────────────────┘  │
│                                                                              │
│  ▲ 局域网连接（真机同 WiFi / 模拟器 adb reverse）                               │
│  │ URL: http://<ip>:<port>/mcp   Header: Authorization: Bearer <token>      │
│  └── 外部 MCP 客户端（Claude Desktop / Trae / Cursor / 脚本）                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **一处鉴权、处处复用**：token 校验在 McpHttpServer 入口统一做；工具权限在 AgentToolMcpAdapter 统一做（复用 D3）。
- **与现有 mcp/ 客户端的关系**：`feature/agent/domain/mcp/` 下新增 `server/` 子包，与客户端共用 `JsonRpcRequest`/`JsonRpcResponse` 等协议模型；两端互不依赖。

---

## 5. 详细设计

### 5.1 传输与监听（McpHttpServer）

**端点形态**：按 MCP **Streamable HTTP** 规范实现单端点（如 `/mcp`）：
- `POST /mcp`：接收 JSON-RPC，回 `application/json`（普通响应）或 `text/event-stream`（需要流式时，按 SSE 写 `event: message` 行）；首个响应带 `Mcp-Session-Id` 头（复用客户端已实现的会话语义）。
- `GET /mcp`：SSE 长连接（客户端拉取服务端推送，首期可仅返回空流保活）。
- `DELETE /mcp`：结束会话（可选，M0 可不做）。

**HTTP server 选型**（**评审决策：Ktor CIO**，NanoHTTPD / ServerSocket 自写为备选，不采用）：
| 方案 | 依赖增量 | 评估 |
|---|---|---|
| a. **Ktor CIO server**（✅ 已定） | 新增 `ktor-server-core` + `ktor-server-cio` | 与项目协程/序列化栈同源；Streamable HTTP 的 SSE 原生支持；路由/中间件成熟，鉴权好加 |
| b. **NanoHTTPD** | 新增单库，轻量 | Android 常用、零协程心智；SSE 与 JSON 解析要手写，中规中矩 |
| c. **java.net.ServerSocket 自写** | 零依赖 | 需手写 HTTP 解析（Content-Length/POST body/SSE 帧），易出错，仅作兜底 |

**监听与可达性**：
- 绑定 `0.0.0.0:<port>`（端口默认 `3000`，可配置，避开常见端口冲突）。
- **真机**：同 WiFi 下用 `getLocalIpAddress()`（FtpServerManager 已有）展示 `http://192.168.x.x:port/mcp`。
- **模拟器/虚拟机**：`adb reverse tcp:<port> tcp:<port>`，PC 端访问 `http://localhost:<port>/mcp`（与既有双 ABI/虚拟环境支持衔接）。
- 后台监听需**前台服务保活**（复用 `TerminalKeepaliveService` 的 FGS 模式：`dataSync` 类型 + 常驻通知），防止进程被杀导致端口失联。

### 5.2 协议会话层（McpServerSession）

对齐客户端已实现的握手语义（[McpClient.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/mcp/McpClient.kt#L33-L42)），server 侧实现：

| 方法 | 行为 |
|---|---|
| `initialize` | 校验 `protocolVersion`；回 `serverInfo`（name=deepcode-mcp, version）+ `capabilities`（声明 `tools` 能力） |
| `notifications/initialized` | 无操作（客户端通知已就绪） |
| `tools/list` | 经 AgentToolMcpAdapter 拉取 `ToolRegistry.getAvailableTools()` → 转 MCP tool 描述 |
| `tools/call` | 经 AgentToolMcpAdapter 执行 + 权限审批，回 `content` 块（text 拼接，对齐客户端 `flattenContent` 语义） |
| `ping` | 回 `{}` 保活 |

- **JSON-RPC 路由**：每个请求分配 id，异步处理（挂起审批不阻塞其它请求），响应按 id 回写。一个 server 会话内可并行多个 tools/call。
- **会话状态**：`Mcp-Session-Id` 与 token 绑定，管理会话级上下文（首期只做连接管理，不持久化）。

### 5.3 工具映射（AgentToolMcpAdapter）

**tools/list 映射**：`AgentTool` → MCP tool 描述，直接复用 `toToolDefinition()`（[AgentTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/tool/AgentTool.kt#L208-L222)）：
```
name: <tool.name>
description: <tool.description>
inputSchema: { type: "object", properties: <从 parameters 生成的 JSON Schema>, required: [...] }
```

**tools/call 映射**：
1. `ToolRegistry.getTool(name)`；不存在 → 回错误（`code: TOOL_NOT_FOUND`）。
2. **权限审批**（复用 D3）：
   - 读取工具 `permissionPolicy`：`AUTO_APPROVE` → 直接放行；`NEVER` → 拒绝；`ASK` → `awaitApproval` 挂起。
   - **远程专用加强**：服务端加「远程调用强制审批」总开关（默认开）——开启时即使工具 `AUTO_APPROVE` 也进入审批，用户在 App 弹窗确认（弹窗注明来源「外部 MCP 调用 · <tool>」）。
   - 审批结果 `PermissionChoice.REJECT` → 回 `isError: true`；`ONCE/ALWAYS` → 继续执行。
3. **执行**：
   - 无上下文工具：`execute(args)`。
   - `AbstractContextualTool`（[AbstractContextualTool.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/agent/domain/tool/AbstractContextualTool.kt)）需 `AgentContext`：首期**不暴露**这类工具（tools/list 过滤），M3 再评估合成最小 context。
   - `StreamingAgentTool`：进度事件在 SSE 通道回传（M2 增强，首期退化为执行完一次性返回）。
4. **结果封装**：`ToolResult.Success/Error/Partial` → MCP `content` 块；Error 置 `isError: true`。

**权限黑名单**：不纳入 MCP 暴露的工具（示例，评审时确认）：
- 强依赖 UI/本机会话的：`askUser`、`switchMode`（会话状态）、浏览器中依赖用户登录态的操作（`browser` 保留只读子集）。
- 依赖容器内私有 socket 的：RcbBridge 相关能力。
- 具体清单在实施时按 `capabilities` + 工具依赖逐项定夺，设计原则：**宁缺毋滥，读操作优先**。

**暴露策略（评审决策：渐进式）**：M0 仅暴露**只读子集**（file.read / search / git status 等），跑通「连接 → 列工具 → 调用」链路；M1 起逐步开放写文件与终端（受远程强制审批总开关约束）。不采用「一步到位全量」。

### 5.4 安全模型（McpServerSecurity）

| 维度 | 设计 | 依据 |
|---|---|---|
| **默认关闭** | 服务默认关闭，用户显式开启才监听 | 对标 D4 FTP 默认关闭 |
| **token 鉴权** | 开启时生成随机 token（可重生成）；每个请求校验 `Authorization: Bearer <token>`，失败回 401 | MCP 客户端 `headers` 已支持自定义头（[mcp-and-skills.md](file:///workspace/deepcode-R/app/src/main/assets/docs/mcp-and-skills.md)），Claude Desktop/Trae 均支持 customHeaders |
| **远程审批总开关** | 默认开：远程调用一律弹审批（即使工具 AUTO_APPROVE） | 暴露终端=远程执行，必须人工兜底 |
| **局域网提示** | UI 明示「请连接可信 WiFi」；不做公网穿透（首期不暴露公网） | 缩小攻击面 |
| **日志** | 每次远程调用写审计日志（工具名、参数、来源 IP、审批结果） | 复用 `RemoteAuditLogRepository` 思路 |

### 5.5 服务管理（McpServerManager，对标 FtpServerManager）

与 [FtpServerManager.kt](file:///workspace/deepcode-R/app/src/main/java/com/core/deepcode/feature/workspace/domain/remote/ftp/FtpServerManager.kt) 同构：
- `@Singleton`，DataStore 持久化配置：`enabled / port / token / requireApproval / autoStart`。
- 状态流：`isRunning / serverUrl / errorMessage`；UI 展示「运行中: http://<ip>:<port>/mcp」+ token 查看/复制/重生成。
- `startServer()/stopServer()`：绑定 Ktor server + 启动 FGS；`autoStart` 时 App 启动自动拉起。
- 入口放在「设置」新增「MCP 服务端」区块（或并入现有 MCP 服务器页，加 Tab 区分「客户端 / 服务端」）。

### 5.6 目录结构

```
feature/agent/domain/mcp/
├── client/            # （现有）McpManager / McpClient / 传输层
└── server/            # （新增，本次设计）
    ├── McpServerManager.kt      # 服务管理（对标 FtpServerManager）
    ├── McpHttpServer.kt         # Ktor 监听 + 路由 + token 鉴权中间件
    ├── McpServerSession.kt      # 协议会话（initialize/list/call/ping）
    ├── McpServerSecurity.kt     # token 生成/校验、远程审批总开关
    └── AgentToolMcpAdapter.kt   # AgentTool ↔ MCP tool 映射 + 权限审批编排
```

---

## 6. 里程碑与验收

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0** | 最小链路：Ktor server 监听 + token 鉴权 + `initialize/tools/list/tools/call`，暴露**只读子集**（file.read / search / git status 等） | PC 端 Claude Desktop 配置 `http://<ip>:<port>/mcp` + Bearer token 后能连上、列工具、成功执行一个只读工具 |
| **M1** | 全量无上下文 AgentTool + 远程审批集成 + 审计日志 | 外部客户端能调终端/写文件等全量工具；每次调用弹审批、拒绝正确回错误 |
| **M2** | StreamingAgentTool SSE 进度 + FGS 保活 + 自动启动 + 模拟器 adb reverse 说明 | 长命令进度在客户端流式可见；锁屏/切后台不丢服务 |
| **M3** | ContextualTool 最小 context 合成、浏览器能力子集、Skills 联动（可选） | 按评审优先级推进 |

建议演进顺序 **M0 → M1 → M2 → M3**，每步独立交付、独立回退。

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 暴露终端 = 远程代码执行 | 默认关闭 + token + 远程强制审批总开关 + 局域网限制 + 审计日志 |
| Android 后台监听被系统限制 | FGS 保活（复用 `TerminalKeepaliveService` 模式）+ 常驻通知 |
| 部分工具强依赖 UI/本机会话 | tools/list 过滤（黑名单），M3 再评估合成 context |
| Ktor 依赖体积/启动开销 | 用 CIO 最小集；若体积敏感回退 NanoHTTPD（决策记录留存） |
| 外部客户端兼容性（SSE 语义差异） | 按 Streamable HTTP 规范实现，以客户端已实现的行为为参照联调 |

---

## 8. 决策记录

| 决策 | 结论 | 理由 |
|---|---|---|
| 方向 | 从「MCP 客户端」扩展为「客户端 + 服务器」双角色 | 复用协议/工具/权限/服务端四大地基，增量低 |
| 定位 | 手机当开发后端，暴露给 PC 外部 AI 客户端 | 用户决策 |
| 暴露范围 | 全部 AgentTool（含终端/文件写），带权限开关；**实施渐进式** | 用户决策；安全由审批体系兜底，先只读验证再放开 |
| 传输 | Streamable HTTP（单端点 + SSE），首期不做 stdio 形态 | Android 进程模型下 stdio 无消费方；HTTP 与主流客户端兼容 |
| server 选型 | **Ktor CIO**（已定） | 与协程栈同源、SSE 原生；NanoHTTPD / 自写为备选不采用 |
| 权限 | 复用 ToolPermissionManager + 远程强制审批总开关 | 一行审批链路，天然适配异步 tools/call |
| 本文件 | ✅ 已评审（决策回填完成） | 进入实施期，M0 为首个里程碑 |

---

## 9. 实施清单（已评审，按 M0→M3 推进）

- [x] 确认黑名单工具清单方向（读操作优先、UI/本机会话依赖不暴露）
- [x] 确认 HTTP server 选型（**Ktor CIO**）
- [ ] M0：McpServerManager + McpHttpServer（Ktor）+ McpServerSession + 只读工具子集 + token 鉴权
- [ ] M1：全量无上下文工具 + 审批集成 + 审计日志 + 写文件/终端开放
- [ ] M2：SSE 进度 + FGS 保活 + 自启 + 模拟器 adb reverse 说明
- [ ] M3：ContextualTool 最小 context 合成、浏览器能力子集、Skills 联动（可选）
- [ ] 同步模块文档（`docs/modules/`）+ AGENTS.md 工具/目录更新（实施后）
