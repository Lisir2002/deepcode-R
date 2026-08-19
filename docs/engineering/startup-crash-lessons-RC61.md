# R-CodeCore 工程经验文档（Android · 启动稳定性 / 升级兼容性 / CI 守卫）

> **文档定位**：专属于 R-CodeCore 项目的「教训 & Bug 修补经验手册」。
> 本手册只收录**踩过坑、交过学费、有具体修复落地代码位置**的经验，不收录任何泛泛而谈的"最佳实践"空话。
>
> - **RC61 主事件**（v0.1.0-rc60 → v0.1.0-rc61b）：升级用户启动 1-2 秒无弹窗闪退、卸载重装就好、日志拿不到。
> - **历史融合经验**（ExperienceRecall 479976 / 291148 / 1498720）：Android 构建 / ABI / 崩溃证据链 / 补丁最小化 / 反模式。
>
> 维护者：R-CodeCore 项目全体 · **每次发布 RC 或正式版本前，发布负责人必须通读 §6 的 Checklist**。

---

## 0. 速记卡片（贴在显示器上的那一张）

| # | 口诀 | 违反后果 |
|---|---|---|
| ① | 「重装就好 = 升级专属分支有 bug，绝不是用户环境问题」 | 你会继续在首次安装的成功路径上瞎改，永远复现不了 |
| ② | 「闪退没日志 = CrashHandler 用了异步写 / ContentProvider 抛异常直接杀进程」 | 下一轮照样拿不到证据链，只能猜 |
| ③ | 「Provider.onCreate / 冷启动 Flow transform 里不准碰 DB、不准碰 Keystore、不准做 asset IO」 | 1~2 秒 ANR，系统无声杀进程，无崩溃弹窗 |
| ④ | 「升级路径（增量迁移）> 首次安装路径」，**RC 前必须列 UPGRADE-PATH 清单** | migration 32 类问题只炸老用户，首次安装 smoke 根本测不到 |
| ⑤ | 「Room schema 校验是字节级的——迁移 SQL 要与 Entity 严格同名同序同类型」 | Room onOpen 抛 IllegalStateException：expected/found schema 不一致 |
| ⑥ | 「打补丁 = 最小差异原则；没有崩溃栈 = 不动公共接口/头文件/线程池」 | 非补丁式重构引入新变量，用户反馈"原地踏步" |
| ⑦ | 「替换魔法数字前，先在 compileSdk 里 grep 官方常量**真的存在**」 | PURPOSE_UNWRAP_KEY 类不存在符号 → compileReleaseKotlin 全红，浪费一轮 CI |
| ⑧ | 「任何 @Provides / @Singleton 注入链同步方法，外层必须有 Throwable 级兜底」 | Hilt component build 失败 → 系统直接杀进程 |
| ⑨ | 「ABI / 签名 / 对齐问题有客观证据链，不靠猜测——查 APK lib/ 目录」 | 把 x86_64 模拟器翻译层 SIGSEGV 和真实 arm64 闪退混为一谈 |

---

## 1. RC61 主事件复盘（为什么升级用户闪退 1-2 秒、卸载重装就好？）

### 1.1 现象
- **用户**：升级到 RC61 后，点击图标 1-2 秒自动关闭，**无崩溃弹窗**、**日志目录里 CRASH 行为空**。
- **用户试错**：卸载重装 RC61 APK → 立刻不再闪退。

### 1.2 "卸载重装"到底清了什么？
卸载 = 同时清空 4 样东西，让升级路径的分支**全部不执行**：

| 资源 | 升级路径（旧用户 = 闪退） | 首次安装（新用户/重装 = 不闪退） |
|---|---|---|
| `databases/rcodecore_agent_db` | SCHEMA_VERSION ≤ 31 → 必须走 migration 32 + Room 全 schema 字节级校验 | fallbackCreateFromScratch 直接按 Entity 建 32，0 迁移 0 校验 |
| `shared_prefs/execution_mode_prefs.xml`（DataStore） | `PASSWORD_KEY` 有 legacy 明文/中间态 V2 密文 → `decryptCredentialCompat` 真会跑 | 全 key 为空 → `decryptCredentialCompat` 直接 `return ""`，**连调用都不会做** |
| `filesDir/credential_encryption_state`（Room 表） | 存在旧行 → `ensureInitialized` 读 → MasterKey fingerprint 核对 / unwrapDek / Keystore 服务调用 | 表空 → 生成新 MasterKey+DEK 直接写入，0 对比 0 失败 |
| Android Keystore 别名 `rcodecore_credential_masterkey` | 存在旧 MasterKey（可能是 RC60 旧版本生成 / ROM 升级后 Keystore 变脏 / 用户改锁屏 / StrongBox 异常） | 包名删除 = Keystore 条目系统自动清 → 新 MasterKey 0 对比 |

### 1.3 根因链（按触发概率排序）

```
升级用户冷启动
 ├─ 主线程 Application.onCreate
 │   └─ Hilt component.build() → provideAgentDatabase()
 │       └─ Room DB 第 1 次 open()
 │           ├─ 执行 migration 32（慢 + schema 字节级校验）
 │           └─ 持有 Room DB 单例锁
 │
 └─ 后台线程（Hilt 构造 CredentialRequestBridge→LinuxContainerEngine→…）
     └─ 首次订阅 remoteConnectionFlow → mapLatest → decryptCredentialCompat
         └─ encryptor.decrypt → ensureInitialized（旧 RC61 实现）
             ├─ stateDao.getSingleOrNull → 第 1 次 Room DB open() ← 等主线程那把锁
             ├─ Keystore getOrCreateMasterKey → 和 TEE 通信 300~2000ms（锁/首次解锁后更长）
             └─ MasterKey fingerprint 核对 / unwrapDek / any RuntimeException → 穿透杀进程
     
     ↓ ↓ ↓ 伪死锁 + 主线程首帧不绘制 + 超时 5s（低端机 1-2s 触发） ↓ ↓ ↓
     
     ActivityManager: Launch timeout has expired, giving up wake lock!
     系统 killProcess（无崩溃弹窗，无 ANR 对话框）
     CrashHandler 异步 e() → ioExecutor 排队任务 → 进程被杀 = 队列清空 = 用户「拿不到日志」✔
```

### 1.4 修复落地位置（每次回归都对这些文件做 smoke diff）

| # | 文件 | 修复要点 | 代码锚点 |
|---|---|---|---|
| A | [FileLogger.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/core/util/FileLogger.kt) | 新增 `flushSync(level, tag, msg, t)`：阻塞当前线程 append + `FileChannel.force(false)`，保证 return 前日志已在文件里 | L? 搜索 `fun flushSync` |
| B | [AIEditorApp.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/AIEditorApp.kt) | installCrashHandler 用 **flushSync("FATAL")** + 先 logcat 再交 default uncaught handler 杀进程 | L? 搜索 `installCrashHandler` |
| C | [WorkspaceDocumentsProvider.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/workspace/data/provider/WorkspaceDocumentsProvider.kt) | ① onCreate 独立 FileLogger.init（Provider 比 Application.onCreate 更早）<br>② 全 SAF @Override 入口 `providerSafe {}` 异常统一转 `FileNotFoundException`<br>③ `exposedChildren()` 去掉 `extractDocs(ctx())` 热路径 asset IO | L? 搜索 `providerSafe`、`onCreate`、`exposedChildren` |
| D | [AgentModule.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/di/AgentModule.kt) | `provideAgentDatabase` 三阶段兜底：首阶段迁移 → 次阶段 destructive rebuild → 三阶段终极 try Throwable destructive | L? 搜索 `provideAgentDatabaseInternal` |
| E | [MigrationLoader.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/core/db/MigrationLoader.kt) | 整个 doLoad 外层 runCatching，失败返回空数组（由 AgentModule 转 destructive） | L? 搜索 `fun doLoad` |
| F | [CredentialEncryptor.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/core/security/CredentialEncryptor.kt) | `ensureInitialized` 永不抛 MasterKeyTamperedException：失败只 flushSync 记日志 + dekCached=null + initialized=false 允许重试 | L? 搜索 `suspend fun ensureInitialized` |
| G | [ExecutionModeRepository.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/feature/settings/data/repository/ExecutionModeRepository.kt) | `decryptCredentialCompat` 从 `suspend + encryptor.decrypt` 改成**纯前缀判断的普通函数**：V2:→""，其他→raw。**彻底切断冷启动 Flow → encryptor → DB open → 争用的链** | L? 搜索 `fun decryptCredentialCompat` |
| H | [DEKManager.kt](/workspace/deepcode-R/app/src/main/java/com/R/codecore/core/security/DEKManager.kt) | 定义局部兼容常量 `PURPOSE_UNWRAP_KEY_COMPAT = 8`，不引用官方未公开的 `KeyProperties.PURPOSE_UNWRAP_KEY`（compileSdk 里不存在） | L? 搜索 `PURPOSE_UNWRAP_KEY_COMPAT` |

---

## 2. 九大工程铁律（RC 前必须逐条过一遍）

### 铁律 1：升级路径（增量迁移）优先级 ≥ 首次安装路径
**为什么**：90% 的"重装就好"类闪退都埋在升级分支；首次安装 smoke 通过 ≠ 升级通过。

**落地动作**（发布 RC 前必须产出）：
1. 产出 `UPGRADE-PATHS.md` 清单（或在 commit message 里分章节），逐条列出：
   - 哪些 Entity / DataStore key 被迁移；
   - 哪些 `if (旧值 != null) { ... }` 代码分支老用户会走、新用户不会走；
   - 哪些 Provider / Application / Worker 启动路径因"已有数据"触发额外 IO。
2. 本地至少做一次"低版本 DB 文件拷过来 + 启动 App + Room onOpen 校验"的手动回归（有条件则做成单元测试：`FileMigrationTest.kt` 已存在，扩展即可）。

### 铁律 2：ContentProvider 生命周期比 Application.onCreate 更早，且 Provider 抛异常 = 系统直接杀进程
**为什么**：Android 规定：ContentProvider.onCreate 在 `Application.attachBaseContext` 之后、`Application.onCreate` 之前执行；Provider 抛任何 RuntimeException（包括 NullPointerException / IllegalStateException），系统直接结束该 app 进程，**不走 uncaughtExceptionHandler、不弹窗、不提示**。

**铁律落地**：任何自定义 Provider（DocumentsProvider / FileProvider / WorkManagerInitializerProvider）满足三条：
1. **onCreate 内必须先初始化 FileLogger**（否则 Provider 崩了，日志里没记录）；
2. **所有 @Override 方法必须有统一 wrapper**：只允许抛 `FileNotFoundException` / `SecurityException` / `IllegalArgumentException` 这类 SAF/Provider 契约内定义的异常，任何内部 RuntimeException 一律 wrap 成契约异常后再抛；
3. **onCreate / queryRoots / exposedChildren 等热路径禁止做：asset IO / DB open / Keystore 调用 / network / 任何可能 IOException 的跨文件操作**。要做的放在 Application.appScope 的 `launch(Dispatchers.IO)` 里异步延后执行。

### 铁律 3：冷启动 Flow 的 transform（map / mapLatest / transform）里不准触 DB、不准碰 Keystore
**为什么**：`@Singleton` 的 Flow 往往在 Hilt 构造期（另一个后台线程）立刻被首次订阅，transform 的代码会在**主线程同时构建 Hilt 组件**的情况下并行执行——DB/Keystore 都是全局单例资源，必然形成争用，伪死锁后就是「1-2 秒闪退」。

**落地**：
- 对 `remoteConnectionFlow`、`executionModeFlow` 这种「默认值就足够」的 Flow，transform 里只做纯前缀、纯字段读取，绝不调用 `encryptor.decrypt`、`dao.select`。
- 如果真的需要解密凭据才能返回 → 改成 Flow 返回**占位对象 + activeConnectionId 指针**，由 UI 层（进 Settings 页 / 进入 SSH 标签页）**用户显式触发**时再去 Room 查 + 解密。

### 铁律 4：CrashHandler 必须同步阻塞落盘，禁止走异步 Executor
**为什么**：`defaultUncaughtExceptionHandler.uncaughtException()` 返回后，Android Framework 会**立即 killProcess**；任何放在 Executor 队列里的写日志任务，此时根本没机会跑——结果就是"闪退了，但日志里啥都没有"。

**落地**：
- 日志库必须暴露两个入口：一个普通异步（日常日志，性能优先）、一个 `flushSync`（CrashHandler 专用，return 前必须保证行写入 + 最好 `channel.force(false)` 到 OS）。
- CrashHandler 顺序固定：
  1. `android.util.Log.e` 保底进 logcat；
  2. `FileLogger.flushSync("FATAL", "CRASH", summary, t)` 阻塞写入；
  3. 再交给 `previous.uncaughtException` 杀进程（如果它自己也炸，兜底 `Process.killProcess + System.exit(10)`，绝不能"吞掉异常继续跑"）。

### 铁律 5：Room 迁移 SQL = 与 Entity 注解字节级一致
**为什么**：Room 在 DB open 结束时，会对 Entity 反射出来的期望 schema 和表里实际 schema 做**字节级比对**：列名、列顺序、列类型 affinity、NOT NULL、索引名、外键……一个不对就抛 `IllegalStateException("Migration didn't properly handle: ... Expected: ... Found: ...")`。首次安装不走迁移所以不炸，升级用户 100% 炸。

**落地**（migration 32 给我们的具体教训）：
1. **列名严格用 camelCase 匹配 Kotlin 字段名**：`masterKeyFingerprint` 对应字段 `masterKeyFingerprint`，不要写 `master_key_fingerprint`（snake_case）。
2. **移除 SQL DEFAULT 子句**：Room 不支持 SQLite 默认值和 Entity 默认值混写，直接去掉 DEFAULT，让 Kotlin 字段默认值负责。
3. **索引名严格匹配 Room 自动生成格式**：`index_<table>_<camelCaseCol>`，例如 `index_remote_audit_logs_createdAt`，不写 `idx_remote_audit_logs_created_at`。
4. **写一个 `FileMigrationTest`（项目已存在）**：把旧版 DB 文件放 test resources，跑 migrate 然后让 Room 打开它，断言 onOpen 不抛 IllegalStateException（这是唯一能 100% 挡住 schema 不匹配的测试）。

### 铁律 6：@Provides / @Singleton 的同步注入链，外层必须有 Throwable 级兜底
**为什么**：Hilt component.build() 过程中任何 `@Provides` 抛异常（包括 ExceptionInInitializerError / NoClassDefFoundError / Room 内部检查异常），都会让 `Application.onCreate` 直接失败 —— 用户视角就是"点图标闪一下消失"，连 Crash 弹窗都没有。

**落地**：
- `provideAgentDatabase` 必须三层：迁移阶段 try / destructive 阶段 try / 终极 Throwable try（任何一个阶段失败都继续向更保守的 fallback 走，绝不抛）。
- `MigrationLoader.loadMigrations` 外层必须 try：迁移资产读取失败返回空数组，由下游 AgentModule 转 destructive fallback（至少能启动，数据丢失优先级低于闪退）。

### 铁律 7："替换魔法数字" → 先在 compileSdk 里确认符号真的存在
**为什么**（教训来自 DEKManager 的 PURPOSE_UNWRAP_KEY 事件）：Android Keystore 文档语义上"应该存在"UNWRAP 常量，但实际上 compileSdk 里 `KeyProperties` 只公开了 `PURPOSE_WRAP_KEY=32`，`PURPOSE_UNWRAP_KEY` 从未公开为符号——逻辑上正确不代表 API 层面真能 import。直接替换硬编码只会引入**编译期的确定性失败**，浪费一轮宝贵的 CI/Release。

**落地**：
1. 替换前先：打开 Android Studio → 在 `KeyProperties.` 后面看自动补全列表有没有你要的符号；
2. 或者 grep `${ANDROID_HOME}/platforms/android-XX/android.jar` 反编译该类；
3. 两个办法都做不到时 → **定义局部常量 + 详细 KDoc**：
   ```kotlin
   // 为什么用局部常量：官方 KeyProperties 仅公开 PURPOSE_WRAP_KEY，不公开 PURPOSE_UNWRAP_KEY，
   // 位掩码 0x8 自 API 23 引入 wrap/unwrap 即存在且稳定。
   private const val PURPOSE_UNWRAP_KEY_COMPAT = 8
   ```

### 铁律 8：打补丁 / 修闪退 = 最小差异原则；没有崩溃栈 = 不改公共接口
**融合经验来源**：Experience 291148（打补丁场景被强行大改解码器/线程池头文件，用户反馈"原地踏步"）。

**为什么**：闪退 + 证据链不完整时，任何结构性重构（改公共 API、改变默认参数、替换线程池实现、改编码器默认压缩比）都会引入**新的变量**，让你下一轮无法判断崩溃"是旧问题还是你改出来的新问题"。

**落地**：
1. **补丁动作白名单**（可以做）：
   - 在现有方法内部加空值判断 / 边界保护 / runCatching；
   - 加日志（flushSync 同步）；
   - 把一个同步调 IO 的动作改成延后（async）；
   - 把抛异常改成返回默认值 / null / 旧行为。
2. **补丁动作黑名单**（没崩溃栈绝不做）：
   - 新增公共 API / 改变公开方法签名 / 改默认参数；
   - 重构线程池 / 解码器 / 编码器等核心组件；
   - 整文件 Write 覆盖；
   - 改变产品默认行为（压缩等级默认值、ABI 策略默认值）。
3. **只有在崩溃栈精确指向某组件内部（例如解码器的一帧）**，才允许动该组件；否则"加护栏 + 补日志"，等下一轮拿到 FATAL 栈再动刀。

### 铁律 9：ABI / 签名 / 对齐问题 —— 有客观证据链，不靠猜测
**融合经验来源**：Experience 479976 + 1498720（把 ABI 问题和翻译层崩溃混为一谈，导致无效改动）。

**落地**（一套固定流程，不要跳步）：
1. **ABI 结构核对**：对 APK 执行 `unzip -l app.apk | grep "lib/" | sort`，确认只包含哪些 ABI 目录；同时 `adb shell getprop ro.product.cpu.abilist` 拿目标设备支持列表，交叉。如果 APK 只包含 arm64-v8a 而连接设备显示 x86_64 → **ABI 不兼容结论只在这个时候下**，不要看到 SIGSEGV 就先定 ABI 罪。
2. **签名链核对**：用 `apksigner verify --verbose --print-certs app.apk`，确认 v2/v3 签名存在、对齐正确；不要让 release build 走 `jarsigner`（只产生 v1 签名，现代 Android 会报告"无效安装包"）。
3. **对齐→签名顺序固定**：`zipalign -f 4 in.apk aligned.apk` → `apksigner sign --ks ks aligned.apk`，顺序反了 v2 签名会被 zipalign 破坏。

---

## 3. 反模式红黑榜

### 🔴 反模式 3.1：CrashHandler 用异步写日志（已在 RC61b 修复）
- **后果**：闪退没日志，下一轮只能猜。
- **正确做法**：CrashHandler 专用 flushSync 同步入口。（见 铁律 4）

### 🔴 反模式 3.2：Provider.onCreate/热路径做 asset IO / DB open / Keystore（已在 RC61b 修复）
- **后果**：Provider 抛 RuntimeException = 系统直接杀进程，无弹窗。
- **正确做法**：全入口 providerSafe；IO 延后到 Application.appScope.launch(IO)。（见 铁律 2）

### 🔴 反模式 3.3：在冷启动 Flow transform 里触加密子系统 / DB（已在 RC61b 修复）
- **后果**：后台线程和主线程 Hilt 同时抢 Room DB 单例 → 伪死锁 → ANR 杀进程。
- **正确做法**：decryptCredentialCompat 改成纯前缀判断；真正查凭据延后到用户操作。（见 铁律 3）

### 🔴 反模式 3.4：Room migration 列名 snake_case、索引名自定、加 SQL DEFAULT（已在 RC61a 修复）
- **后果**：Room onOpen 校验 Expected/Found 不一致，升级用户 100% 崩；首次安装不崩 → smoke 通过，用户升级骂娘。
- **正确做法**：列名/索引名/类型/空值 affinity 严格跟 Entity 注解对齐。（见 铁律 5）

### 🔴 反模式 3.5：引用"逻辑上应该存在"的官方常量（PURPOSE_UNWRAP_KEY 事件）
- **后果**：compileReleaseKotlin Unresolved reference，CI 白烧一轮。
- **正确做法**：先确认符号存在；否则定义局部常量 + KDoc 解释。（见 铁律 7）

### 🔴 反模式 3.6：闪退没崩溃栈时，就改公共接口 / 线程池 / 解码器核心头文件
**融合经验来源**：Experience 291148。
- **后果**：引入新变量，下一轮崩溃无法判断是旧 bug 还是新 bug，用户反馈"原地踏步"。
- **正确做法**：只加护栏 + 补 flushSync 日志，等下一轮拿到 FATAL 栈再定点修复。（见 铁律 8）

### 🔴 反模式 3.7：用户明确"不关模拟器"，仍基于连接中的 x86_64 模拟器下 ABI 结论
**融合经验来源**：Experience 1498720 + 479976。
- **后果**：结论与用户真实事实（原始 APK 能装）冲突，用户认为跑偏。
- **正确做法**：当用户强调是真机场景时，先让用户给真机 ABI/Android 版本/安装报错原文，真机日志采集走 §4.1 SOP，**不要继续围绕模拟器给结论**。

### 🔴 反模式 3.8：为解决 mkversion 找 go.mod 问题，在仓库根创建伪 go.mod 软链接来"旁路修复"
**融合经验来源**：Experience 479976。
- **后果**：污染工程边界，其他模块/IDE/Goland 会误判项目模块根。
- **正确做法**：Makefile 中先 `cd <go.mod 所在目录>` 再执行 mkversion；或传路径参数（若工具支持）。

### 🔴 反模式 3.9：ABI 裁剪在 gomobile 上游做一套、Gradle packaging 再一套、最后手工 zip/unzip 重打包
**融合经验来源**：Experience 479976。
- **后果**：产物不一致概率成倍增加，release 和 debug 输出不同 ABI 集合，用户端出现"Failed to extract native libraries"。
- **正确做法**：**单一事实来源原则**——二选一：
  - （A）从 gomobile 上游 `--target=android/arm64` 就只产出 arm64，下游 Gradle 零额外处理直接消费；
  - （B）gomobile 保留全 ABI，只由 Gradle 的 `ndk.abiFilters` 单一入口决定打包。绝不能中间再加一步手工重打包。

### 🟢 正确模式 3.10：三句口头禅（已在 RC61b 全部落地）
1. **"重装就好 = 升级专属分支 bug"** → 直接列 UPGRADE-PATH 清单，不猜。
2. **"闪退没日志 = CrashHandler 异步写 + Provider 异常杀进程"** → 先补 flushSync + ProviderSafe，再生产下一轮崩溃栈。
3. **"没有崩溃栈 = 只加护栏不加特性"** → 最小差异补丁，等拿到 FATAL 栈再动刀。

---

## 4. 三套标准作业程序（SOP）

### SOP 4.1：Android 闪退/1-2 秒无弹窗 —— 证据链采集 & 修复流程
**适用症状**：点击图标闪一下、无崩溃弹窗、用户"闪退"反馈。

#### 阶段 A：采集（不做任何代码改动）
1. **清日志 → 启动 → 立即抓 FATAL**：
   ```
   adb logcat -c
   adb shell am force-stop <包名>
   adb shell monkey -p <包名> -c android.intent.category.LAUNCHER 1
   sleep 5
   adb logcat -d -b crash -b main -b system -s AndroidRuntime:E System.err:W ActivityManager:W *:F > /tmp/crash_$(date +%s).txt
   ```
   ⚠️ 只看**本次启动产生的最后 100 行**，别被历史日志干扰。
2. **取 App 私有日志**（FileLogger 输出目录，通常是 `Android/data/<pkg>/files/logs/log-YYYY-MM-DD.txt`），**直接 grep FATAL/ERROR/CRASH** —— 如果这里为空，立刻判断"CrashHandler 走异步"，先修铁律 4 再生产一次崩溃。
3. **采集用户一句事实**：是升级还是新装？卸载重装会不会好？（这句事实决定你走 §1 升级路径分析还是走首次安装 bug 分析）。
4. **列本次启动涉及的 Provider**：看 `AndroidManifest.xml` 所有 `<provider>`（包括 androidx.work / androidx.startup / 自定义 DocumentsProvider）——把它们的 onCreate 列出来，检查是否违反铁律 2。

#### 阶段 B：定位
- **"卸载重装就好"** → 直接列 UPGRADE-PATH 清单（见 铁律 1），只分析这些分支。
- **"重装也不好"** → 走"对比原始 APK vs 当前 APK"的差异分析：assets 列表、lib/<abi> 列表、签名链对齐（见 铁律 9）。
- **"有崩溃栈"** → 只围绕栈顶 3 帧对应方法做局部加护栏 / runCatching / 非空判断。

#### 阶段 C：修复 & 验证
- 补丁 = 最小差异（铁律 8）。
- 本地回归条件允许的话，准备一份"低版本 DB + Keystore 脏数据"作为升级 fixtures，专门复现升级路径。
- 推送 CI 前，对 hotfix 改的文件做**静态 import 自检**：grep 新增符号名是否在 import 区里（RC61b-hotfix3 因为没做这一步，缺 3 条 import 白白浪费一轮 CI）。

### SOP 4.2：RC / 正式发布前升级兼容性 Checklist
每条打勾后才能发布：

- [ ] **清单**：已产出本版本的 `UPGRADE-PATHS` 清单（migration 条目 / legacy DataStore key 分支 / 已有数据触发的额外 IO 点）。
- [ ] **CrashHandler**：`installCrashHandler` 使用 `flushSync` 同步落盘（不是异步 `e()`）。
- [ ] **Provider**：所有自定义 Provider 的 onCreate 只做轻操作；所有 SAF/Provider 对外 @Override 方法有统一 try/catch wrapper，不抛 RuntimeException 穿透。
- [ ] **冷启动 Flow**：`remoteConnectionFlow` / `executionModeFlow` 的 transform 方法不触 DB、不碰 Keystore、不做 asset IO。
- [ ] **Room migration**：migration N 的列名 camelCase、索引名 `index_<table>_<col>`、无 SQL DEFAULT；`FileMigrationTest` 能在低版本 DB 文件上通过且 onOpen 不抛。
- [ ] **AgentModule/MigrationLoader**：`provideAgentDatabase` 至少双阶段；`MigrationLoader.doLoad` 外层 try。
- [ ] **常量替换**：所有"官方常量替换魔法数字"已在 compileSdk 里 grep 确认存在；否则定义局部常量 + 详细 KDoc。
- [ ] **ABI/签名**：release APK 产出后 `unzip -l` 核对 ABI 目录、`apksigner verify` 核对 v2/v3、`zipalign -c` 核对对齐。
- [ ] **最后一步**：人工模拟"升级路径"一次——安装上一个 RC APK，不清数据升级到当前 RC APK，手动启动 10 次，统计闪退次数。

### SOP 4.3：CI compileReleaseKotlin 失败 5 分钟定位法
适用：GitHub Actions 上 compileReleaseKotlin FAILED，本地沙箱无 Gradle wrapper 无法复现。

1. **下载失败 job 的日志**（不是 run 级别 zip，是 job 级别单独 logs）：
   ```
   gh run view <run_id> --log-failed -L
   # 或用 API：GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs
   ```
2. **定位模式固定 5 个关键词**（按命中率排序）：
   ```
   grep -nE "Unresolved reference|Type mismatch|No value passed|is not accessible|e: file:" failed_job.txt | head -60
   ```
   - `Unresolved reference` → 90% 是**忘记加 import**（RC61b-hotfix3 就是）。
   - `Type mismatch / No value passed` → 改了方法签名但调用方没同步。
   - `is not accessible` → 把 internal/private 成员拿跨 module 用。
   - 剩下 5%：`Cannot access class '<X>'` / `inaccessible` → 通常是 R8/ProGuard 可见性问题 + testCompileClasspath 没开。
3. **修复后 commit message 必须写清原文错误信息 + 为什么缺**（方便以后回归搜索）。

---

## 5. 决策 Quick Reference 表

| 如果你遇到这种情况 | 立刻这样做 | 千万不要这样做 |
|---|---|---|
| 闪退没日志 | 把 CrashHandler 改成 flushSync 同步写；再检查 Provider 入口 providerSafe 包了没 | 改公共接口、换线程池、上大规模重构 |
| 用户说"卸载重装就好" | 列 UPGRADE-PATH 清单；准备低版本 DB fixtures 做升级回归；核对 migration SQL | 在首次安装路径上 smoke 然后宣称"已经修好" |
| 想替换魔法数字为官方常量 | Android Studio 自动补全确认 → 反编译 compileSdk 的 android.jar 确认 → 不确认就定义局部常量 + KDoc | 直接替换 push 等 CI 反馈 |
| 用户真机闪退、连接电脑显示是 x86_64 模拟器 | 让用户给真机 ABI/报错原文；真机日志用 §4.1 SOP | 基于模拟器 ABI 下结论，给 setprop/改 ABI 方案 |
| Room migration 后用户启动报 schema Expected/Found | 改 SQL：列名→camelCase、去 DEFAULT、索引名改为 index_<table>_<col>，三管齐下 | 不管，让用户重装（你只是在把 bug 推给用户） |
| Provider 异常导致无声闪退 | onCreate 先 FileLogger.init；全 SAF override providerSafe；热路径 IO 全部延后 | 在 queryRoots 里继续同步 extractDocs() 这类 asset IO |
| 打补丁，用户明说"小修" | 只在方法内部加空值/runCatching/日志；整文件 Edit 不做 Write；公共接口一个不改 | 整文件覆盖 Write；改默认参数；改默认行为；换线程池 |
| CI compileReleaseKotlin 红 | 拉失败 job 级 logs → grep 5 关键词 → 5 分钟定位 → commit message 抄原文错误 | 不停 retry run，希望第二次自己好 |

---

## 6. RC / 正式发布前 Checklist（打印 & 手动打勾）

> 发布负责人必须逐项勾完才能执行 `git push tag`。

### 6.1 启动稳定性
- [ ] CrashHandler 使用 flushSync 同步落盘。
- [ ] 自定义 Provider.onCreate 不做 IO；SAF 对外入口全 providerSafe。
- [ ] 冷启动 Flow transform 不触 DB、Keystore、asset IO。
- [ ] `provideAgentDatabase` 至少双阶段 fallback；`MigrationLoader` 失败返回空数组兜底。

### 6.2 升级兼容性
- [ ] 已产出本版本的 UPGRADE-PATH 清单，每条分支标注"只升级走 / 新装也走"。
- [ ] 新增 migration 的列名 camelCase、无 DEFAULT、索引名 Room 格式。
- [ ] FileMigrationTest（或手动 fixture 升级回归）对最后两个版本 DB 均通过。
- [ ] 所有"替换魔法数字"已确认 compileSdk 符号存在，或已定义局部兼容常量。

### 6.3 构建产物
- [ ] APK unzip -l：ABI 目录符合预期（arm64-v8a only）。
- [ ] apksigner verify：v2/v3 签名 OK、证书指纹正确。
- [ ] zipalign -c 4：对齐 OK。
- [ ] 命名 `rcodecore-arm64-v<version>.apk`、SHA256 在 Release body 中附出。
- [ ] prerelease flag 已对 RC 版本置 True。

### 6.4 最终人工回归
- [ ] 上一个 RC APK → 不清数据升级 → 启动 10 次 0 闪退。
- [ ] 卸载后新装 → 启动 5 次 0 闪退。
- [ ] 如果用户上轮反馈"闪退"，这一轮安装后必须**主动触发一次人为 crash**（例如临时写 `throw RuntimeException("test")`），确认 FileLogger 里 FATAL 行真的写入，再提交正式 tag。

---

## 7. 事后复盘标准模板

> 每次闪退事故、每次构建失败、每次升级问题，结束后必须按此模板写 1 页复盘，放进 `docs/engineering/incidents/` 目录。

```markdown
# Incident：<一句话描述> · 版本 vX.Y.Z-rcNN

## 0. 时间线（UTC）
- YYYY-MM-DD HH:MM 用户首次反馈
- YYYY-MM-DD HH:MM 拿到第一轮崩溃证据
- YYYY-MM-DD HH:MM 定位根因
- YYYY-MM-DD HH:MM 修复提交 commit <sha>
- YYYY-MM-DD HH:MM CI 成功
- YYYY-MM-DD HH:MM Release 成功

## 1. 用户现象（原文引用）
> 

## 2. 根因分析
### 2.1 现象 → 根因推导链（必填）
```
用户反馈 A
→ 排除 B（证据：...）
→ 排除 C（证据：...）
→ 剩下 D，对应代码 <锚点位置>
→ 复现路径：升级用户 / 新装用户 / 特定设备
```
### 2.2 根因代码锚点（必填，文件链接 + 行号）
- 

## 3. 修复内容（逐文件）
| 文件 | 修复方式（最小差异 / 结构重构 / 兜底） | 防止回归的机制 |
|---|---|---|
| `xxx.kt` |  |  |

## 4. 违反了本手册哪条铁律
- 铁律 N：<一句话解释为什么违反>

## 5. 反模式
- 反模式 X.Y

## 6. 防止回归的工程化动作
- [ ] 新增 FileMigrationTest fixture
- [ ] 新增启动稳定性单元测试 / 仪器测试
- [ ] 在 CI 里加 `grep "Unresolved"` 的 hotfix 自检步骤
- [ ] 其他：____

## 7. 如果下次回到这个节点，我会先做什么不同的事
> 
```

---

## 附录 A：RC61b 各轮 CI 失败速查表

| 轮次 | Commit | 失败阶段 | 原文报错 | 根因一句话 | 修复 commit |
|---|---|---|---|---|---|
| CI#93 / Release#77 | `cc41af0` RC61b | compileReleaseKotlin | `Unresolved reference: Migration`（实际在 AgentModule.addMigrations 参数类型找不到） | 重构 Room 双阶段时误删 `import androidx.room.migration.Migration` | `78d47ac` |
| CI#94 / Release#78 | `78d47ac` hotfix1 | compileReleaseKotlin | `Unresolved reference 'PURPOSE_UNWRAP_KEY'` | 引用了 Android Keystore 从未公开的符号 | `9a3614b`（定义 PURPOSE_UNWRAP_KEY_COMPAT=8 局部常量） |
| Release#80 | `35d75d2` hotfix3 | compileReleaseKotlin | `Unresolved reference 'CredentialEncryptionContract'.`<br>`Unresolved reference 'FileLogger'.`<br>`Unresolved reference 'FileLogger'.` | 改了两个文件代码逻辑但忘记补 3 条 import（Kotlin 不会自动加） | `a0ffabf` |
| CI#97 / Release#81 | `a0ffabf` hotfix3+4 | ✅ success | — | — | — |

---

## 附录 B：相关外部经验引用

- **Experience 479976**：Android ABI/签名/构建产物 6 条失败经验 → 已融入 铁律 9、反模式 3.7/3.8/3.9。
- **Experience 291148**：补丁最小化原则、无崩溃栈不大改 → 已融入 铁律 8、反模式 3.6。
- **Experience 1498720**：闪退证据链固定流程、真机 vs 模拟器目标收敛 → 已融入 SOP 4.1、铁律 9、反模式 3.7。

---

本手册随项目演进迭代，**每次遇到新的闪退/升级问题/CI 故障，先查本手册对应条款；如果手册里没有，解决之后必须补一节**。
