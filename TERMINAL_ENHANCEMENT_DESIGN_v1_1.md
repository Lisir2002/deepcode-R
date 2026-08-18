# 终端体验强化设计文档（Termux 优点对齐）v1.1

> v1.1 修订记录（相对 v1.0）：
> - **重查并修正若干"基于错误前提"的结论**：因 `targetSdk=28` 锁死（[build.gradle.kts](app/build.gradle.kts#L140-L142)），
>   本 App 命中 legacy storage、且豁免 Android 12+/14+ 的后台 FGS 限制。据此：
>   - 删除 `MANAGE_EXTERNAL_STORAGE`（对 target<30 无意义）；
>   - 开机自启从"系统限制所以只能点开"改为"**我们主动加的安全闸**"（技术其实能直接跑）；
>   - 删除 Android 14 `FOREGROUND_SERVICE_DATA_SYNC` 的"否则会崩"表述（targetSdk=28 不受其约束）。
> - **护栏与取消机制重做**：复用项目**已有的成熟能力**（ZTH 确认卡 + `ToolPermissionPolicy.ASK` + 协程 `CancellationException`），而非另造解析器。详见第 10 节。
> - 保留 v1.0 六主题框架，逐条给出"修正后方案 + 现状 + 缺口 + 涉及文件 + 优先级"。

---

## 0. 现状盘点（对齐"已有 vs 缺口"，v1.1 校准）

| 能力 | 现状 | 缺口 |
|---|---|---|
| 终端仿真引擎 | 已用 Termux `terminal-emulator` / `terminal-view` | 无（内核已对齐） |
| extra keys / 修饰键 | 已有全尺寸/简洁档 + Ctrl/Alt（[TerminalClients.kt](app/src/main/java/com/R/codecore/feature/terminal/presentation/component/TerminalClients.kt)） | 缺自定义布局 |
| 双指缩放+持久化 | 已实现（`onScale` + `scaleGesturePersistsFlow`） | 无 |
| 剪贴板互通 | 已有「系统⇄终端」(`onCopyTextToClipboard`/`onPasteTextFromClipboard`) | 缺**容器内主动读写**（桥） |
| 后台保活 | 已有 `TerminalKeepaliveService`（`dataSync` FGS + 引用计数，[TerminalKeepaliveService.kt](app/src/main/java/com/R/codecore/feature/terminal/domain/TerminalKeepaliveService.kt)） | 缺 agent 维度触发、唤醒锁、停止按钮、取消 |
| 命令权限闸 | **已有** `ExecuteCommandTool`=`ASK` + ZTH 确认卡（`ZthConfirmationCardManager` 12×17 状态机 + sentinel + SwipeToConfirm） | 缺存储敏感路径的专门确认 |
| 冲突取消 | 已有协程 `CancellationException` 织入工具层 | 缺 `CommandEngine` 级确定性 `cancel` |
| 存储互通 | 无 `/sdcard` 绑定；`ContainerProfile.extraBindings` 已预留 | 缺运行时开关 + 授权 + 护栏 |
| 容器内 helper | 无 | 全新 |
| 开机自启 / intent | 无 receiver；有 `enqueueAgentRequest` 触发入口 | 全新触发层 |
| rootfs 备份 | Backup module 管 app 数据/密钥，**不含 rootfs** | 新增容器快照维度 |

---

## 1. 存储互通（Topic 1）

### 定案
- 范围/权限：**整卡读写**，容器内挂 `~/storage/shared`。
- 授权方式：**走 legacy storage**。`targetSdk=28` → 只需 `READ/WRITE_EXTERNAL_STORAGE`（manifest 已有）运行时授权，即可访问整卡。**不引入 `MANAGE_EXTERNAL_STORAGE`**。
- 默认：关，设置里手动开；未授权时引导授权。
- 护栏：见第 10.1 节（复用 ZTH 确认卡 + 专用存储工具白名单）。

### 现状
- `ContainerProfile.extraBindings` 已存在、内置 profile 为空（[ContainerProfile.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/ContainerProfile.kt)）。
- proot 基础 argv（`buildBaseProotArgv`）只绑 `/dev /proc /sys /system`。
- manifest 已有 `READ/WRITE_EXTERNAL_STORAGE`。

### 改动点
1. **运行时开关**：新增「共享设备存储」预选项（`TerminalSettingsRepository` 或新 `StorageShareSettingsRepository`）。`buildBaseProotArgv` 处按开关动态追加 `-b /storage/emulated/0:~/storage/shared`（不写死 profile，因内置 profile 不支持静态改 bind）。
2. **运行时授权**：开关开启时，若未获写存储权限 → 走标准运行时请求（legacy 授权）。Android 10- 存在范围差异，需读 `Environment.isExternalStorageLegacy()` 判定；不可用整卡时 UI 提示降级范围。
3. **护栏**：见 10.1（不 grep shell，改走专用工具 + 确认卡）。
4. **生效语义**：bind 为 per-process，新命令/新终端立即生效；旧 shell 需重开（UI 提示）。

### 涉及文件
- [LinuxContainerEngine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/LinuxContainerEngine.kt)（`buildBaseProotArgv` 动态绑）
- `TerminalSettingsRepository.kt`（开关 + 授权状态）
- 新增存储读写工具 + ZTH 确认接线（第 10.1）

**优先级**：P0

---

## 2. 容器内 helper 命令（Topic 2）

### 定案
- 命名：**`rcb-*`** 独立前缀。
- 桥接：**loopback TCP `127.0.0.1:<port>`** 为主通路（规避 proot 对 unix-socket 特殊文件 bind 的不确定性，见 v1.1 漏洞 H）；stdout OSC 仅作**用户可读回显**，不承载功能。
- **鉴权（v1.1 修正）**：放弃"无需鉴权"。连接握手交换**会话令牌**；按令牌来源区分**agent 令牌**与**用户终端令牌**，两端能力集合不同（见 10.3）。
- 首批 6 个：`rcb-clipboard get/set`、`rcb-open-url`、`rcb-open <file>`、`rcb-toast`、`rcb-notify`、`rcb-share`。
- open-url 落点：跟随用户设置（内置浏览器 / 系统默认）。
- 剪贴板 get：接受 Android 13+ 后台限制（仅前台可读）。

### 设计（桥接协议）
- Host 侧 `RcbBridge`：`ServerSocket` 监听 loopback，accept 后先做令牌握手，再按协议分行读取 JSON `{cmd, args}` 分发到 `ClipboardManager` / `Intent` / `Toast` / `NotificationManager`。
- **能力隔离（v1.1）**：
  - `share` / `open-url` 属**敏感/渗出**操作：仅**用户终端令牌**可用；agent 令牌默认禁用（除非用户在设置里显式放开，并弹醒风险）。
  - `toast` / `notify` 全端可用。
  - `clipboard get`：用户端可用；agent 端必须经 `rcb-clipboard get` 工具化 + 确认（防污染）。
- **helper 脚本注入**：provision 阶段把脚本写进 `${PREFIX}/bin/`，`$PATH` 可达；脚本内不硬编码端口，从环境变量 `RCB_BRIDGE_ADDR` 读取。
- 生命周期：`RcbBridge` 挂进 `TerminalKeepaliveService`（与后台保活同生共死）。

### 涉及文件
- 新增 `RcbBridge`（ServerSocket + 握手 + 分发 + 能力隔离）
- provision 脚本（rootfs 内 `rcb-*` + 读 `RCB_BRIDGE_ADDR`）
- [LinuxContainerEngine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/LinuxContainerEngine.kt)（注入 `RCB_BRIDGE_ADDR` 环境变量）
- `TerminalSettingsRepository.kt`（open-url 落点 + agent 端 share/open 放开开关）

**优先级**：P0

---

## 3. 后台保活（Topic 3）

### 定案
- 触发：agent 跑任务时升前台服务，空闲释放；多任务引用计数，最后一个结束才降级。
- 类型：**`dataSync`** + **`PARTIAL_WAKE_LOCK`**。
  - 注：`targetSdk=28` 不受 Android 14 FGS 类型强校验约束，现有 `dataSync` 不会因此崩；`FOREGROUND_SERVICE_DATA_SYNC` 近期无需补（留为未来升级 targetSdk 的备注）。
- 通知：常驻通知 + 「停止」按钮，点击回 App。
- 停止：先补 **agent 取消机制**（粒度=当前步骤/tool），见 10.2；再挂按钮。
- 终端：本期只保活 agent。

### 现状
- `TerminalKeepaliveService` 已实现 `dataSync` FGS + `sessionCount` 引用计数 + 常驻开关 + `START_STICKY` 兜底；manifest 已注册 `dataSync` 类型。
- 已有 `FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`。
- **缺**：agent 计数、`WakeLock`、通知「停止」action、`CommandEngine.cancel`。

### 改动点
1. **agent 触发接线**：agent 任务循环启/停时向 service 发 `ACTION_AGENT_START/STOP`（新增常量），以 agent 代理计数叠加/替代 `sessionCount`。
2. **唤醒锁**：service 内 `WakeLock`（`PARTIAL_WAKE_LOCK`），count>0 持锁、归零释放；manifest 增 `WAKE_LOCK`。
3. **停止按钮**：通知加 `stop` action → `pendingIntent` 触发"取消当前 agent 步骤"（走 10.2）。
4. **取消机制**：见 10.2。
5. **通知文案**：区分「agent 执行中 / N 个任务进行中」。

### 涉及文件
- [TerminalKeepaliveService.kt](app/src/main/java/com/R/codecore/feature/terminal/domain/TerminalKeepaliveService.kt)（agent 计数、WakeLock、stop action）
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml)（`WAKE_LOCK`）
- [CommandEngine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/CommandEngine.kt) + [LinuxContainerEngine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/LinuxContainerEngine.kt)（`cancelRunning`）
- agent 任务运行器 / `AIAgentViewModel`

**优先级**：P1

---

## 4. 终端交互精修（Topic 4）

### 定案
- URL 长按：弹「打开 / 复制 / 分享」，打开复用 open-url 落点设置。
- 捏合缩放 + 持久化：**已实现**，无需改。
- 长按选区自动复制：时序已通（`onCopyTextToClipboard` 已触发复制），补一个轻 Toast 提示。

### 现状
- `AppTerminalViewClient.onLongPress()` 返回 `false`（未处理）；再现未集成。
- 缩放/剪贴板已达标。

### 改动点
1. **URL 识别 + 长按菜单**：在长按/链接命中回调识别光标处 URL（复用 emulator 屏幕缓冲），弹 Compose 菜单（打开/复制/分享）。打开走 `rcb-open-url` 或直接 Intent，复用设置落点。
2. **长按选区提示**：进入 termux 本文选择模式，结束时补一个轻 Toast。

### 涉及文件
- [TerminalClients.kt](app/src/main/java/com/R/codecore/feature/terminal/presentation/component/TerminalClients.kt)（`onLongPress`）
- `TerminalScreen.kt` / `TerminalComponents.kt`（菜单 UI）

**优先级**：P2

---

## 5. 开机自启 + intent 触发（Topic 5）

### 定案（v1.1 重新定性）
- **技术前提修正**：`targetSdk=28` → 本 App **能在 `BOOT_COMPLETED` 直接 `startForegroundService`**（Android 12+ 后台 FGS 限制仅针对 targetSdk31+）。
- 因此"开机→点开才跑"不再是系统强制的，而是**我们主动加的安全闸**：默认关闭，仅在设置里显式开启。
- 即便开启，**开机不自动跑 agent**，只发「点击启动」通知唤起 App（≤预填 prompt，且仅 woken 后跑）；真正的 agent 全自动执行不纳本期。
- intent 触发：对外 receiver，**白名单 + 签名校验**；内容任意 prompt（走 `enqueueAgentRequest`）。
- 开机任务 = 设置里可预填的**固定 prompt**（空则仅唤起 App）。

### 现状
- 有 `enqueueAgentRequest(projectRoot, targetSessionId, isAutoTrigger)` 现成入口。
- 无 `BootReceiver`、无对外 receiver。

### 改动点
1. `BootReceiver`：注册 `RECEIVE_BOOT_COMPLETED`；开启且 prompt 非空 → 发「点击启动」通知。
2. `RunAgentReceiver`（`exported` + `signature` 权限 + 白名单）→ 解析 prompt → `enqueueAgentRequest`。
3. 设置项：「开机启动 prompt」+「开机提醒」总开关。
4. manifest：`RECEIVE_BOOT_COMPLETED` + receiver 声明。

### 涉及文件
- 新增 `BootReceiver.kt` / `RunAgentReceiver.kt`
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml)
- `TerminalSettingsRepository.kt`

**优先级**：P3

---

## 6. rootfs 备份恢复（Topic 6）

### 定案
- 粒度：**包清单 + 配置层**（非全镜像）。
- 存储：设备存储（复用 Topic 1 的 `~/storage/shared`）。
- 恢复：重装包 + 还原配置，挂靠现有 BackupSection。

### 现状
- Backup module（`BackupManagerImpl` / `BackupSection`）只管 app 数据/密钥，不含 rootfs。

### 改动点
1. 备份：收集 `apk list --installed` 清单 + `$HOME`、`/etc`、`~/.rcodecore` 可变文件 → 打包到设备存储。
2. 恢复：`apk add` 清单 + 释放配置。挂靠 `BackupSection` 增「容器环境」分组或新增容器管理入口。
3. 版本：快照记录基础镜像版本，跨版本仅恢复包清单（配置按需兼容）。
4. 风险（v1.1 明示）：Alpine 源可能已更致重装失败 → 若失败保留快照 + 提示手动换源（不自动清数据）。

### 涉及文件
- [BackupManagerImpl.kt](app/src/main/java/com/R/codecore/feature/backup/data/BackupManagerImpl.kt) / [BackupSection.kt](app/src/main/java/com/R/codecore/feature/backup/presentation/BackupSection.kt)
- [ContainerInstaller.kt](app/src/main/java/com/R/codecore/feature/agent/domain/container/ContainerInstaller.kt)

**优先级**：P3

---

## 7. 实现清单（按批次）

### P0（最稳）
- [ ] Topic 1：`/sdcard` 动态绑 + legacy 授权 + 开关 + 10.1 护栏
- [ ] Topic 2：`RcbBridge`（TCP + 握手 + 能力隔离）+ 6 个 `rcb-*` helper + provision 注入 + open-url 设置

### P1（收益最大）
- [ ] Topic 3：agent 保活接线 + `WakeLock` + 停止按钮 + 10.2 取消机制

### P2（低成本纯前端）
- [ ] Topic 4：URL 长按菜单 + 长按选区提示

### P3（可选/低频）
- [ ] Topic 5：`BootReceiver` + `RunAgentReceiver` + 开机提醒
- [ ] Topic 6：容器包清单+配置 快照/恢复

---

## 8. 风险与兼容（v1.1 重算）

- **`targetSdk=28` 是安全/合规双刃**：豁免现代后台限制 → 最需要显式安全闸（开机不自动跑 agent、helper 能力隔离、存储默认关）。同时不可上 Google Play（已在 [build.gradle.kts](app/build.gradle.kts#L293-L294) 注明）。
- **legacy 存储授权**：`WRITE_EXTERNAL_STORAGE` 运行时授权；Android 10- 存在范围差异需探测 `isExternalStorageLegacy`，否则降级提示。
- **proot bind 为 per-process**：存储/new helper 对旧会话不生效，需 UI 引导重开终端。
- **agent 白名单护栏（10.1）**：靠"确认卡 + 专用工具"，不 grep shell；原始 `cp` 落 Bash（本就 ASK 确认）。
- **取消机制（10.2）**：销毁子进程后需保证 agent 消息落库序列（OpenAI tool_call 配对）一致，避免 400。
- **helper 能力隔离（10.3）**：`share`/`open-url` 默认 agent 禁用；`clipboard` agent 工具化。
- **WakeLock 泄漏（漏洞 G）**：进程被回收未发 STOP → count 不归零。处置：`START_STICKY` 重建时若 agent 计数丢失则清零并释放锁；锁+timer 设最大持有时长兜底。

---

## 9. 验收标准

1. 打开「共享设备存储」后，新终端 `ls ~/storage/shared` 可见设备文件；关后可开放新终端不可见。
2. `rcb-clipboard get/set`、`rcb-open-url`、`rcb-open`、`rcb-toast`、`rcb-notify`、`rcb-share` 在终端与 agent 两链均可用；**agent 端无法直接调 `share`/`open-url`**（除非设置显式放开）。
3. agent 任务运行期间，通知栏出现「停止」，点击后当前步骤立即中断；子进程被杀、工具循环感知取消后收尾。
4. 终端长按 URL 弹「打开/复制/分享」；长按选区自动复制。
5. 开机后不自动跑 agent；开启提醒时，「点击启动」通知 → 按预填 prompt 跑。
6. 容器备份/恢复往返后，已装包 + `$HOME` 配置还原一致。

---

## 10. 本版新增：关键防护/机制设计（针对严查漏洞）

### 10.1 存储护栏（复用 ZTH 确认卡，不 grep shell）
- **结论**：`ExecuteCommandTool` 已是 `ToolPermissionPolicy.ASK`，项目已有成熟 ZTH 确认卡（[ZthConfirmationCardStateMachine.kt](app/src/main/java/com/R/codecore/feature/agent/domain/zth/ZthConfirmationCardStateMachine.kt)、`ZthConfirmationCardManager`、sentinel、SwipeToConfirm）。因此**不新造 shell 路径解析器**（不可靠）。
- **方案**：对"共享设备存储"引入两个独立工具：`storage_read`（只读，`ASK`）+ `storage_write`（写，更高确认档）。`storage_write` 命中 `/sdcard` 白名单目录一律确认，白名单顶层目录（Download、专门共享目录）可 `ASK`=confirm 免 list 级却仍需确认卡；敏感目录（DCIM/照片/微信/QQ/支付宝/网盘）走 tier 更高确认。原始 `cp` 等仍落 Bash（本就逐步确认）。容器路径→host 路径仅在这两个专门工具里做（工具参数结构化，无需解析 shell）。

### 10.2 agent 取消机制（粒度=当前步骤/tool）
- **结论**：工具层已织入协程 `CancellationException`（[ExecuteCommandTool.kt](app/src/main/java/com/R/codecore/feature/agent/domain/tool/container/ExecuteCommandTool.kt) 的 `catch (CancellationException)`）。
- **改动**：`CommandEngine` 增 `suspend fun cancelRunning()`（默认空实现）；`LinuxContainerEngine` 覆写为**销毁本进程组**（`processHandle.destroyForcibly()` 或 `kill -<group>`），远程实现走 SSH exec 中断。通知「停止」→ 触发 agent 当前协程 `cancel()` → 工具循环收到取消 → 以"已取消"收尾并保证消息落库配对不破。

### 10.3 helper 能力隔离（终结"无需鉴权"）
- loopback TCP + 握手令牌；`ServerSocket` 只绑 `127.0.0.1`。
- 能力表：
  | 操作 | 用户终端令牌 | agent 令牌 |
  |---|---|---|
  | clipboard get/set | ✅ | ✅（工具化+确认） |
  | toast/notify | ✅ | ✅ |
  | open-url / open | ✅ | ⛔默认 / 设置放开 |
  | share | ✅ | ⛔默认 / 深度确认放开 |

### 10.4 锁定前提的复核结论（v1.1 明确）
- targetSdk=28 → legacy storage、豁免 12+/14+ FGS 限制 → 方案按"技术能跑但主动限制"设计，安全性靠"默认关 + 能力隔离 + 开机不自动跑"。
- 若未来升级 targetSdk（需先解决 PRoot W^X 问题再谈），上述限制将重新生效，届时再按现代约束调整（MANAGE_EXTERNAL_STORAGE / FGS type 权限 / 开机只能点开）。已在设计保留此迁移备注。