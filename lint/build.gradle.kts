// 自定义 Lint 规则：把"UI 必须统一"这条约定变成编译期硬约束。
// 没有这一层，设计系统三个月内必然被绕过。
// 注意：deepcode-R 未使用 version catalog；Kotlin 插件版本由 settings 的 resolutionStrategy 统一管理，
// 这里不重复声明版本（重复声明会触发 "already on the classpath" 冲突）。
plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("com.android.tools.lint:lint-api:31.7.3")
    compileOnly("com.android.tools.lint:lint-checks:31.7.3")
}

// 不显式指定 JVM 版本，跟随项目默认 JDK，避免与 Kotlin 的 jvmTarget 不一致
java {
    sourceCompatibility = JavaVersion.current()
    targetCompatibility = JavaVersion.current()
}