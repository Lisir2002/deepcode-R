package com.core.deepcode.feature.agent.domain.container

import com.core.deepcode.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.Serializable

/**
 * 一个可切换的容器配置：镜像来源 + shell 路径 + 额外 proot 绑定/参数。
 *
 * 内置 [BUILTIN_ALPINE] 描述现有 Alpine rootfs（来自 assets），其行为与改动前逐字等价。
 * 用户自定义 profile 通过导入 tar.gz 提供 rootfs，只保证能起 shell 跑命令——不 provision、
 * 不接管镜像源与包管理，所需工具由用户自行在容器内安装。
 *
 * 远程 SSH profile（[mode] == [ExecutionMode.REMOTE_SSH]）不导入本地 rootfs，而是绑定一个
 * 工作区已配置的 SSH 通道（[RootfsSource.RemoteSsh]），命令执行走 [RemoteSshEngine]。
 */
/**
 * 容器 rootfs 的 CPU 架构。
 *
 * - [ARM64]：aarch64 rootfs，proot 直接执行（真机 arm64 上的默认/快速路径）。
 * - [X86_64]：x86_64 rootfs，容器内所有进程经宿主侧静态 `qemu-x86_64-static`（proot `-q`）转译执行。
 *   主要用于让 Android SDK（官方只发 x86_64 的 aapt2/zipalign 等）成为容器内的"原生环境"，零 wrapper。
 */
@Serializable
enum class ContainerArch { ARM64, X86_64 }

@Serializable
data class ContainerProfile(
    val id: String,
    val name: String,
    val rootfsSource: RootfsSource,
    /** 自定义镜像用的 shell（如 /bin/sh 或 /bin/bash）；内置忽略，走 provision 后的 bash/ash 选择。 */
    val shellPath: String?,
    /** 额外 -b 绑定，如 ["/sdcard:/mnt/sdcard"]，逐项作为 `-b <binding>` 拼进 proot argv。 */
    val extraBindings: List<String> = emptyList(),
    /** 额外 proot 参数，原样追加到基础 argv（如 ["-k","..."]）。 */
    val extraArgs: List<String> = emptyList(),
    val isBuiltin: Boolean,
    /** 该 profile 的执行模式：本地 PRoot 容器 or 远程 SSH。选中时据此切全局 [ExecutionMode]。 */
    val mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
    /** rootfs 架构。默认 [ContainerArch.ARM64]，兼容旧版反序列化（无此字段的存量 JSON 自动取默认）。 */
    val arch: ContainerArch = ContainerArch.ARM64
) {
    companion object {
        const val BUILTIN_ID = "builtin-alpine"
        const val BUILTIN_X86_ID = "builtin-alpine-x86"

        /** 内置 Alpine (arm64) profile：镜像来自 assets，复用现有安装/provision 全流程。 */
        val BUILTIN_ALPINE = ContainerProfile(
            id = BUILTIN_ID,
            name = "内置 Alpine (arm64)",
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = null,
            isBuiltin = true
        )

        /**
         * 内置 Alpine (x86_64) profile：镜像来自 assets（[ContainerArch.X86_64]），
         * 启动时经 proot `-q <qemu-x86_64-static>` 全容器转译。与 [BUILTIN_ALPINE] 自由切换，
         * 共享同一份宿主侧 workspace / ~/.deepcode 数据目录。
         */
        val BUILTIN_ALPINE_X86 = ContainerProfile(
            id = BUILTIN_X86_ID,
            name = "内置 Alpine (x86_64)",
            rootfsSource = RootfsSource.Asset("alpine-rootfs-x86_64.bin"),
            shellPath = null,
            isBuiltin = true,
            arch = ContainerArch.X86_64
        )
    }
}

@Serializable
sealed interface RootfsSource {
    /** 内置：assets 里的 rootfs 文件（[ContainerProfile.BUILTIN_ALPINE] 用）。 */
    @Serializable
    data class Asset(val path: String) : RootfsSource

    /** 用户导入的 tar.gz，经 content uri 引用，解压到 filesDir/rootfs_<id>。 */
    @Serializable
    data class LocalFile(val uri: String) : RootfsSource

    /** 远程 SSH：绑定工作区已配置的 SSH 通道（connectionId）+ 远程工作区路径，不导入本地 rootfs。 */
    @Serializable
    data class RemoteSsh(
        val connectionId: String,
        val remoteWorkspacePath: String
    ) : RootfsSource
}
