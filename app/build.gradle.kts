import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")

    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("app.cash.sqldelight")
}

// 从本地 keystore.properties 读取 release 签名密钥（已 gitignore，不入库）。
// 若文件不存在（如 CI 环境）则跳过，release 产出 unsigned 包。
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// 当前版本基线：0.5.1.1；后续版本号升级必须由用户明确指令
// 修复版本号漂移：此前 BASE_VERSION 停留在 0.3.0 但已发 v0.5.0-rc3 tag，
// 导致 gitVersionName() 无法匹配 v0.5.1.1* tag、产物 versionName 错误回退为 "0.3.0-dev"。
val BASE_VERSION = "0.5.1.1"

// 版本号策略：
//   1. 仅当 git tag 以 v0.5.1.1 开头时（如 v0.5.1.1 / v0.5.1.1-rcN），沿用 tag 中的后缀；
//   2. 其他情况（tag 为其他版本号 / 无 tag / 无 git 环境），一律 fallback 到 "0.5.1.1-dev"；
//   3. 严禁从旧 1.x tag 推导版本号，避免版本号回跳到 1.x 系列。
fun gitVersionName(): String = try {
    val process = Runtime.getRuntime().exec(
        arrayOf("git", "describe", "--tags", "--always", "--dirty"),
        null,
        rootProject.projectDir
    )
    process.waitFor()
    val raw = process.inputStream.bufferedReader().readText().trim()
    if (raw.startsWith("v")) {
        val version = raw.substring(1)
        // 仅接受 0.5.1.1 系列的 tag
        if (version.startsWith("$BASE_VERSION")) {
            // "0.5.1.1" / "0.5.1.1-rcN" / "0.5.1.1-N-gabcdef0"
            val devRegex = Regex("""^(\d+\.\d+\.\d+(?:-[a-zA-Z0-9]+)?)-(\d+)-g([0-9a-f]+)(.*)$""")
            val match = devRegex.matchEntire(version)
            if (match != null) {
                val (base, count, hash, dirty) = match.destructured
                "$base-dev.$count+$hash$dirty"
            } else {
                version
            }
        } else {
            // 非 0.5.1.1 系列 tag（如旧的 1.8.0），一律忽略，走 fallback
            "$BASE_VERSION-dev"
        }
    } else if (raw.isNotEmpty()) {
        "$BASE_VERSION-dev+$raw"
    } else {
        "$BASE_VERSION-dev"
    }
} catch (e: Exception) {
    "$BASE_VERSION-dev"
}

// versionCode 从 git 提交数自动生成：随每次提交单调递增，无需手动维护，
// 杜绝"升 versionName 忘升 versionCode"导致升级判定失效。
// 工作目录用 rootProject.projectDir（仓库根），无 git 环境（如下载 zip 构建）时 fallback 到 1。
// CI 额外校验 versionCode 单调（见 .github/workflows/android-release.yml），防 rebase/squash 改写历史导致回退。
fun gitCommitCount(): Int = try {
    val process = Runtime.getRuntime().exec(
        arrayOf("git", "rev-list", "--count", "HEAD"),
        null,
        rootProject.projectDir
    )
    process.waitFor()
    process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

android {
    namespace = "com.R.codecore"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    signingConfigs {
        // 统一签名策略：release / debug 都"必然有一个 signingConfig"，
        // 避免 CI 门禁跑 `:app:assembleRelease` 时因"release 没绑定 signingConfig"而被
        // AGP 在 packageRelease 阶段直接判失败（用户策略 = "所有测试/验证都用发行版"，
        // release 必须总能生成 APK，哪怕回退到默认 debug keystore 签名）。
        //
        // 规则：
        //   1. 优先：仓库根 keystore.properties（release 正式签名，本地/发版 CI secrets 生成）；
        //   2. 其次：自定义本地 debug keystore（/root/Android/Sdk/debug.keystore）；
        //   3. 最后：用户 home 默认 debug keystore（$HOME/.android/debug.keystore，
        //      AGP 会自动创建，CI/本地无配置时 99% 场景都会命中）。
        //
        // 同时强制 enableV1Signing=true + enableV2Signing=true：
        //   - v2/v3 是 Android 7+ 默认（快、抗篡改）；
        //   - v1 (JAR 签名) 给 jarsigner/某些老工具与 ROM 保留可识别的 META-INF/*.RSA，
        //     让"发出来是一个已签 APK"这件事对任何检查方式都成立。
        val customDebugKeystore = file("/root/Android/Sdk/debug.keystore")
        val defaultDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
        val fallbackDebugKeystore = when {
            customDebugKeystore.exists() -> customDebugKeystore
            defaultDebugKeystore.exists() -> defaultDebugKeystore
            else -> defaultDebugKeystore  // 两者都不存在：指向默认路径，AGP 会自动创建
        }

        create("androidDebug") {
            storeFile = fallbackDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }

        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } else {
                // 没有正式 release 签名时，回退到 debug keystore 签名 release buildType：
                //   - 保证 assembleRelease 在 CI/本地零配置下也能输出 APK；
                //   - 这不是"上架签名"，只是让 R8+资源收缩后的最终发行版形态能被构建/安装/测试。
                storeFile = fallbackDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.R.codecore"
        minSdk = 26
        // 锁定 targetSdk 28：Android 10+（API 29+）的 W^X/SELinux 策略禁止执行 App 可写
        // 数据目录里的文件，PRoot 二进制将无法运行（同 Termux 的取舍）。代价：不能上 Google Play。
        targetSdk = 28
        versionCode = gitCommitCount()
        versionName = gitVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 通用单包（用户决策「不分包」）：双 ABI 打入同一 APK，真机与模拟器/虚拟机都安装即用。
        //   - arm64-v8a：真机 arm64 / arm64 系统镜像模拟器（原生执行容器，默认路径）；
        //   - x86_64：x86_64 系统镜像模拟器（x86_64 原生 proot + x86_64 rootfs，见
        //     ContainerInstaller 双架构安装与 EnvironmentDetector 环境探测）。
        // Android 包管理器在安装/运行期按设备 ABI 自动选用 lib/arm64-v8a 或 lib/x86_64 下的 .so
        // （libtermux.so 由 terminal-emulator 模块为全部 ABI 提供），互不干扰、无需用户选择。
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // 双架构通用包：sourceSets.main.assets 挂 _armAssets，其内同时含 container/arm（arm64 容器）与
    // container/x86_64（x86_64 rootfs + arm64 宿主 qemu 转译器 + x86_64 宿主原生 proot），一并打进 APK，
    // 运行时由 EnvironmentDetector 按宿主架构选装/选用对应 rootfs 与 proot（见 ContainerInstaller）。
    sourceSets {
        getByName("main") {
            assets.srcDir("src/_armAssets")
        }
    }

    buildTypes {
        // debug 加包名后缀 .debug → applicationId 变 com.R.codecore.debug，与 release（com.R.codecore）
        // 可同机共存、互不覆盖。IDE 跑 debug 不再因签名不同卸载已装的正式版。
        // 注意：因 applicationId 不同，debug 变体私有目录为 /data/data/com.R.codecore.debug/，
        // release 已解压的容器 rootfs 与工作区项目在 debug 下不可见（需重新解压/clone），属预期隔离行为。
        debug {
            applicationIdSuffix = ".debug"
            // signingConfigs.androidDebug 已经在顶层保证永远有值（优先 custom → 默认 debug keystore，
            // 都不存在则指向默认路径让 AGP 自动建），这里直接绑定即可，不用再做 exists 判断。
            signingConfig = signingConfigs.getByName("androidDebug")

            // 统一让 debug APK 也做 DEX ZIP DEFLATE 压缩：
            //   开发期分发 debug 给他人时体积稳定在 ~35 MB，而非 AGP 默认 STORE 导致的 90 MB 膨胀。
            //   代价：模拟器首次安装需解压 DEX → oat 目录，慢约 2~5 秒；
            //   注：release 构建按用户策略是"所有测试/发布都用发行版"，这里仅给 debug 做体积收敛的友好默认。
            packaging {
                dex { useLegacyPackaging = false }
                jniLibs { useLegacyPackaging = false }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            // signingConfigs.release 顶层已经保证永远有值：
            //   - 有 keystore.properties → 正式 release 签名；
            //   - 没有 → 回退到 debug keystore（零配置 CI 仍能签出可安装的 release APK）。
            // 因此这里直接绑定，不再做 exists 判断，保证 assembleRelease 永远产出 APK。
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // release 构建的体积/性能深度优化：
            //   debugSymbolLevel=none  —— 不向 APK / AAB 注入 native 调试符号表，省 ~1MB+。
            //   isPseudoLocalesEnabled=false —— 关闭伪本地化资源，省少量体积。
            ndk { debugSymbolLevel = "none" }
            isPseudoLocalesEnabled = false

            // 利用 R8 / D8 的 advancedMode 与 useLegacyPackaging=false：
            //   useLegacyPackaging=false 让 APK 内的 .dex / .so 保持压缩状态被直接加载
            //   （需要 APK Signature Scheme v2，AGP 默认开启），省掉安装期解压副本占用的空间。
            packaging {
                jniLibs { useLegacyPackaging = false }
                dex { useLegacyPackaging = false }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // RC92：MigrationSchemaConsistencyTest 失败详情 println 到 stdout，
    // 默认 Gradle 吞掉测试 stdout/stderr，CI 日志看不到具体不一致项。
    // 开启 showStandardStreams 让失败详情直接出现在 CI 控制台日志。
    testOptions {
        // 让 android.util.Log 等桩方法返回默认值而非抛「not mocked」，便于被测代码间接调用日志不崩。
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                showStandardStreams = true
                showExceptions = true
                showStackTraces = true
                showCauses = true
            }
        }
    }

    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "17"
        // 全局 opt-in：Compose / Material3 实验性 API（FilterChip/ElevatedAssistChip/TooltipBox 等）
        // —— CI 的 -Werror 会把 experimental warning 当成 error，这里一次性白名单。
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }

    buildFeatures {
        compose = true
        // RC67a 新增：Android Gradle Plugin 8+ 默认不生成 BuildConfig.java（仅 buildConfig=true 时才生成）。
        // P0-2 assertContinuity 需要 BuildConfig.DEBUG 来区分 Debug/Release：
        //   Debug 构建发现 SCHEMA_GAP 直接抛异常 → 开发者/CI 立刻看见坏版本；
        //   Release 构建只写日志 → 保持启动安全语义（永不阻断启动）。
        buildConfig = true
    }



    // 全局打包选项：统一排除重复 META-INF 通知；
    // 所有变体（debug/release）都强制 dex/jniLibs 走 ZIP DEFLATE 压缩（useLegacyPackaging=false）。
    //
    // 背景：AGP 8.9.x 对 debug 变体的 packagingOptions 有隐藏的"debuggable → 强制 STORE"兜底逻辑，
    // 单独在 buildTypes.debug.packaging 里设置 dex.useLegacyPackaging=false 不一定能生效，
    // 表现就是 debug APK 的 DEX 全是 STORE（未压缩）→ 90MB 级。
    // 所以把全局默认放到 android.packaging 顶层（所有变体先继承这份值），
    // 再配合 buildTypes.release 的显式设置（release 本来就会压缩），
    // 最终让 debug APK 的 DEX 也稳定回到 DEFLATE（~35MB 级别，与旧 armsolo 一致）。
    packaging {
        dex { useLegacyPackaging = false }
        jniLibs { useLegacyPackaging = false }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/INDEX.LIST"
            excludes += "/sshj.properties"
            excludes += "/kotlin-tooling-metadata.json"
            excludes += "/DebugProbesKt.bin"
        }
    }

    // targetSdk 故意锁定 28（PRoot 需在 app 可写目录执行二进制，Android 10+ W^X 禁止），
    // 代价是不进 Google Play——故关闭该平台的过期 targetSdk 检查。
    // 同时关闭 release 构建的 lint 检查：本仓库只出 GitHub Release 不上 Play，
    // lintVital 在 R8/打包阶段额外吃 CPU 与内存（2 核 7GB runner 易 OOM），且其发现不阻塞发布。
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// 彻底禁用 lintVital 任务（现为 debug/release 各一个），
// 使其不进入 assembleRelease 的任务图——比 lint.checkReleaseBuilds=false 更省构建开销与内存。
// 仅在 release 任务图执行前禁用，避免影响开发期 debug lint。
gradle.projectsEvaluated {
    tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
}

// ── 数据保全：applicationId 白名单硬校验 ──────────────────────────────
// 包名（applicationId）变更在 Android 眼里是"全新安装"，私有数据目录随之隔离，
// 历史对话会全部"消失"（历史上已因此丢失三次，见 docs/plan-docs/data-preservation-design.md）。
// 这里在配置期对每个 variant 做白名单校验：包名不在白名单内 → 构建直接失败，
// 与单测 ApplicationIdStabilityTest（release classpath 断言）、CI 发版门禁（Tag 间一致性）
// 构成三重防线，杜绝 rebrand 误改包名再次造成用户数据丢失。
androidComponents {
    onVariants(selector().all()) { variant ->
        val id: String = variant.applicationId.get()
        require(id in ALLOWED_APPLICATION_IDS) {
            "applicationId=$id 不在白名单 $ALLOWED_APPLICATION_IDS 内。禁止变更包名——" +
                "包名变更在 Android 上是全新安装，会导致用户历史对话不可见" +
                "（详见 docs/plan-docs/data-preservation-design.md）。" +
                "如需 rebrand 请只改应用名/图标/namespace，勿改 applicationId。"
        }
    }
}

/** 允许的 applicationId 白名单：release=com.R.codecore，debug 带 .debug 后缀（与 release 数据隔离）。 */
val ALLOWED_APPLICATION_IDS = setOf("com.R.codecore", "com.R.codecore.debug")

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.animation:animation")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Hilt 依赖注入
    implementation("com.google.dagger:hilt-android:2.56.1")
    ksp("com.google.dagger:hilt-compiler:2.56.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // HTML 解析与清洗 (用于 WebFetchTool)
    implementation("org.jsoup:jsoup:1.18.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // 内置 MCP 服务器（Streamable HTTP）：Ktor CIO 起 HTTP 监听 + SSE，供外部 MCP 客户端连入。
    // 与项目协程/序列化栈同源（见 docs/plan-docs/builtin-mcp-server-design.md 决策记录）。
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-cio:2.3.13")

    // YAML 解析 (用于 Skill Frontmatter)
    implementation("org.yaml:snakeyaml:2.2")

    // 远程同步 (SFTP/FTP) 与内置 FTP 服务端
    implementation("com.hierynomus:sshj:0.38.0")
    // BouncyCastle：sshj 0.38.0 用 X25519 密钥交换，Android 自带裁剪版 BC 不含该算法，
    // 需显式引入完整版并注册替换（见 AIEditorApp.registerBouncyCastle）。版本与 sshj 传递依赖一致。
    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
    implementation("commons-net:commons-net:3.10.0")
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // 容器：解压 Alpine rootfs tar.gz（正确处理 symlink/hardlink/权限位）
    implementation("org.apache.commons:commons-compress:1.26.2")
    // xz 解压支持：commons-compress 的 XZCompressorInputStream 依赖此库（解压用户导入的 .tar.xz 镜像）
    implementation("org.tukaani:xz:1.10")

    // Termux 开源终端组件：terminal-emulator 负责 VT100/ANSI 解析与 PTY（自带 native .so），
    // terminal-view 是渲染用的 Android View。经 JitPack 分发（com.github.<user>.<repo> 坐标形式），
    // 避免自行实现终端模拟器。
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // Material Icons
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Markdown Renderer
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
    // Markdown Renderer — Code Syntax Highlighting
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
    // 语法高亮引擎（markdown-renderer-code 传递引入，显式声明以供 diff 视图直接使用）
    implementation("dev.snipme:highlights-jvm:1.1.0")

    // Core Android
    implementation("androidx.core:core:1.16.0")
    // WebView 文档起始注入（addDocumentStartJavaScript，用于内置浏览器动态数据捕获的 fetch/XHR/WS/SSE 插桩）
    implementation("androidx.webkit:webkit:1.13.0")
    // Testing
    testImplementation("junit:junit:4.13.2")
    // JSON（TableDataProvider / SqlDelightDataProvider 通用表转储 Provider 使用；main sourceSet 需要）
    implementation("org.json:json:20240303")
    // MigrationSchemaConsistencyTest：解析 Room 导出的 schema JSON 做迁移一致性校验（已随旧数据层删除）
    testImplementation("org.json:json:20240303")

    // ── 新数据层（data-layer-redesign）─ SQLDelight（设计文档 §2/§12）──
    // Android 驱动（AndroidSqliteDriver，L0 引擎，可插拔加密 factory 的明文实现）
    implementation("app.cash.sqldelight:android-driver:2.2.1")
    // 响应式查询（KVStore.observe 等 asFlow 扩展）
    implementation("app.cash.sqldelight:coroutines-extensions:2.2.1")
    // JVM 驱动（迁移黄金测试 / 数据保护测试用 NativeSqliteDriver，设计 §5.5）
    testImplementation("app.cash.sqldelight:sqlite-driver:2.2.1")
    // androidx-sqlite 桥接（PlainDriverFactory 的 FrameworkSQLiteOpenHelperFactory）
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ── 新数据层（data-layer-redesign）─ SQLDelight 6 库拓扑（设计文档 §4 / §12）──
// 核心 5 域各自独立 Database 类（独立版本链）+ infra 一个 Database 类承载全部 Store。
// 生成规则（SQLDelight 2.x）：Database 类在公共包 com.R.codecore.datalayer.sqldelight；
// 查询类/数据类在 packageName + 相对 srcDir 的目录路径（agent/ → .sqldelight.agent 等）。
// 文件布局：src/main/sqldelight/<域>/<域>/<文件>.sq（.sq 必须位于 srcDir 的子目录「包目录」）。
// dialect = sqlite 3.38：schema 编译期校验按 3.38；运行期由设备 SQLite 提供（语句均兼容 3.18+，
// 已避免 UPSERT 等 3.24+ 语法以兼容 targetSdk=28 / Android 9 的 SQLite 3.22）。
sqldelight {
    databases {
        create("AgentDb") {
            packageName.set("com.R.codecore.datalayer.sqldelight")
            srcDirs("src/main/sqldelight/agent")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.2.1")
        }
        create("CredentialsDb") {
            packageName.set("com.R.codecore.datalayer.sqldelight")
            srcDirs("src/main/sqldelight/credentials")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.2.1")
        }
        create("SettingsDb") {
            packageName.set("com.R.codecore.datalayer.sqldelight")
            srcDirs("src/main/sqldelight/settings")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.2.1")
        }
        create("WorkspaceDb") {
            packageName.set("com.R.codecore.datalayer.sqldelight")
            srcDirs("src/main/sqldelight/workspace")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.2.1")
        }
        create("T2iDb") {
            packageName.set("com.R.codecore.datalayer.sqldelight")
            srcDirs("src/main/sqldelight/t2i")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.2.1")
        }
        create("InfraDb") {
            packageName.set("com.R.codecore.datalayer.sqldelight")
            srcDirs("src/main/sqldelight/infra")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.2.1")
        }
    }
}
