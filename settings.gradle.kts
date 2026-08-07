pluginManagement {
    repositories {
        // 本地 Maven 代理优先：解决 Gradle Apache HC 客户端出口阻断导致的 ConnectTimeout 问题
        // 代理通过 curl（沙箱 socket 放行对象）中转拉取 Google/MavenCentral/Plugins/JitPack/Aliyun
        maven { url = uri("http://127.0.0.1:19099/google") }
        maven { url = uri("http://127.0.0.1:19099/central") }
        maven { url = uri("http://127.0.0.1:19099/plugins") }
        maven { url = uri("http://127.0.0.1:19099/aliyun") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application",
                "com.android.library",
                "com.android.dynamic-feature",
                "com.android.test" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "com.android.settings" ->
                    useModule("com.android.tools.build:gradle-settings-plugin:${requested.version}")
                "org.jetbrains.kotlin.android",
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.multiplatform" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.plugin.serialization" ->
                    useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
                "com.google.devtools.ksp" ->
                    useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
                "com.google.dagger.hilt.android" ->
                    useModule("com.google.dagger:hilt-android-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("http://127.0.0.1:19099/google") }
        maven { url = uri("http://127.0.0.1:19099/central") }
        maven { url = uri("http://127.0.0.1:19099/plugins") }
        maven { url = uri("http://127.0.0.1:19099/aliyun") }
        maven { url = uri("http://127.0.0.1:19099/jitpack") }
        google()
        mavenCentral()
    }
}
rootProject.name = "app"

include(":app")
include(":terminal-emulator")
include(":terminal-view")

