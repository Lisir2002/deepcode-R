package com.R.codecore.feature.agent.domain.container

import com.R.codecore.feature.terminal.data.bundle.TerminalBundleId

/**
 * 容器初始化的实时进度状态。由 [LinuxContainerEngine.initProgress] 暴露，供终端页 / 终端设置页 / AI 页等所有
 * 初始化入口共享同一份进度（engine 为 @Singleton）。StateFlow 自动 conflation，高频更新只保留最新值。
 *
 * 改造后把原来的 monolithic「InstallingPackages」细化到 bundle 级，
 * 这样终端设置页能给每个 bundle 的卡片显示独立进度。
 */
sealed interface ContainerInitState {
    /** 尚未开始初始化。 */
    data object Idle : ContainerInitState

    /** 正在解压 Alpine rootfs，[processed] 为已处理的 tar 条目数。 */
    data class ExtractingRootfs(val processed: Int) : ContainerInitState

    /** 正在部署 proot 二进制 / loader / 动态依赖库。 */
    data object DeployingProot : ContainerInitState

    /**
     * rootfs + proot 已就绪（物理环境 OK）。
     * 此时可以开始按需装单个 bundle，不再是"强制全装 8 个包"。
     * 终端页 / 设置页 / AI Run 入口据此切到 "Bundle 按需安装" 阶段。
     *
     * [migratedFromLegacyProvisioned] = true 表示检测到旧版 .provisioned 标记、
     * 已自动把 7 个 bundle 标为已安装（存量用户升级零感知）。
     */
    data class Ready(val migratedFromLegacyProvisioned: Boolean = false) : ContainerInitState

    /**
     * 正在安装某单个 Bundle。
     *
     * UI（终端设置页的 Bundle 卡片）订阅 TerminalBundleRepository.states 作为主状态；
     * 这里的状态主要供**首屏 Loading/全局初始化进度**用——当首进终端页触发"初始化环境"时，
     * 除了 rootfs+proot，通常还会紧接着"安装 AI 推荐组合（5 个 bundle）"，本字段给出整体轮询反馈。
     *
     * @param bundleId 正在装的 bundle；null 表示正在装自定义 apk 包 / 索引刷新 / 镜像配置。
     * @param line apk 输出行（可为 null）。
     */
    data class BundleInstalling(
        val bundleId: TerminalBundleId?,
        val line: String? = null
    ) : ContainerInitState

    /**
     * 卸载单个 Bundle / 自定义包中。
     *
     * @param bundleId 正在卸的 bundle；null 表示正在卸载自定义 apk 包（与 BundleInstalling.bundleId 语义对齐）。
     */
    data class BundleUninstalling(
        val bundleId: TerminalBundleId?
    ) : ContainerInitState

    /** 初始化失败，[reason] 为原因。 */
    data class Failed(val reason: String) : ContainerInitState
}
