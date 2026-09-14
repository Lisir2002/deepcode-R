// ── 品牌 / 产物参数单一事实源（Centralized Branding Config）────────────────
// 统一管理：应用显示名、APK 产物名前缀、release keystore 文件名、launcher 图标资源族名。
// Gradle 接入：app/build.gradle.kts 顶部 apply(from = file("branding.gradle.kts"))，随后经
//              extra["brandAppName"] 等读取。此处改动后每次构建自动生效。
// CI 接入：.github/workflows/android-release.yml 用 `grep` 从本文件 const 提取 APK_PREFIX /
//          KEYSTORE_FILE / APP_NAME（不重复硬编码），与 Gradle 共享同源。
//
// ⚠️ 边界 — 以下参数刻意不进本文件，由既有纪律保护（改它们会触发构建/测试/CI 三重阻断或造成事故）：
//   - 应用包名 applicationId：数据保全三重防线（app/build.gradle.kts 白名单硬校验 +
//     ApplicationIdStabilityTest 单测 + CI 发版门禁）锁定 com.mini.me_core(.debug)。
//     包名变更在 Android 上是"全新安装"，会导致用户历史对话不可见
//     （历史已因此四次数据丢失，见 docs/plan-docs/data-preservation-design.md）。
//   - 发版版本 versionName/versionCode：由 git tag 四段版本动态推导（gitVersionName()/
//     gitVersionCode()），纪律明确禁止手写。发版只需在 main 打 tag（v0.0.0.1-rcN / v0.0.0.1）。

/** 应用显示名。经 build.gradle.kts resValue 覆盖 res/values/strings.xml 的 app_name；
 *  英文翻译保留在 values-en/strings.xml（本品牌名中英一致）。 */
val APP_NAME = "MiniMe-core"

/** APK / Release 资产名前缀，产物形如 <APK_PREFIX>-<版本>.apk（如 minime-v1.0.0-rc1.apk）。 */
val APK_PREFIX = "minime"

/** release keystore 文件名（app 目录下，与生成的 keystore.properties 的 storeFile 对应）。 */
val KEYSTORE_FILE = "minime.jks"

/** launcher 图标资源族名（mipmap ic_launcher / ic_launcher_round / ic_launcher_foreground）。
 *  换图标时替换同名资源即可；若改族名，同步调整 res/mipmap-* 目录下的资源文件名即可。 */
val ICON_FAMILY = "ic_launcher"

// ── 签名策略（SIGNING POLICY，唯一官方密钥）───────────────────────────────
// 正式发布（release）一律使用**唯一官方 keystore**：app/<KEYSTORE_FILE>（= minime.jks）。
// 按维护者决定，该 keystore 与 app/keystore.properties **已入库**（见 .gitignore），
// 保证本地与 CI 每一次 release 都加载同一把密钥、签名指纹恒定。
// 构建/发版强制约束：app/build.gradle.kts 的 release signingConfig require——
//   打 release 包前必须存在 app/keystore.properties（storeFile/storePassword/keyAlias/keyPassword），
//   缺失即构建失败，**不再静默回退 debug keystore 当正式签名**。
// ⚠️ 安全提示（维护者已知情并授权）：私钥随仓库公开，等同于任何能读仓库者都可冒充该签名；
//   如未来上架/对公开分发敏感，建议改用 CI secrets 持有密钥并撤销本入库决定。

/** release 唯一官方 keystore 别名（与 app/keystore.properties 的 keyAlias / 证书一致）。 */
val SIGNATURE_ALIAS = "minime"

// 暴露给 app/build.gradle.kts 实际使用。
// 注意：apply(from=…) 场景下 cross-script 顶层 const 不能直接 import，必须经 extra 传递。
extra.set("brandAppName", APP_NAME)
extra.set("brandApkPrefix", APK_PREFIX)
extra.set("brandKeystoreFile", KEYSTORE_FILE)
extra.set("brandIconFamily", ICON_FAMILY)