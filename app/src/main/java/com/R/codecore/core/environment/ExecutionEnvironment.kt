package com.R.codecore.core.environment

import android.os.Build

/**
 * 程序运行环境（真机 / 模拟器 / 虚拟机）抽象，作为所有「真机绑定」适配的唯一入口。
 *
 * 设计背景（见 docs/plan-docs/emulator-support-design.md）：产品从「真机 arm64 专用」平滑演进到
 * 「真机 + 虚拟环境均可用」，用本层把三类正交问题归一化：
 *
 * 1. 打包 ABI（arm64-v8a + x86_64 双 ABI 打入同一 APK，安装期由系统按设备 ABI 自动选用）；
 * 2. 容器 rootfs / proot 架构（按宿主架构选择原生执行 or 经 qemu 转译）；
 * 3. 无容器降级路由（AI 核心不依赖容器，仍可用）。
 *
 * **安全边界**：探测仅用于适配与降级，绝不用于授权/安全判断——虚拟环境不应被信任为安全边界。
 */
enum class ExecutionEnvironment(val isEmulator: Boolean) {
    /** 真机（arm64 宿主）。 */
    REAL_DEVICE_ARM64(false),

    /** arm64 系统镜像的模拟器/虚拟机（宿主即 arm64，容器/终端能力与真机一致）。 */
    EMULATOR_ARM64(true),

    /** x86_64 系统镜像的模拟器/虚拟机（宿主 x86_64，容器走 x86_64 原生 proot + x86_64 rootfs）。 */
    EMULATOR_X86_64(true),

    /** 其它（无 arm64/x86_64 宿主，或探测未知）——容器不可用，走降级。 */
    VM_OTHER(true)
}

/**
 * 环境探测单例。全部基于公开 API（[Build] 字段），不依赖隐藏 `SystemProperties`，
 * 任何一条信号命中即判定为模拟器/虚拟机，保证各家 ROM / 模拟器镜像都能覆盖。
 */
object EnvironmentDetector {

    /** 是否为模拟器/虚拟机：`ro.kernel.qemu` 不可见时退化为 fingerprint/product 关键字判定。 */
    private val isEmulator: Boolean by lazy {
        qemuProp() ||
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emu", ignoreCase = true) ||
            Build.PRODUCT?.contains("emulator", ignoreCase = true) == true ||
            Build.PRODUCT?.contains("sdk_gphone", ignoreCase = true) == true ||
            Build.PRODUCT?.contains("emu", ignoreCase = true) == true
    }

    /** 宿主是否为 arm64（真机 arm64 / arm64 模拟器镜像）。 */
    val hostIsArm64: Boolean get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /** 宿主是否为 x86_64（x86_64 模拟器镜像，如 sdk_gphone_x86_64）。 */
    val hostIsX86_64: Boolean get() = Build.SUPPORTED_ABIS.any { it == "x86_64" }

    /** 宿主是否有对应架构的 proot 可执行（决定容器/终端能否原生运行）。 */
    val containerRunnable: Boolean get() = hostIsArm64 || hostIsX86_64

    /** 探测当前环境。 */
    fun detect(): ExecutionEnvironment = when {
        !isEmulator -> ExecutionEnvironment.REAL_DEVICE_ARM64
        hostIsX86_64 -> ExecutionEnvironment.EMULATOR_X86_64
        hostIsArm64 -> ExecutionEnvironment.EMULATOR_ARM64
        else -> ExecutionEnvironment.VM_OTHER
    }

    /**
     * 默认内置容器 profile id：x86_64 宿主自动落到 x86_64 内置容器，其余回落 arm64 内置。
     * 供「首次启动未持久化 profile」时选用（见 ContainerSettingsRepository 与 LinuxContainerEngine）。
     */
    fun defaultProfileId(): String =
        if (hostIsX86_64) com.R.codecore.feature.agent.domain.container.ContainerProfile.BUILTIN_X86_ID
        else com.R.codecore.feature.agent.domain.container.ContainerProfile.BUILTIN_ID

    /**
     * 安全读取 `ro.kernel.qemu`（最可靠的模拟器信号）。该属性走隐藏 API [android.os.SystemProperties]，
     * 编译期依赖 @hide，运行期在多数 ROM 可反射访问；不可用时返回 false，由其它信号兜底。
     */
    private fun qemuProp(): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java, String::class.java)
        get.invoke(null, "ro.kernel.qemu", "0") as? String == "1"
    } catch (e: Throwable) {
        false
    }
}
