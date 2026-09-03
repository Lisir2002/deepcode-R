# 数据保全（Data Preservation）设计方案

> 状态：已实施
> 关联问题：「历史对话在升级之后清空」
> 关联模块：`feature/backup`、`feature/settings`、`feature/agent`、CI

## 1. 背景与问题复盘

### 1.1 现象

用户多次反馈「升级之后历史对话被清空，只剩一条会话」。

### 1.2 根因（已验证）

`applicationId`（包名）历史上变更过三次：

```
com.aicodeeditor → com.deep.rcode → com.R.codecore → com.core.deepcode
（第四次为 rc11 DeepCore-Code 品牌迭代：显式 rebrand，内测阶段执行；
  旧包名 `com.R.codecore` 已纳入 Gradle 白名单、CI 门禁与单测的防回退黑名单）
```

Android 按包名隔离应用私有数据（`/data/data/<包名>/`）。**包名变更在系统眼里等于"卸载旧 App、安装了一个完全不同的新 App"**：新包名是"全新安装"，其数据目录为空 → 用户覆盖安装新包后，旧包全部历史对话不可见。

### 1.3 放大因素（本次暴露出的系统性缺口）

| # | 缺口 | 说明 |
|---|------|------|
| G1 | 包名无稳定性约束 | rebrand 时随意改 applicationId，无编译/发布期拦截 |
| G2 | 无数据完整性自检 | 数据"突然变空"时应用完全无感知，无法主动提示用户 |
| G3 | 无自动备份 | 用户没有主动导出习惯，数据丢失后不可找回 |
| G4 | 变体包名不同 | debug 带 `.debug` 后缀，与 release 数据互不可见，混装时用户误判"丢失" |
| G5 | 无迁移引导入口 | 即使旧包仍装着，用户也不知道可以导出/导入找回 |

## 2. 设计目标与原则

**目标：历史数据「不丢、能救、可恢复」**，把"包名变更导致数据消失"从"不可逆事故"变成"可感知、可恢复的异常"。

**原则**：
- 只读式探测：所有自检逻辑不主动删改用户数据，只做检测、备份与提示。
- 最小改动：复用现有 `BackupManager`，不引入 WorkManager 等新依赖。
- 数据安全：自动备份写入应用私有目录，不触碰用户可见文件，轮转清理防膨胀。

## 3. 方案总览：三层防线

```
防线一：防变更（编译期/发布期硬约束）
   D1 applicationId 稳定性单测        ✅ 已实施
   D2 CI 发版门禁：包名一致性校验       ✅ 已实施
   D3 构建期 applicationId 白名单校验  ✅ 已实施

防线二：防丢失（运行时数据安全网）
   D4 数据完整性哨兵（Data Sentinel）  ✅ 已实施
   D5 升级前自动备份（Auto Backup）    ✅ 已实施
   D6 自动备份状态可视化（Backup 页）  ✅ 已实施

防线三：可找回（迁移与恢复入口）
   D7 同签名旧包检测横幅               ✅ 已实施
   D8 数据丢失告警 + 一键自动备份恢复   ✅ 已实施
   D9 About 页变体/包名展示            ✅ 已实施

配套：单测 + 文档
   D10 哨兵/轮转逻辑单元测试            ✅ 已实施
   D11 用户文档 + 模块文档同步          ✅ 已实施
```

## 4. 详细设计

### 4.1 D2：CI 发版门禁（包名一致性校验）

**位置**：`.github/workflows/android-release.yml`，在 `Verify versionCode monotonic` 步骤之后新增一步。

**逻辑**：
1. 取当前 tag 与上一个 tag（`git tag --sort=-creatordate` 排除当前）。
2. 分别 `git show <tag>:app/build.gradle.kts` 提取 `applicationId = "..."` 并比对。
3. 不一致 → `::error::` 并 exit 1，阻断发版。
4. 无历史 tag（首次发版）→ 跳过。

**效果**：即便未来有人改包名，Tag 发版也会在 CI 被拦下，杜绝"带新包名的包流入用户"。

### 4.2 D3：构建期 applicationId 白名单校验

**位置**：`app/build.gradle.kts` 底部增加一个校验 Task（挂在 `preBuild` 上）。

**逻辑**：

```kotlin
// 数据保全：applicationId 白名单硬校验（改动即编译失败）
val allowedApplicationIds = setOf(
    "com.core.deepcode",            // 正式/Release
    "com.core.deepcode.debug",      // 本地 Debug（.debug 后缀，与 release 数据隔离）
)
tasks.register("checkApplicationIdWhitelist") {
    doFirst {
        require(android.applicationVariants.all { it.applicationId in allowedApplicationIds }) {
            "applicationId=${...} 不在白名单 $allowedApplicationIds 内。禁止变更包名——" +
            "包名变更在 Android 上是全新安装，会导致用户历史对话不可见（详见 docs/plan-docs/data-preservation-design.md）"
        }
    }
}
tasks.named("preBuild") { dependsOn("checkApplicationIdWhitelist") }
```

> 实现细节：`applicationVariants` 的 `applicationId` 在配置期即可读取，需用 `forEach` 收集后在 `doFirst` 校验。

**效果**：本地构建在编译期就拦截包名改动，与 D1 单测、D2 CI 构成三重防线。

### 4.3 D4：数据完整性哨兵（Data Sentinel）

**目标**：感知"数据突然变空"，区分「全新安装」与「数据丢失」。

**模块归属**：`feature/backup/data/guard/`（复用 backup 模块已注入的 DAO 与 DataStore 依赖）。

**新增文件**：
- `feature/backup/data/guard/AppRunMeta.kt`：运行元数据存储（DataStore）。
- `feature/backup/data/guard/DataSentinel.kt`：自检逻辑。

**运行元数据（AppRunMeta，preferencesDataStore("app_run_meta")）**：

```kotlin
data class AppRunMeta(
    val dataInitialized: Boolean = false,  // 是否已在本包名下建立过数据
    val lastVersionCode: Int = 0,          // 上次运行的 versionCode
    val lastApplicationId: String? = null, // 上次运行时的包名
)
```

**启动自检流程（DataSentinel.check()）**：

```
1. 读 AppRunMeta + 当前 versionCode/applicationId + 会话总数 count
2. 若 !dataInitialized：
     → 本次为全新安装（或首次运行），写 dataInitialized=true + lastVersionCode，静默返回 NORMAL_FIRST_RUN
3. 若 dataInitialized && 会话总数 == 0：
     → 检测到"数据丢失"迹象（上次有数据，本次却为空）
     → 返回 LOST（触发 D8 告警）
4. 若 lastVersionCode < 当前 versionCode（正常升级）：
     → 触发 D5 升级前自动备份，更新 lastVersionCode，返回 UPGRADED
5. 若 lastApplicationId != 当前 applicationId：
     → 包名被改动（理论上被 D1/D3 拦截，这里是运行时兜底）
     → 触发 D7/D8 迁移引导，返回 PACKAGE_CHANGED
6. 其余：更新 lastVersionCode，返回 NORMAL
```

**新增 DAO 查询**（`ChatSessionDao`）：

```kotlin
@Query("SELECT COUNT(*) FROM chat_sessions")
suspend fun count(): Int
```

**UI 状态输出**：哨兵结果写入 `SharedFlow`/状态，设置页与启动弹窗订阅。

### 4.4 D5：升级前自动备份（Auto Backup）

**模块归属**：`feature/backup/data/AutoBackupManager.kt`。

**逻辑**：
1. 哨兵判定为"正常升级"（versionCode 增加）时，后台自动执行 `BackupManager.export(null, BackupOptions() /* 全量 */, FileOutputStream)`。
2. 备份文件写入 `context.filesDir/auto-backups/backup-<epochMs>.tar.gz`（应用私有目录，无需 SAF）。
3. 轮转：目录内仅保留最近 **7 份**，超出按创建时间删除最旧的。
4. 全程 `runCatching`，失败仅记日志，不影响启动。
5. 备份源数据范围：`BackupOptions()` 默认全选（providers/gitCredentials/remoteConnections/chatHistory/mcpServers/permissionRules/appSettings）。

**挂载点**：`AIEditorApp.onCreate` 中 `appScope.launch { dataSafetyCoordinator.run() }`，延后于首帧（与现有 SSH 链一致的延后策略）。

> 不引入 WorkManager：本项目无该依赖。用"启动时检测"覆盖"升级"这一核心场景，另有设置页「立即备份」按钮作为补充。

### 4.5 D6：自动备份状态可视化 + D8 恢复入口

**位置**：`feature/backup/presentation/BackupSection.kt`（扩展）与 `BackupViewModel`。

**新增 UI（Backup 页，置于 LegacyDataRecoveryBanner 之后）**：

1. **自动备份卡片** `AutoBackupCard`：
   - 显示：上次自动备份时间、本机备份份数。
   - 按钮：**立即备份到本机**（写私有目录，与 D5 同路径）。
2. **数据丢失告警弹窗**（哨兵返回 LOST/PACKAGE_CHANGED 时，启动首帧后弹出）：
   - 文案：检测到历史数据未在本包名下，本机存在最近自动备份（若有）。
   - 按钮：**从自动备份恢复** → `BackupManager.import(filesDir 最新备份, null)`；**暂不处理**。
   - 同时保留既有 `LegacyDataRecoveryBanner`（旧包仍在时的迁移路径）。

**数据安全中心归属**：为控制改动面，不新增独立二级页，直接在 Backup 页顶部聚合展示（备份 + 自动备份 + 恢复引导三块）。

### 4.6 已实施部分（本次战役已完成）

- **D1**：`app/src/test/java/com/core/deepcode/core/applicationid/ApplicationIdStabilityTest.kt`（release 包名锁死 + 禁回退遗留包名，3 用例）。
- **D7**：`BackupSection.kt` 的 `LegacyDataRecoveryBanner`（同签名旧包检测 → 引导备份迁移）。
- **D9**：`AboutSection.kt` 的 `VariantPill` + 包名展示（debug/release 混装可见性）。

## 5. 数据流与调用链

```
应用启动 onCreate
   └─ appScope.launch { dataSafetyCoordinator.run() }   // 延后 500ms，不抢首帧
        ├─ 读 AppRunMeta(DataStore) + 当前 versionCode/applicationId + ChatSessionDao.count()
        ├─ 分支判定（DataSentinel）
        │    ├─ NORMAL_FIRST_RUN  → 写元数据，静默
        │    ├─ UPGRADED          → AutoBackupManager.run() 自动备份 + 轮转
        │    ├─ LOST              → 触发 D8 告警（存在自动备份则提供一键恢复）
        │    └─ PACKAGE_CHANGED   → 触发 D7/D8 迁移引导
        └─ 结果写入状态，BackupSection 订阅渲染
```

## 6. 实施计划

| 步骤 | 改动 | 涉及文件 | 分支 |
|------|------|---------|------|
| S1 | D2 CI 门禁 | `.github/workflows/android-release.yml` | main 直提（CI 配置） |
| S2 | D3 构建白名单 | `app/build.gradle.kts` | feat/data-safety |
| S3 | D4 哨兵（AppRunMeta + DataSentinel + DAO count） | `feature/backup/data/guard/*`、`ChatSessionDao.kt` | feat/data-safety |
| S4 | D5 自动备份（AutoBackupManager + 轮转） | `feature/backup/data/AutoBackupManager.kt` | feat/data-safety |
| S5 | D6/D8 UI（自动备份卡片 + 告警恢复弹窗） | `BackupSection.kt`、`BackupViewModel.kt`、strings.xml | feat/data-safety |
| S6 | D10 单测（哨兵分支 + 轮转） | `feature/backup` 测试 | feat/data-safety |
| S7 | D11 文档同步 | `backup-and-restore.md`、`docs/modules/backup.md` | feat/data-safety |

验证：`./gradlew :app:assembleDebug :app:testReleaseUnitTest`；CI 全流程；Tag 发版监控。

## 7. 风险与权衡

| 风险 | 权衡/缓解 |
|------|----------|
| 自动备份占用私有目录空间 | 轮转只留 7 份；tar.gz 全量含聊天历史，单份通常 < 10MB |
| 启动自检增加耗时 | 仅在 versionCode 变化时做全量备份；哨兵本身仅一次 COUNT 查询，毫秒级 |
| 数据丢失误报（用户主动清空聊天） | 告警仅提示且可关闭，不强制；文案说明"若为主动清空可忽略" |
| 备份含敏感明文（API Key/Git Token） | 自动备份无口令为明文，仅存应用私有目录（其他 App 不可读）；设置页提示用户如需跨设备请手动加口令导出 |

## 8. 评审状态

- [x] 问题复盘完成
- [x] 方案总览与详细设计完成
- [x] 实施（S1–S7）
- [x] 验证与发版
  - `./gradlew :app:assembleDebug` 通过（含 D3 applicationId 白名单配置期校验）
  - `./gradlew :app:testReleaseUnitTest` 通过（192 用例，含 D1/D10 新增用例）
