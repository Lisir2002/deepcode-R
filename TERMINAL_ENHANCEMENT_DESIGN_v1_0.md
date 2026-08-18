# 终端体验强化设计文档（Termux 优点对齐）v1.0

> 目标：**不引入"原生后端切换"，保持 PRoot 容器为主线**，通过吸收 Termux 在
> 「平台集成 / 后台保活 / 容器内 helper 命令 / 终端交互」上的成熟做法，强化我司内置终端。
>
> 原则：**从实际已有代码结构出发，能复用就复用，能"扩"不"新建"**。
> 本文档六主题均已在评审中逐条定案，每个主题标注「现状 — 缺口 — 改动点 — 优先级」。

---

## 0. 现状盘点（先对齐"已有 vs 缺口"）

| 能力 | 现状 | 缺口 |
|---|---|---|
| 终端仿真引擎 | 已用 Termux 的 `terminal-emulator` / `terminal-view` 两库 | 无（内核层已对齐） |
| extra keys / 修饰键 | 已有全尺寸/简洁档 + Ctrl/Alt 虚拟键（[TerminalClients.kt](app/src/main/java/com/R/codecore/feature/terminal/presentation/component/TerminalClients.kt)） | 缺自定义布局 |
| 双指缩放字号+持久化 | 已实现（`AppTerminalViewClient.onScale` + `scaleGesturePersistsFlow`） | 无 |
| 剪贴板互通 | 已有「系统→终端」粘贴 + 选区复制到系统（`onPasteTextFromClipboard` / `onCopyTextToClipboard`） | 缺「容器内反向读写剪贴板」 |
| 后台保活 | 已有 `TerminalKeepaliveService`（`dataSync` FGS + `sessionCount` 引用计数 + 常驻开关） | 缺 agent 任务触发、唤醒锁、停止按钮 |
| 存储互通 | 无 `/sdcard` 绑定；`ContainerProfile.extraBindings` 已预留字段 | 缺运行时开关 + 系统权限 + 护栏 |
| 容器内 helper | 无 | 全新 |
| 开机自启 / intent 触发 | 无 `BootReceiver`；有 `enqueueAgentRequest` 触发入口（`isAutoTrigger`） | 全新触发层 |
| rootfs 备份恢复 | 有 Backup module（备份 app 数据/密钥），**不含容器 rootfs** | 新增容器快照维度 |

---

## 1. 存储互通（Topic 1）

### 定案
- 范围/权限：**整卡读写**，容器内挂 `~/storage/shared`。
- 系统权限：Android 11+ 走 **`MANAGE_EXTERNAL_STORAGE`（所有文件访问）**引导；Android 10- 用 `READ/WRITE_EXTERNAL_STORAGE`。
- 默认：**关**，设置里手动开。
- 安全护栏：**白名单模式** —— agent 写任意顶层目录默认需确认，仅少数安全目录（`Download`、专门的共享目录）免确认；**仅 agent 工具/命令层拦截**，用户终端不拦。

### 现状
- `ContainerProfile.extraBindings: List<String>` 已存在，内置 `BUILTIN_ALPINE` 为空（[ContainerProfile.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/ContainerProfile.kt)）。
- proot 基础 argv（`buildBaseProotArgv`）目前只绑 `/dev /proc /sys /system`，无设备存储。

### 改动点
1. **运行时开关**：新增「共享设备存储」预选项（建议塞进 `TerminalSettingsRepository` 或新建 `StorageShareSettingsRepository`）。因 proot `-b` 是每次 fork 拼接的，开关在 `buildBaseProotArgv` 处按该开关动态追加 `-b /storage/emulated/0:~/storage/shared`（内置 profile 不支持库改静态 `extraBindings`，故走运行时判定，而非写死 profile）。
2. **系统权限**：开启时检测 `Environment.isExternalStorageManager()`，未授权则跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`；未授权时开关置灰/引导。manifest 增 `MANAGE_EXTERNAL_STORAGE`（含 `tools:ignore` 说明用途）。
3. **护栏（agent 层白名单）**：在 agent 执行链（命令执行工具 / 文件写路径）解析「容器路径 → host 路径」，命中 `/sdcard` 顶层目录且不在白名单时，走**确认弹窗**（复用现有 LoginPrompt/Takeover 的用户确认通道）。用户终端（raw PTY）不拦。
4. **生效语义**：新命令/新终端立即生效；已运行 shell 需重开（bind 为 per-process 视图）。

### 涉及文件
- [ContainerProfile.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/ContainerProfile.kt)（仅注释/字段语义确认，不改造）
- [LinuxContainerEngine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/LinuxContainerEngine.kt)（`buildBaseProotArgv` 动态绑 /sdcard）
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml)（`MANAGE_EXTERNAL_STORAGE`）
- `TerminalSettingsRepository.kt`（开关 + 引导状态）
- agent 写路径护栏（确认通道 + 白名单）

**优先级**：P0（高价值、低改动）

---

## 2. 容器内 helper 命令（Topic 2）

### 定案
- 命名：独立 **`rcb-*`** 前缀。
- 桥接：**Unix socket 绑定为主通路**（终端 + agent 两条执行链都走）；stdout OSC 仅作**用户可读回显**（不承载功能）。
- 首批 6 个：`rcb-clipboard get/set`、`rcb-open-url`、`rcb-open <file>`、`rcb-toast`、`rcb-notify`、`rcb-share`。
- open-url 落点：**跟随用户设置**（内置浏览器 / 系统默认）。
- 剪贴板 get：接受 Android 13+ 后台限制（仅前台可读）。

### 设计（桥接协议）
- **Host 侧**：一个 `LocalServerSocket` 监听 `filesDir/rcb.sock`；proot 用 `-b filesDir/rcb.sock:/run/rcb.sock` 绑进容器。helper 脚本向 `/run/rcb.sock` 写一行 JSON `{cmd, args}`，Host 端 `RcbBridge` 读取后按 cmd 分发到 `ClipboardManager` / `Intent.ACTION_VIEW/ACTION_SEND` / `Toast` / `NotificationManager`。
- **OSC 回显**：helper 同时向 stdout 写 `\x1b]RCB;<结果>\x07`，仅给人看，不解析。
- **helper 脚本注入**：provision 阶段（现成 EnsureAndroidEnv 流程）把脚本写进 `${PREFIX}/bin/`（rootfs 内），保证 `$PATH` 可达。
- **安全**：socket 文件位于 app 私有目录，容器内进程天然可达、外部 app 不可达，无需额外鉴权。

### 涉及文件
- 新增 `RcbBridge`（LocalServerSocket + 命令分发）＋ 可选把它挂进 `TerminalKeepaliveService` 生命周期
- provision 脚本（rootfs 内 `rcb-*`）
- [LinuxContainerEngine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/LinuxContainerEngine.kt)（`-b` 绑 socket + `$RCODECORE_BRIDGE` 环境变量）
- `TerminalSettingsRepository.kt`（open-url 落点开关）

**优先级**：P0

---

## 3. 后台保活（Topic 3）

### 定案
- 触发：**agent 跑任务时**升前台服务，空闲释放；多任务引用计数，最后一个结束才降级。
- 类型：**`dataSync`** + **`PARTIAL_WAKE_LOCK`**（CPU 不休眠）。
- 通知：常驻通知 + **「停止」按钮**，点击回 App。
- 停止：先补 **agent 任务取消/中断机制**（粒度=当前步骤/tool：销毁子进程，agent 循环感知取消），再挂按钮。
- 终端：本期只保活 agent（终端自己跑的长命令沿用现有 `TerminalKeepaliveService` 的后台会话计数机制）。

### 现状
- `TerminalKeepaliveService` 已实现 `dataSync` FGS + `sessionCount` 引用计数 + 「常驻保活」开关 + `START_STICKY` 兜底（[TerminalKeepaliveService.kt](app/src/main/java/com/R/codecore/feature/terminal/domain/TerminalKeepaliveService.kt)）。
- 已注册 `foregroundServiceType="dataSync"`；已有 `FOREGROUND_SERVICE`、`POST_NOTIFICATIONS` 权限。
- **缺**：agent 维度的触发/计数、`WakeLock`、通知「停止」动作、agent 取消通道。

### 改动点
1. **agent 触发接线**：在 agent 任务循环启动/结束时，向 `TerminalKeepaliveService` 发 `ACTION_AGENT_START`/`ACTION_AGENT_STOP`（复用现有 action 机制，新增两个常量），用代理计数替代/叠加 `sessionCount` 语义。
2. **唤醒锁**：service 内 `PowerManager.WAKE_LOCK`（`PARTIAL_WAKE_LOCK`），count>0 时持锁、归零释放；manifest 增 `WAKE_LOCK`。
3. **停止按钮**：通知加 `stop` action → `pendingIntent` 触发「取消当前 agent 步骤」。
4. **agent 取消机制**：给执行链引入取消 token（`CommandEngine` 子进程按 token 销毁；工具循环在每步前后检查取消态），取消后 agent 给出「已取消」收尾而非继续。
5. **通知文案**：区分「agent 正在执行任务 / N 个任务进行中」。

### 涉及文件
- [TerminalKeepaliveService.kt](app/src/main/java/com/R/codecore/feature/terminal/domain/TerminalKeepaliveService.kt)（扩展：agent 计数、WakeLock、stop action）
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml)（`WAKE_LOCK`）
- agent 任务运行器（`AIAgentViewModel` 等）＋ `CommandEngine` 取消 token

**优先级**：P1（收益最大，依赖取消机制，工作量中上）

---

## 4. 终端交互精修（Topic 4）

### 定案
- URL 长按：弹「打开 / 复制 / 分享」，打开复用 open-url 落点设置。
- 捏合缩放：缩放字号并持久化 —— **已实现**（`scaleGesturePersistsFlow` 默认 true）。
- 长按选择：选区自动复制 + 轻提示。

### 现状
- `AppTerminalViewClient.onLongPress()` 目前返回 `false`（未处理）；`isTerminalViewSelected() = true`。
- 缩放/剪贴板已达标。

### 改动点
1. **URL 识别 + 长按菜单**：在 `onLongPress` 或 `TerminalView` 的链接命中回调里，识别光标/长按处的 URL（复用 emulator 屏幕缓冲 + 终端文本的 URL span），弹 Compose 菜单（打开/复制/分享）。打开走 helper `rcb-open-url`（或直接 Intent），复用设置落点。
2. **长按选区自动复制**：`onLongPress` 进入 termux 库的文本选择模式，选区结束后 `onCopyTextToClipboard` 已触发复制，补一个轻 Toast 提示即可（核心链已有，只需补提示 + 可能放宽 `isTerminalViewSelected`）。

### 涉及文件
- [TerminalClients.kt](app/src/main/java/com/R/codecore/feature/terminal/presentation/component/TerminalClients.kt)（`onLongPress`、URL 菜单）
- `TerminalScreen.kt` / `TerminalComponents.kt`（菜单 UI 挂载）

**优先级**：P2（成本低，纯前端）

---

## 5. 开机自启 + intent 触发（Topic 5）

### 定案
- 开机自启：`BOOT_COMPLETED` → 有任务则发「点击启动」通知，用户点开才拉起 FGS（合规）。
- intent 触发：对外 receiver，**白名单 + 签名校验**；内容 **任意 prompt**（走 `enqueueAgentRequest`）。
- 开机任务 = 设置里预填的**固定 prompt**（可空，空则仅唤起 App）。

### 现状
- 有 `enqueueAgentRequest(projectRoot, targetSessionId, isAutoTrigger)` 现成入口。
- 无 `BootReceiver`、无对外触发 receiver。

### 改动点
1. **`BootReceiver`**：注册 `RECEIVE_BOOT_COMPLETED`；开机时若「开机 prompt」非空，发通知「点击启动」；点开 → 拉起 App 并 `enqueueAgentRequest(prompt)`。
2. **对外 receiver**：新增 `RunAgentReceiver`（`exported=true` + `signature` 权限 + 白名单包名校验），解析 prompt → `enqueueAgentRequest`。安全上仅接受 `signature` 级别或显式白名单来源。
3. **设置项**：`TerminalSettingsRepository` 增「开机启动 prompt」字符串项。
4. manifest：`RECEIVE_BOOT_COMPLETED`、receiver 声明。

### 涉及文件
- 新增 `BootReceiver.kt` / `RunAgentReceiver.kt`
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml)
- `TerminalSettingsRepository.kt`（开机 prompt）

**优先级**：P3（中低价值，中等成本）

---

## 6. rootfs 备份恢复（Topic 6）

### 定案
- 粒度：**包清单 + 配置层**（非全镜像 tar）。
- 存储：**设备存储**（复用 Topic 1 的 `~/storage/shared`）。
- 恢复：重装包 + 还原配置，挂靠现有 BackupSection。

### 现状
- 已有 Backup module（`BackupManagerImpl` / `BackupSection` / `BackupSnapshot`），但覆盖的是 app 数据/密钥，**不含容器 rootfs**。

### 改动点
1. 备份：收集容器内 `apk list --installed` 清单 + `$HOME`/`/etc`/`~/.rcodecore` 可变文件，打包成快照写到设备存储。
2. 恢复：读取快照 → 重跑 `apk add` 清单 + 释放配置。挂靠 `BackupSection` 增加「容器环境」分组，或新增容器管理入口。
3. 版本兼容：快照记录容器基础镜像版本，跨版本仅恢复包清单（配置按需兼容）。

### 涉及文件
- [BackupManagerImpl.kt](app/src/main/java/com/R/codecore/feature/backup/data/BackupManagerImpl.kt) 或 [BackupSection.kt](app/src/main/java/com/R/codecore/feature/backup/presentation/BackupSection.kt)
- [ContainerInstaller.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/ContainerInstaller.kt)（包清单收集/重装钩子）

**优先级**：P3（中低价值）

---

## 7. 实现清单（按批次）

### P0（先做，最稳）
- [ ] Topic 1：`/sdcard` 动态绑定 + `MANAGE_EXTERNAL_STORAGE` + 默认关开关
- [ ] Topic 2：`RcbBridge`（socket）+ 6 个 `rcb-*` helper + provision 注入 + open-url 设置

### P1（收益最大）
- [ ] Topic 3：agent 保活接线 + `WakeLock` + 停止按钮 + agent 取消机制

### P2（低成本纯前端）
- [ ] Topic 4：URL 长按菜单 + 长按选区自动复制提示

### P3（可选/低频）
- [ ] Topic 5：`BootReceiver` + `RunAgentReceiver` + 开机 prompt
- [ ] Topic 6：容器包清单+配置 快照/恢复（挂 BackupSection）

---

## 8. 风险与兼容

- **`MANAGE_EXTERNAL_STORAGE` 审核**：上架需说明"用户主动开启以在容器内读写设备文件"；未授权时退化为不挂载。
- **Android 14+ `FOREGROUND_SERVICE_DATA_SYNC`**：现有 service 已声明 `dataSync` 类型，需补对应权限 `FOREGROUND_SERVICE_DATA_SYNC`（Android 14 强校验）。
- **proot bind 为 per-process**：存储绑定/新 helper 生效需新开终端/命令，旧会话不生效——用提示引导用户重开。
- **agent 白名单护栏**：仅拦"经 agent 工具层"的写；agent 直接 shell `cp` 的场景需在命令解析层做路径归并，实现时单独确认拦截点。
- **取消机制**：粒度定在"当前步骤"，销毁子进程后需保证 agent 状态机与消息落库序列（OpenAI tool_call 配对约束）一致，避免 400。

---

## 9. 验收标准

1. 打开「共享设备存储」后，新终端内 `ls ~/storage/shared` 能看到设备文件；关闭后新终端不可见。
2. `rcb-clipboard set/get`、`rcb-open-url`、`rcb-open <file>`、`rcb-toast`、`rcb-notify`、`rcb-share` 在终端与 agent 两条链下均可用。
3. agent 任务运行期间，通知栏出现「停止」按钮，点击后当前步骤立即中断。
4. 终端长按 URL 弹「打开/复制/分享」；长按文本选区自动复制。
5. 开机后有「点击启动」通知，点开按预填 prompt 跑 agent。
6. 容器备份/恢复往返后，已装包 + `$HOME` 配置还原一致。