# 终端（Terminal）模块文档

> 模块路径：`app/src/main/java/com/core/deepcode/feature/terminal/`；维护规则：本模块代码变更必须同步更新本文档

## 1. 模块定位

终端模块提供 App 内的**终端会话能力**，覆盖三块功能：

1. **交互式终端**：基于 Termux 的 `TerminalSession`/`TerminalView`（PTY + 屏幕缓冲 + 渲染）构建多标签终端页，支持 Tab 栏、扩展按键行（Esc/Ctrl/Alt/方向键等）、搜索、复制/粘贴、字号档位、配色主题、标签 Pin/颜色标记等。
2. **后台命令执行**：以「后台标签」形式供 AI 挂起 `npm run dev` 等长任务（`startBackgroundCommand`），输出留在 emulator 缓冲，命令结束通过 `TabFinishedEvent` 通知 AI。
3. **容器/功能包环境管理**：联动 `LinuxContainerEngine` 完成容器初始化、7 个功能包（Bundle：Python/Node/rg/Git/Bash/网络/QEMU 转译器）的安装/卸载/状态管理，以及终端偏好设置（外观/键盘/行为/SSH 四组 15+ 项）。

**核心架构原则**：会话的所有权被提升到进程级 `@Singleton` 管理器，而非绑定导航栈的 ViewModel——离开终端页/切到聊天页，会话继续在后台运行；ViewModel 只是只读观察层。终端同时支持**本地模式**（fork PRoot 容器进程）与**远程 SSH 模式**（sshj 驱动远程 shell），两者通过统一接口 `TerminalSessionProvider` 由 `DelegatingTerminalSessionProvider` 按执行模式转发。

## 2. 目录结构与职责

| 路径 | 职责 |
| --- | --- |
| `data/bundle/TerminalBundles.kt` | Bundle 稳定标识 `TerminalBundleId`、安装状态机 `BundleInstallState`、`TerminalBundle` 定义与 7 个内置 Bundle 清单（含 postInstallHook 脚本）；AI 推荐组合常量 |
| `data/repository/TerminalBundleRepository.kt` | Bundle 安装状态仓库：StateFlow 暴露、`.bundles/<stableKey>-v<N>.done` 标记文件落盘/恢复、存量用户 `.provisioned` 迁移、自定义 apk 包快照 |
| `data/repository/TerminalSettingsRepository.kt` | 终端偏好 DataStore（`terminal_prefs`）：字号/主题/Tab 栏、键盘交互、行为、SSH 心跳等读写；`TerminalFontSizes` 字号档位 |
| `domain/TerminalSessionProvider.kt` | 终端会话后端抽象接口（AI terminal 工具依赖它而非具体实现）：`startBackgroundCommand`/`sendInput`/`writeToTab`/`writeBytesToTab`/`getTabOutput`/`listTabs`/`closeTab`/`tabFinishedEvents` |
| `domain/DelegatingTerminalSessionProvider.kt` | `TerminalSessionProvider` 委托层：按 `ExecutionModeHolder.currentMode()` 把调用转发到本地或远程实现（运行时决定，与注入时机解耦） |
| `domain/TerminalSessionManager.kt` | **本地会话管理器（@Singleton）**：进程内唯一 `TerminalSession` 池；建交互/后台标签、按 id 读写、关闭/重连、退出兜底监控、Tab 互斥锁 |
| `domain/RemoteTerminalSessionManager.kt` | **远程 SSH 会话管理器（@Singleton）**：用 sshj shell channel + `SshShellBackend` 驱动 Termux 会话；SSH 断线自动重连重建交互 tab |
| `domain/SshShellBackend.kt` | `SessionBackend` 的 sshj 适配：shell 输出 → emulator、输入 → shell stdin、resize → PTY 尺寸 |
| `domain/TerminalKeepaliveService.kt` | 前台服务保活：会话计数 + 常驻开关，前台通知文案联动；START_STICKY 兜底避免 Android 12+ FGS 崩溃 |
| `domain/TerminalTab.kt` | `TerminalTab`（会话+视图+元数据）、`RunState` 状态机、`TabFinishedEvent` 后台命令完成事件、`TabInfo` AI 摘要、颜色标记枚举 |
| `presentation/TerminalViewModel.kt` | 终端页薄观察层：转发 UI 操作到本地/远程管理器，暴露 tabs/activeTabId/revision/字号/Banner/Bundle 状态等 StateFlow；**不持有、不销毁任何会话** |
| `presentation/TerminalSettingsViewModel.kt` | 终端设置页 VM：四组偏好开关、容器环境大卡片（初始化/重置/换源）、Bundle 卡片与自定义包动作、rootfs 占用统计 |
| `presentation/component/TerminalScreen.kt` | 终端页主界面：Tab 栏、Banner、TerminalSurface、扩展按键行、搜索浮层、各弹窗编排 |
| `presentation/component/TerminalComponents.kt` | TabBar/TabChip、`TerminalSurface`（TerminalView 包装+调色板应用+对比度断言）、`ExtraKeysRow` 扩展按键行、重连/操作/长按/确认/重命名菜单与搜索浮层 |
| `presentation/component/TerminalClients.kt` | `TerminalKeyModifiers` 虚拟修饰键、`AppTerminalSessionClient`（会话回调→视图刷新/剪贴板/TextInputTracker）、`AppTerminalViewClient`（输入/手势/缩放回调） |
| `presentation/component/TextInputTracker.kt` | 字符级输入追踪（B 方案）：记录提示符长度与用户输入字节，实现「仅用户输入区可剪切」 |
| `presentation/component/TerminalBundleManagerScreen.kt` | 功能包管理子页面：容器状态卡 + 分 Tab 的 Bundle 卡片/自定义包列表 |
| `presentation/component/BundleInstallCard.kt` / `BundleLogDialog.kt` | 单个 Bundle 安装卡片（状态动画）与安装日志弹窗 |
| `presentation/component/TerminalSettingsScreen.kt` / `TerminalSettingCards.kt` | 终端设置页 UI 与四组设置卡片 |
| `presentation/component/TerminalPalette.kt` / `TerminalUISpec.kt` | 终端配色三档（跟随程序/黑底/白底）与终端外壳 UI 规格（布局常量/语义色/按钮规格） |
| `presentation/component/UiBundleAdapter.kt` | `TerminalBundle` → UI 轻量模型 `UiBundle`（含 icon 解析） |

## 3. 核心架构与主流程

### 3.1 会话所有权模型（本地）

`TerminalSessionManager` 是进程内唯一的会话池（`@Singleton`）。为何不放 ViewModel：ViewModel 绑定导航路由、出栈即 `onCleared`，会连带杀掉 proot 会话；上移到 Singleton 后，只要进程活着会话就一直在跑。ViewModel 只做只读观察与 UI 转发。

每个标签有稳定且 AI 友好的 id（`term-N`），AI 可凭 id 挂后台命令、发输入、发控制字符（如 Ctrl-C=0x03）、读屏幕缓冲、关闭标签。

### 3.2 本地终端启动主流程（`createInteractiveTab`）

1. `ensureContainer()` + `containerEngine.isContainerInstalled()` 判断容器是否就绪。
2. 就绪：`buildSession` 用 `containerEngine.buildProotInvocation` 构造 proot 命令 → `TerminalSession`（`TRANSCRIPT_ROWS=2000` 屏幕缓冲），`updateSize(80,24)` 立即 fork proot 并起 I/O 线程（后台命令无需先打开终端页即可运行）。
3. 未就绪：fallback 到 Android 原生 `/system/bin/sh`（`buildNativeFallbackSession`），打印 MOTD 提示用户初始化容器，保证首次进入不白屏。
4. 会话结束回调按 `session === tab.session` 回查标签更新 `RunState`，后台且 `notify` 时发 `TabFinishedEvent`。

### 3.3 后台命令主流程（`startBackgroundCommand`）

1. 生成 `term-N` id，构造 `cd <workdir>; <command>; echo "[command exited: $ec]"` 的 shell 命令；`notify` 时命令后 `exit $ec`，否则 `exec <shell>` 保活为可继续输入的会话。
2. 容器未装时命令不真执行，直接 echo 错误并提示初始化。
3. `notify=true` 时启动 `monitorBackgroundExit` 兜底监控：proot 宿主进程可能不随 bash exit 释放 PTY，导致 `onFinished` 永不回调；以命令打印的 `[command exited: N]` 标记为可靠结束信号，缓冲 1.5s 后仍 Running 则强制收尾并 emit 事件。
4. 启动 `TerminalKeepaliveService`（`ACTION_START_SESSION`）。

### 3.4 关闭标签（`closeTab`）与竞态防护

连续关闭 5~6 个 tab 曾触发闪退，因此引入 `tabOpLock`（ReentrantLock）串行化「移除 + 解绑 View + finish 会话」，顺序固定为：① 先从列表移除并切走 activeTabId → ② 解绑 `TerminalView`（`attachSession(null)` 置空 emulator）→ ③ 下一帧主线程空闲再 `finishIfRunning()`。`ensureAtLeastOneTab` 用合并式 pendingJob（150ms 缓冲 + 锁内双重判空）保证关闭最后一个 tab 后恰好新建一个兜底 tab。

### 3.5 远程 SSH 模式

- `RemoteTerminalSessionManager` 与本地版共用同一套 tab/事件/UI 接口，仅 backend 不同：`RemoteSshConnection.startShellSession()` + `allocateDefaultPTY()` 在 IO 线程建立，回主线程构造 `TerminalSession`（Handler 需主线程 Looper）并接 `SshShellBackend`。
- `SshShellBackend` 把 sshj shell 适配为 `SessionBackend`：resize/close 走网络 I/O，切独立线程执行。
- 断线重连：构造期注册 `RemoteSshConnection.registerOnReconnectedListener`，重连成功后只重建 **Running 的交互 tab**，故意忽略后台命令 tab（命令已死，自动重启可能重复副作用）与 Finished tab。
- 入口守卫 `ensureRemote()`：非 REMOTE_SSH 模式或未连接时抛 `IllegalStateException`。

### 3.6 保活服务（`TerminalKeepaliveService`）

后台存在会话或用户开启常驻时进入前台并展示通知。`onStartCommand` 按 action 维护会话计数（START/STOP）与常驻开关（ENABLE/DISABLE_PERSISTENT）；unknown/null intent（START_STICKY 重建）一律进 `ensureForeground()` 安全兜底，避免 Android 12+ `ForegroundServiceDidNotStartInTimeException` 杀进程。

### 3.7 容器与功能包管理

- `TerminalBundles` 定义 7 个 Bundle（Python/Node/rg/Git/Bash/网络工具/QEMU x86 转译器），`version` 自增触发存量重装；`postInstallHook` 含 pip 三链路兜底、git-credential-store、bash PS1 前缀等一次性 shell 脚本。
- **Bundle 架构无关性**：Bundle 只声明 apk 包名，不涉及架构——apk 在容器内按容器所在架构（arm64 / x86_64 rootfs）自动解析安装，无需为 x86_64 环境单独维护包定义（见 [emulator-support-design](../plan-docs/emulator-support-design.md)）。
- `TerminalBundleRepository` 用 `<rootfs>/.bundles/<stableKey>-v<N>.done` 标记文件持久化安装状态，冷启动从磁盘恢复；检测旧 `.provisioned` 标记自动迁移为全量已安装；实际 `apk add/del` 由 `LinuxContainerEngine` 执行，本仓库只负责状态机与落盘。
- `TerminalSettingsViewModel` 通过 `containerEngine.refreshBundleStatesFromApk()` 用真实 apk 世界校准 UI 状态（聊天页 AI 直接 apk 装包时保持联动）。

## 4. 对外接口与集成点

| 接口 / 类 | 消费者 | 说明 |
| --- | --- | --- |
| `TerminalSessionProvider`（实现：`DelegatingTerminalSessionProvider`） | `feature/agent/domain/tool/container/BackgroundTerminalTools.kt`（AI 的 terminal 工具）、`di/AgentModule.kt` | AI 发起后台命令、按 id 读写/读取/关闭标签的统一入口 |
| `TerminalSessionManager` / `RemoteTerminalSessionManager` | `TerminalViewModel`、`TerminalSessionProvider` | 会话池与标签管理 |
| `LinuxContainerEngine`（依赖注入） | `TerminalSessionManager`、两个 ViewModel | 容器安装/启动、Bundle 安装、init 进度 |
| `WorkspaceRepository.currentPath()`（依赖注入） | `TerminalSessionManager` | 本地工作区目录、workdir→`~/workspace` 换算 |
| `ExecutionModeHolder`（依赖注入） | 委托层 / 两个管理器 | 本地 vs 远程 SSH 模式路由 |
| `TerminalKeepaliveService` | `TerminalSessionManager`、`MainActivity`（设置页恢复） | 前台保活；action 常量供外部 startService |
| `TextInputTracker` | 会话/视图 client | 字符级输入追踪（剪切 B 方案） |

## 5. 关键设计点与约束

- **会话常驻后台**：会话归 Singleton 管理器所有，`TerminalViewModel.onCleared` 刻意不销毁会话。
- **主线程约束**：所有可变状态读写与 `buildSession`/`updateSize` 都在主线程（Termux Handler 绑定主 Looper）；SSH channel 建立等网络 I/O 切 `Dispatchers.IO`。
- **closeTab 三阶段解绑**：先移除列表、再解绑 View、最后延后 finish，配合 `tabOpLock` 防止 Termux native OOB/NPE。
- **退出兜底监控**：以 `[command exited: N]` 为可靠结束信号，弥补 proot 不释放 PTY 导致 onFinished 不回调的问题。
- **容器未装 fallback**：原生 `/system/bin/sh` 保证终端页永远可用，MOTD 引导初始化。
- **远程断线语义**：重连只重建交互 tab，后台命令 tab 与 Finished tab 不做自动重启（避免重复副作用）。
- **前台服务安全兜底**：unknown/null intent 一律 `ensureForeground()`，`startForeground` 异常只记录不崩溃。
- **配色对比度**：调色板应用带 WCAG 断言（fg/bg ≥ 4.5:1，cursor/bg ≥ 7.0），防止改色回退到纯白不可见。

## 6. 维护与扩展指引

- **新增一个功能包**：在 `TerminalBundles` 加 `TerminalBundleId` + `TerminalBundle` 定义（改 packages/hook 同步 `+1 version` 触发重装），并在 `UiBundleAdapter.iconVector` 与 `containerInitMessage` 补充图标/文案；无需改 repository。
- **新增终端偏好**：在 `TerminalSettingsRepository` 加 key + Flow + save 方法，再在 `TerminalSettingsViewModel`/`TerminalSettingsScreen` 暴露。
- **新增 AI 终端能力**：改 `TerminalSessionProvider` 接口 → 同步实现 `TerminalSessionManager` 与 `RemoteTerminalSessionManager` 两处（勿只改一处），委托层自动转发。
- **会话并发安全**：涉及 tab 增删的代码必须走 `tabOpLock`；`ensureAtLeastOneTab` 的「空→新建」逻辑不要移除缓冲窗口与双重判空。
- **修改 Bundle 版本**：必须同步 +1 version，否则已装用户不会触发重装。
- **涉及本模块的行为变更**：更新 `docs/modules/terminal.md` 的对应小节。

## 7. 版本演进记录

> 本模块开发维度演进；用户可见变更见仓库根 [CHANGELOG.md](../../CHANGELOG.md)。

- **v0.2.0（2026-08-25）**：修复终端启动字段初始化顺序导致的 NPE 闪退。
- **v0.1.0（早期）**：终端与会话核心落地（Termux 组件 + PRoot 容器、后台常驻、多标签、7 个内置功能包、容器未装 `/system/bin/sh` fallback、远程 SSH shell channel）。
