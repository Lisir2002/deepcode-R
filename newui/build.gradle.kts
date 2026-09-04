plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.core.deepcode.newui"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    // :newui 是独立设计系统库，不走 app 的 lintVital；放行常用 deprecation 检查即可。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-graphics")

    // 自适应导航（AppAdaptiveNav）：NavigationSuiteScaffold / 五断点
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

/**
 * 令牌生成（Style Dictionary / DTCG）：把 newui/tokens/**/*.tokens.json
 * （W3C DTCG 2025.10 格式）经 style-dictionary build 生成到
 * build/generated/designTokens/kotlin/{AppColors,AppSpacing,...}.kt。
 *
 * 依赖 node + npm（仓库根已有 node v24）。断网/无 node 时跳过并给出提示，
 * 不拉崩整个构建（生成产物可由已提交的 generated/ 兜底）。
 */
tasks.register<Exec>("generateDesignTokens") {
    group = "ui"
    description = "用 Style Dictionary 从 tokens/*.tokens.json 生成 Compose 令牌常量"
    workingDir(projectDir)
    val nodeBin = findNodeBinary()
    val styleDictBin = file("node_modules/style-dictionary/bin/style-dictionary.js").absolutePath
    if (nodeBin == null || !file(styleDictBin).exists()) {
        logger.warn("(newui) 未找到 node 或 style-dictionary，跳过令牌生成；使用已提交的 generated/ 产物。")
        enabled = false
        return@register
    }
    // 直接调本地 style-dictionary 二进制；npm exec 会把 --config 误吞成 npm 参数，故不用。
    commandLine(nodeBin, styleDictBin, "build", "--config", "style-dictionary.config.js")
}

// 编译前先产令牌；IDE 直接 compileDebugKotlin 时由该依赖兜底生成
afterEvaluate {
    tasks.configureEach {
        if (name.startsWith("compile") && (name.contains("Kotlin") || name.contains("Java"))) {
            if (name.endsWith("Kotlin") || name.startsWith("compileDebugKotlin")) {
                dependsOn("generateDesignTokens")
            }
        }
    }
}

/** 优先找 node 可执行路径（node / ~/.nvm/.../bin/node）。 */
fun findNodeBinary(): String? {
    val candidates = listOf(
        System.getenv("NODE_BIN"),
        "node",
        "/usr/local/bin/node",
        "/usr/bin/node",
        "/opt/homebrew/bin/node",
    )
    return candidates.firstOrNull { candidate ->
        candidate != null && runCatching {
            val p = ProcessBuilder(candidate, "--version")
                .redirectErrorStream(false).start()
            p.waitFor() == 0
        }.getOrDefault(false)
    }
}