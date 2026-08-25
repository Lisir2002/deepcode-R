package com.R.codecore.feature.agent.domain.container

import android.content.Context
import android.system.Os
import com.R.codecore.core.environment.EnvironmentDetector
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 负责把打进 assets 的 Alpine rootfs 与 PRoot 二进制安装到 App 私有目录。
 *
 * 支持双容器架构：arm64（默认，aarch64 rootfs，原生执行）与 x86_64（x86_64 rootfs，
 * 经 proot `-q` 注入静态 qemu-x86_64-static 转译）。proot/loader/libtalloc 等宿主侧
 * 二进制为 arm64 版，两架构共用。x86_64 额外安装 qemu-user-linux-arm64-x86_64 静态
 * 二进制（宿主 arm64 运行，翻译 x86_64 用户态指令）。
 */
@Singleton
class ContainerInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ContainerInstaller"
        @Volatile private var docsExtractedSession = false

        /** 从 assets 提取 SOP 标准作业到 ~/.rcodecore/sop/（D4-1，内置默认副本，每次启动全量覆盖）。 */
        @Volatile private var sopExtractedSession = false

        /** 从 assets 提取 SOP 标准作业资产到 ~/.rcodecore/sop/，使 App 升级后 SOP 随之更新。 */
        fun extractSop(context: Context) {
            if (sopExtractedSession) return
            val destDir = File(File(context.filesDir, "rcodecore"), "sop")
            destDir.mkdirs()
            runCatching {
                val sop = context.assets.list("sop") ?: return
                for (item in sop) {
                    val destFile = File(destDir, item)
                    context.assets.open("sop/$item").use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                sopExtractedSession = true
            }.onFailure {
                FileLogger.w(TAG, "提取内置 SOP 失败: ${it.message}", it)
            }
        }

        /** 从 assets 提取文档到 ~/.rcodecore/docs (内置使用指导) */
        fun extractDocs(context: Context) {
            if (docsExtractedSession) return
            val destDir = File(File(context.filesDir, "rcodecore"), "docs")
            destDir.mkdirs()
            runCatching {
                val docs = context.assets.list("docs") ?: return
                for (doc in docs) {
                    val destFile = File(destDir, doc)
                    context.assets.open("docs/$doc").use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                docsExtractedSession = true
            }.onFailure {
                FileLogger.w(TAG, "提取内置文档失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取内置提示词到 ~/.rcodecore/prompts/，每次启动全量覆盖，使 App 升级后提示词随之更新。
         *
         * 用户自定义覆盖放在 ~/.rcodecore/prompts.custom/（同名即覆盖），本方法不触碰该目录，
         * 故用户重写的片段不会被升级覆盖。参见 [com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider]。
         */
        fun extractPrompts(context: Context) {
            val destDir = File(File(context.filesDir, "rcodecore"), "prompts")
            destDir.mkdirs()
            runCatching {
                val prompts = context.assets.list("prompts") ?: return
                for (prompt in prompts) {
                    val destFile = File(destDir, prompt)
                    context.assets.open("prompts/$prompt").use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "提取内置提示词失败: ${it.message}", it)
            }
        }

        /**
         * 从 assets 提取自定义 git credential helper 到 ~/.rcodecore/git-credential-rcodecore 并赋可执行位。
         *
         * 经 [LinuxContainerEngine] 的 -b 绑定即容器内 /root/.rcodecore/git-credential-rcodecore，
         * 由 [LinuxContainerEngine.provisionIfNeeded] 在 `.gitconfig` 里登记为第二个 credential.helper，
         * 排在 `store` 之后兜底未登录（双保险）。helper 详行为见 assets/rcodecore/git-credential-rcodecore。
         *
         * 启动即提取、独立于 provisioning 成败：provisioning 失败时 git 没装上，helper 配置不存在也无所谓；
         * 一旦 git 装好且配置登记，helper 立即可用。提取失败仅告警不抛（helper 缺席仅导致未登录时无弹窗，
         * git 仍能裸跑报认证失败，不致命）。
         */
        fun extractCredentialHelper(context: Context) {
            val dest = File(File(context.filesDir, "rcodecore"), "git-credential-rcodecore")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("rcodecore/git-credential-rcodecore").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // 对所有用户赋可执行位（proot 进程以 App uid 运行，参照 [copyAsset] 的 0o111 模式）。
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "提取 git credential helper 失败: ${it.message}", it)
            }
        }

        /**
         * 与 assets 里 alpine-rootfs 版本对应的 apk 分支，用于拼镜像源地址。
         * 固定 v3.21：与 [INSTALL_VERSION]（alpine-3.21.3）一致；该版本 apk-tools 2.14 在 proot 下可靠。
         */
        const val ALPINE_BRANCH = "v3.21"

        /**
         * apk 镜像源；用阿里云国内镜像替代官方 dl-cdn，避免国外源过慢/被墙。
         *
         * 用 http 而非 https：Alpine minirootfs 不含 ca-certificates，容器内 apk 走自己的原生 TLS，
         * 无 CA 证书库会导致 HTTPS 握手失败并被 apk 误报成 "Permission denied"（首次安装直接卡死）。
         * apk 对索引与每个 .apk 都用 /etc/apk/keys 的签名独立校验，故 http 传输仍保证完整性。
         */
        const val ALPINE_MIRROR = "http://mirrors.aliyun.com/alpine"

        /**
         * 安装版本。换 rootfs / proot 或改安装逻辑时 +1，触发重新解压。
         * 与 assets 里实际放的 Alpine 版本保持一致以便排查。
         */
        private const val INSTALL_VERSION = "alpine-3.21.3-v6"

        /**
         * 内置 x86_64 容器的安装版本。与 [INSTALL_VERSION] 独立：x86_64 rootfs 是另一个
         * Alpine 发行（x86_64 架构），版本标记独立，避免与 arm64 互相覆盖触发误重装。
         */
        private const val INSTALL_VERSION_X86 = "alpine-3.21.3-x86-v1"
    }

    /** assets 内的 arm64 内置容器目录（aarch64 rootfs + arm64 proot 全套） */
    val ASSET_DIR: String = "container/arm"

    /** assets 内的 x86_64 内置容器目录（x86_64 rootfs + arm64 静态 qemu 转译器） */
    val ASSET_DIR_X86: String = "container/x86_64"

    /** 轻量探测某 asset 路径是否被打进当前 APK（对外保留以备扩展使用） */
    private fun assetExists(path: String): Boolean =
        context.assets.list(path.substringBeforeLast('/'))?.any { it == path.substringAfterLast('/') } == true

    val ASSET_PROOT: String get() = "$ASSET_DIR/proot"
    // Termux proot 的 loader 分离（靠 PROOT_LOADER 定位），且动态依赖 libtalloc / libandroid-shmem。
    val ASSET_LOADER: String get() = "$ASSET_DIR/loader"
    val ASSET_LOADER32: String get() = "$ASSET_DIR/loader32"
    val ASSET_LIBTALLOC: String get() = "$ASSET_DIR/libtalloc.so.2"
    val ASSET_LIBSHMEM: String get() = "$ASSET_DIR/libandroid-shmem.so"
    // 故意用中性的 .bin 后缀：AGP 的 asset 合并会把 .tar.gz/.tgz 当归档自动解压并改名，
    // 导致运行时 open("...tar.gz") 找不到文件。.bin 让它当普通二进制原样打包。
    val ASSET_ROOTFS: String get() = "$ASSET_DIR/alpine-rootfs.bin"

    /** x86_64 内置容器的 rootfs 资产（Alpine x86_64 minirootfs，gzip 压缩存为 .bin） */
    val ASSET_ROOTFS_X86: String get() = "$ASSET_DIR_X86/alpine-rootfs-x86_64.bin"

    /** x86_64 内置容器的 qemu 转译器资产（宿主 arm64 + 目标 x86_64，静态链接） */
    val ASSET_QEMU_X86: String get() = "$ASSET_DIR_X86/qemu-user-linux-arm64-x86_64"

    // —— x86_64 宿主（x86_64 模拟器）的「原生 proot」三件套 ——
    // Termux proot x86_64 版：proot + loader（64/32 位）+ 动态依赖 libtalloc/libandroid-shmem。
    // 与 arm64 宿主共用 x86_64 rootfs，但 proot 本体必须是宿主架构（arm64 宿主跑 x86_64 容器用
    // arm64 proot + `-q` 注入 qemu 转译；x86_64 宿主跑 x86_64 容器用 x86_64 proot 原生执行）。
    val ASSET_PROOT_X86: String get() = "$ASSET_DIR_X86/proot"
    val ASSET_LOADER_X86: String get() = "$ASSET_DIR_X86/loader"
    val ASSET_LOADER32_X86: String get() = "$ASSET_DIR_X86/loader32"
    val ASSET_LIBTALLOC_X86: String get() = "$ASSET_DIR_X86/libtalloc.so.2"
    val ASSET_LIBSHMEM_X86: String get() = "$ASSET_DIR_X86/libandroid-shmem.so"

    /** rootfs 解压根目录（内置 arm64） */
    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /** 内置 x86_64 容器的 rootfs 解压根目录（与 arm64 隔离，互不删除） */
    val rootfsX86Dir: File
        get() = File(context.filesDir, "rootfs-x86_64")

    /** x86_64 容器的 qemu 转译器可执行文件（宿主侧，proot `-q` 使用） */
    val qemuX86Bin: File
        get() = File(context.filesDir, "container/qemu/qemu-x86_64-static")

    /**
     * AI 配置数据根目录（skill 指令 + MCP 配置），固定在 app 私有 filesDir。
     *
     * 刻意**独立于 [rootfsDir]**：rootfs 在容器版本升级时会被整体删除重装（见 [installRootfsIfNeed]），
     * 而本目录承载用户数据，必须跨升级保留。它由 [com.R.codecore.feature.agent.domain.container.LinuxContainerEngine]
     * 绑定到容器内 `/root/.rcodecore`，故 AI / 终端看到的 `/root/.rcodecore` 实际落在这里。
     */
    val rcodecoreDir: File
        get() = File(context.filesDir, "rcodecore")

    /** PRoot 可执行文件路径（Termux 构建，含 statx，动态链接 libtalloc/libandroid-shmem） */
    val prootBin: File
        get() = File(context.filesDir, "container/bin/proot")

    /** PRoot 的 64/32 位 loader（Termux proot loader 分离，由 PROOT_LOADER/_32 指向）。 */
    val prootLoader: File
        get() = File(context.filesDir, "container/bin/loader")
    val prootLoader32: File
        get() = File(context.filesDir, "container/bin/loader32")

    /** proot 的动态依赖库目录（libtalloc.so.2 / libandroid-shmem.so），由 LD_LIBRARY_PATH 指向。 */
    val prootLibDir: File
        get() = File(context.filesDir, "container/lib")

    // —— x86_64 宿主（x86_64 模拟器）的「原生 proot」安装位置（与 arm64 proot 目录隔离）——
    /** x86_64 原生 proot 可执行文件（Termux x86_64 版，动态链接 linker64 + libtalloc/libandroid-shmem） */
    val prootX86Bin: File
        get() = File(context.filesDir, "container/bin_x86/proot")

    /** x86_64 原生 proot 的 64 位 loader（PROOT_LOADER 指向） */
    val prootX86Loader: File
        get() = File(context.filesDir, "container/bin_x86/loader")

    /** x86_64 原生 proot 的 32 位 loader（PROOT_LOADER_32 指向，x86_64 rootfs 内基本用不到，随包部署保底） */
    val prootX86Loader32: File
        get() = File(context.filesDir, "container/bin_x86/loader32")

    /** x86_64 原生 proot 的动态依赖库目录（LD_LIBRARY_PATH 指向） */
    val prootX86LibDir: File
        get() = File(context.filesDir, "container/lib_x86")

    /** 按 [profile] 返回对应架构的 proot 可执行文件：x86_64 容器 + x86_64 宿主 → x86_64 原生 proot；其余 → arm64 proot。 */
    fun prootBinFor(profile: ContainerProfile): File =
        if (profile.arch == ContainerArch.X86_64 && EnvironmentDetector.hostIsX86_64) prootX86Bin
        else prootBin

    /** 按 [profile] 返回对应架构的 proot loader（PROOT_LOADER 用）。 */
    fun prootLoaderFor(profile: ContainerProfile): File =
        if (profile.arch == ContainerArch.X86_64 && EnvironmentDetector.hostIsX86_64) prootX86Loader
        else prootLoader

    /** 按 [profile] 返回对应架构的 proot 32 位 loader（PROOT_LOADER_32 用）。 */
    fun prootLoader32For(profile: ContainerProfile): File =
        if (profile.arch == ContainerArch.X86_64 && EnvironmentDetector.hostIsX86_64) prootX86Loader32
        else prootLoader32

    /** 按 [profile] 返回对应架构的 proot 动态依赖库目录（LD_LIBRARY_PATH 用）。 */
    fun prootLibDirFor(profile: ContainerProfile): File =
        if (profile.arch == ContainerArch.X86_64 && EnvironmentDetector.hostIsX86_64) prootX86LibDir
        else prootLibDir

    /** PRoot 在 Android 上必须的临时目录（Android 没有 /tmp） */
    val prootTmpDir: File
        get() = File(context.cacheDir, "proot_tmp")

    /** 标记文件，内容是已安装的版本号（内置 arm64） */
    private val installedMarker: File
        get() = File(rootfsDir, ".installed")

    /** 检查 rootfs 与 proot 是否已按当前版本安装就绪（内置 arm64） */
    fun isInstalled(): Boolean {
        if (!prootBin.exists() || !rootfsDir.isDirectory) return false
        val marker = installedMarker
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION
    }

    /** 检查内置 x86_64 容器是否就绪：rootfs 版本标记 + 宿主对应架构的 proot 到位即可。
     *  不把 qemuX86Bin.exists 作为硬条件——proot 进程仍占用旧 qemu 二进制导致 Text file busy
     *  时 qemux 可稍后由 deployQemuX86() 重试部署；若这里把它绑进 isInstalled，会让系统
     *  误以为整个 rootfs 未装而重复解压，反而更容易撞上正在运行的 qemu 转译进程。 */
    fun isInstalledX86(): Boolean {
        // x86_64 容器在 x86_64 宿主需 x86_64 原生 proot；arm64 宿主跑 x86_64 容器（qemu 转译）仍需 arm64 proot。
        // 两套 proot 由 installRootfsX86 一并部署，故任一存在即视为 proot 侧就绪。
        if ((!prootBin.exists() && !prootX86Bin.exists()) || !rootfsX86Dir.isDirectory) return false
        val marker = File(rootfsX86Dir, ".installed")
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION_X86
    }

    /**
     * 若未安装（或版本不匹配）则从 assets 解压安装。幂等，可在每次执行命令前调用。
     *
     * [onProgress] 在真正解压/部署的各阶段被回调以更新 [ContainerInitState]；已安装的快路径不会调用。
     */
    suspend fun installRootfsIfNeed(
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext

        FileLogger.i(TAG, "开始安装容器 rootfs（版本 $INSTALL_VERSION）")

        // 版本不匹配时清掉旧的，保证干净安装
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        extractRootfs(onProgress)
        configureResolvConf(rootfsDir)
        configureApkRepositories(rootfsDir)
        prootTmpDir.mkdirs()

        installedMarker.writeText(INSTALL_VERSION)
        FileLogger.i(TAG, "容器 rootfs 安装完成")
    }

    /**
     * 安装内置 x86_64 容器：proot（arm64 与 x86_64 两套，按宿主架构选用）+ 静态 qemu 转译器
     * （仅 arm64 宿主需要）+ x86_64 rootfs + DNS/apk 源。
     */
    private suspend fun installRootfsX86(
        onProgress: (ContainerInitState) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (isInstalledX86()) return@withContext

        FileLogger.i(TAG, "开始安装 x86_64 容器 rootfs（版本 $INSTALL_VERSION_X86）")

        if (rootfsX86Dir.exists()) rootfsX86Dir.deleteRecursively()
        rootfsX86Dir.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        // x86_64 宿主（x86_64 模拟器）需要 x86_64 原生 proot 才能执行 x86_64 rootfs。
        // arm64 宿主跑 x86_64 容器（qemu 转译）用 arm64 proot，本套部署后闲置但无害（体积 ~300KB）。
        installProotX86()
        // 静态 qemu 转译器（宿主 arm64）：arm64 宿主跑 x86_64 容器时 proot -q 注入；
        // copyAsset 内部已做原子 rename + IOException 捕获，旧 qemu 仍被占用时不会崩。
        deployQemuX86()
        extractRootfsTo(rootfsX86Dir, context.assets.open(ASSET_ROOTFS_X86), null, onProgress)
        configureResolvConf(rootfsX86Dir)
        configureApkRepositories(rootfsX86Dir)
        prootTmpDir.mkdirs()

        File(rootfsX86Dir, ".installed").writeText(INSTALL_VERSION_X86)
        FileLogger.i(TAG, "x86_64 容器 rootfs 安装完成")
    }

    /**
     * 按 [profile] 返回 rootfs 目录：内置 arm64 仍是 [rootfsDir]（不动），内置 x86_64 用
     * [rootfsX86Dir]，自定义本地镜像用 filesDir/rootfs_<id>。
     * 远程 SSH profile 无本地 rootfs，返回一个占位目录（不会被使用/创建）。
     * 目录隔离——各架构/自定义互不共享、互不删除，切回时其 rootfs 原封不动。
     */
    fun rootfsDirFor(profile: ContainerProfile): File =
        when {
            !profile.isBuiltin -> File(context.filesDir, "rootfs_${profile.id}")
            profile.arch == ContainerArch.X86_64 -> rootfsX86Dir
            else -> rootfsDir
        }

    /** 自定义镜像的已安装标记（独立于内置 .installed，避免混淆）。 */
    private fun customInstalledMarker(profile: ContainerProfile): File =
        File(rootfsDirFor(profile), ".installed_custom")

    /** 按 [profile] 判断是否已安装就绪：内置按架构走各自版本校验，自定义本地看目录与标记，远程 SSH 恒就绪。 */
    fun isInstalledFor(profile: ContainerProfile): Boolean =
        when {
            profile.isBuiltin && profile.arch == ContainerArch.X86_64 -> isInstalledX86()
            profile.isBuiltin -> isInstalled()
            profile.rootfsSource is RootfsSource.RemoteSsh -> true
            else -> prootBin.exists() && rootfsDirFor(profile).isDirectory && customInstalledMarker(profile).exists()
        }

    /**
     * 按 [profile] 解压安装 rootfs。内置按架构分流（arm64 走 [installRootfsIfNeed]，x86_64 走
     * [installRootfsX86]，均含 proot/resolv/apk 源）；自定义本地镜像只解压 tar.gz + 装 proot，
     * **不写 resolv.conf / apk 源、不 provision**——镜像源与所需工具由用户自行在容器内处理。
     * 远程 SSH profile 无本地 rootfs，直接返回（命令执行走 [RemoteSshEngine]，不需本地 rootfs）。
     */
    suspend fun installRootfsIfNeed(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledFor(profile)) return@withContext

        if (profile.isBuiltin) {
            if (profile.arch == ContainerArch.X86_64) installRootfsX86(onProgress)
            else installRootfsIfNeed(onProgress)
            return@withContext
        }

        // 远程 SSH profile：无本地 rootfs 可解压，视为就绪。
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext

        val dest = rootfsDirFor(profile)
        FileLogger.i(TAG, "安装自定义容器 rootfs：${profile.id} -> ${dest.absolutePath}")
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()

        onProgress(ContainerInitState.DeployingProot)
        installProot()
        when (val src = profile.rootfsSource) {
            is RootfsSource.Asset -> context.assets.open("${ASSET_DIR}/${src.path}").use {
                // Asset 自定义镜像：不写死格式，按 magic 嗅探，兼容 gzip/xz 两种打包方式。
                extractRootfsTo(dest, it, CompressedFormat.AUTO, onProgress)
            }
            is RootfsSource.LocalFile -> {
                val uri = android.net.Uri.parse(src.uri)
                // 扩展名只作参考（用户可能把文件改名），真实格式以 magic 嗅探为准，
                // 避免用户把 .tar.gz 重命名为 .xz（或反之）导致解压失败。
                context.contentResolver.openInputStream(uri)?.use {
                    extractRootfsTo(dest, it, CompressedFormat.AUTO, onProgress)
                } ?: FileLogger.w(TAG, "打开导入的 rootfs uri 失败: ${src.uri}")
            }
            is RootfsSource.RemoteSsh -> { /* 无本地 rootfs，上面已提前 return */ }
        }
        prootTmpDir.mkdirs()
        customInstalledMarker(profile).writeText("custom")
        FileLogger.i(TAG, "自定义容器 rootfs 安装完成：${profile.id}")
    }

    /** 删除自定义 profile 的 rootfs 目录（删 profile 时调用）。内置 rootfs 不可删，远程 SSH 无 rootfs 可删。 */
    fun deleteCustomRootfs(profile: ContainerProfile) {
        if (profile.isBuiltin) return
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return
        rootfsDirFor(profile).deleteRecursively()
    }

    /**
     * 重置内置 Alpine 容器（arm64）：删除其 rootfs 目录（含 .installed / .provisioned 标记），
     * 下次 [ensureInstalled] 会重新解压 + provision。供内置镜像「重置」按钮调用。
     */
    fun resetBuiltinRootfs() {
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
    }

    /** 重置内置 x86_64 容器：删除其 rootfs 目录，下次初始化会重新解压 + 部署 qemu 转译器。 */
    fun resetBuiltinX86Rootfs() {
        if (rootfsX86Dir.exists()) rootfsX86Dir.deleteRecursively()
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            extractDocs(context)
            extractSop(context)
            extractCredentialHelper(context)
        }
    }

    /** 从 assets 提取文档到 ~/.rcodecore/docs (内置使用指导) */
    fun extractDocs() = extractDocs(context)

    /** 从 assets 提取 SOP 标准作业资产到 ~/.rcodecore/sop/（D4-1）。 */
    fun extractSop() = extractSop(context)

    /** 从 assets 提取 git credential helper 到 ~/.rcodecore/git-credential-rcodecore 并赋可执行位。 */
    fun extractCredentialHelper() = extractCredentialHelper(context)

    /** 从 assets 复制 proot 全套（二进制 + loader + 动态依赖库）到私有目录并赋权限 */
    private fun installProot() {
        // 二进制与 loader：需可执行
        copyAsset(ASSET_PROOT, prootBin, executable = true)
        copyAsset(ASSET_LOADER, prootLoader, executable = true)
        copyAsset(ASSET_LOADER32, prootLoader32, executable = true)
        // 动态依赖库：放到 lib 目录，由 LD_LIBRARY_PATH 指向；可读即可（给可执行位无害）
        copyAsset(ASSET_LIBTALLOC, File(prootLibDir, "libtalloc.so.2"), executable = true)
        copyAsset(ASSET_LIBSHMEM, File(prootLibDir, "libandroid-shmem.so"), executable = true)
    }

    /** 从 assets 复制 x86_64 宿主用的「原生 proot」三件套（x86_64 模拟器场景）。 */
    private fun installProotX86() {
        copyAsset(ASSET_PROOT_X86, prootX86Bin, executable = true)
        copyAsset(ASSET_LOADER_X86, prootX86Loader, executable = true)
        copyAsset(ASSET_LOADER32_X86, prootX86Loader32, executable = true)
        copyAsset(ASSET_LIBTALLOC_X86, File(prootX86LibDir, "libtalloc.so.2"), executable = true)
        copyAsset(ASSET_LIBSHMEM_X86, File(prootX86LibDir, "libandroid-shmem.so"), executable = true)
    }

    /** 把单个 asset 复制到目标文件，按需赋「对所有用户」的可执行位。
     *
     *  为避免「重置后立即点初始化」撞上 proot / qemu 仍持有旧二进制的 inode（Linux 写打开
     *  运行中二进制会抛 ETXTBSY / Text file busy，直接崩 app），这里做 4 层保护：
     *   1. 快路径：dest 已存在且 asset 字节长度一致就跳过（避免无谓写入）。
     *   2. 原子替换：先写到 dest.tmp，再 renameTo 覆盖——in-use 进程继续跑旧 inode，不报错。
     *   3. 兜底：rename 或 open 仍然 IOException（极少数厂商内核把 rename 也当 busy），
     *      就尝试 dest.renameTo(dest.bak) 把旧文件移走，再写新文件；成功与否都不崩。
     *   4. 所有 IO 异常都降级为 FileLogger.w，不抛出、不中止初始化。
     */
    private fun copyAsset(assetPath: String, dest: File, executable: Boolean) {
        dest.parentFile?.mkdirs()

        // 1) 快路径：长度一致就视为已部署（asset 打包时版本不可变，这足够做幂等判定）
        runCatching {
            context.assets.openFd(assetPath).use { afd ->
                if (dest.exists() && dest.length() == afd.length) {
                    if (executable && !dest.canExecute()) dest.setExecutable(true, false)
                    return
                }
            }
        }

        // 2) 原子替换：写到 .tmp 再 rename
        val tmp = File(dest.parentFile, "${dest.name}.tmp.${System.currentTimeMillis()}")
        val movedOld = File(dest.parentFile, "${dest.name}.bak")
        var wroteTmp = false
        try {
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            wroteTmp = true
            if (executable) tmp.setExecutable(true, false)

            if (!tmp.renameTo(dest)) {
                // 3) 兜底：先尝试移走旧文件再 rename 一次（极少数 ROM 对覆盖 in-use inode 不友好）
                runCatching { if (dest.exists()) dest.renameTo(movedOld) }
                if (!tmp.renameTo(dest)) {
                    // 最终兜底：逐字节拷贝覆盖；Text file busy 不崩，记为警告并返回
                    FileLogger.w(TAG, "copyAsset: rename 失败，退化为逐字节覆盖（可能失败但不抛：${dest.absolutePath}）")
                    runCatching {
                        context.assets.open(assetPath).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }.onFailure { t ->
                        FileLogger.w(TAG, "copyAsset fallback copy 也失败: ${dest.absolutePath}, err=${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            // 4) 任何异常只记日志，不崩；重置/并发/低内存都不该让 app 因 asset 拷贝死
            FileLogger.w(TAG, "copyAsset 失败 (asset=$assetPath -> dest=${dest.absolutePath}): ${t.message}")
        } finally {
            if (wroteTmp && tmp.exists()) tmp.delete()
            // .bak 不主动删，下次 reset 会整体清目录
        }
        if (executable && dest.exists() && !dest.canExecute()) {
            if (!dest.setExecutable(true, false)) {
                FileLogger.w(TAG, "setExecutable 返回 false: ${dest.absolutePath}")
            }
        }
    }

    /** 单独幂等部署 qemu-x86_64-static，供重置/初始化/确保容器就绪随时重试而不重解压 rootfs。 */
    fun deployQemuX86() {
        copyAsset(ASSET_QEMU_X86, qemuX86Bin, executable = true)
    }

    /** 解压 alpine-minirootfs，自动按流头部 magic 识别 gzip/xz 格式 */
    private fun extractRootfs(onProgress: (ContainerInitState) -> Unit) {
        context.assets.open(ASSET_ROOTFS).use { rawIn ->
            // 不预传 format，改由 extractRootfsTo 内部 peek magic 字节嗅探，
            // 兼容旧版本（存量 gzip 镜像）与新版本（xz 更高压缩比）的 APK。
            extractRootfsTo(rootfsDir, rawIn, null, onProgress)
        }
    }

    /** 镜像压缩格式；[CompressedFormat.AUTO] 委托给 magic 嗅探（[detectFormat]）。 */
    enum class CompressedFormat { GZIP, XZ, AUTO }

    /**
     * 读取流首字节按 magic 嗅探压缩格式（支持 mark/reset 的流最佳，不消费数据）。
     * gzip  magic = 1F 8B
     * xz    magic = FD 37 7A 58 5A 00 ("\u00fd7zXZ\u0000")
     * 未命中时默认 [GZIP]，与项目早期 Alpine 镜像的默认压缩格式保持兼容。
     */
    private fun detectFormat(input: java.io.InputStream): CompressedFormat {
        val header = ByteArray(6)
        input.mark(header.size)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        return when {
            read >= 6 &&
                header[0] == 0xFD.toByte() &&
                header[1] == 0x37.toByte() &&
                header[2] == 0x7A.toByte() &&
                header[3] == 0x58.toByte() &&
                header[4] == 0x5A.toByte() &&
                header[5] == 0x00.toByte() -> CompressedFormat.XZ
            read >= 2 &&
                header[0] == 0x1F.toByte() &&
                header[1] == 0x8B.toByte() -> CompressedFormat.GZIP
            else -> CompressedFormat.GZIP
        }
    }

    /**
     * 把 tar.gz / tar.xz 流解压到 [destDir]，正确处理目录/文件/符号链接/硬链接与权限位。
     * 内置 Alpine（[extractRootfs] 传 assets 流）与用户自定义镜像（[installRootfsIfNeed] 传 content uri 流）共用。
     *
     * [format] = null / [CompressedFormat.AUTO] 时按流首字节 magic 嗅探；
     * 若调用方已明确知道格式（例如从扩展名解析），可显式传入以跳过嗅探。
     */
    fun extractRootfsTo(
        destDir: File,
        input: java.io.InputStream,
        format: CompressedFormat? = CompressedFormat.AUTO,
        onProgress: (ContainerInitState) -> Unit = {}
    ) {
        // 为不支持 mark 的原始流（如 assets/ 直出 AssetInputStream、部分 contentResolver 流）
        // 包一层 BufferedInputStream 保证 markSupported，peek 6 字节后可正确 reset 回去。
        val buffered = if (input.markSupported()) input else java.io.BufferedInputStream(input).apply {
            // BufferedInputStream 默认 buffer >= 8KB，已足以覆盖 magic 嗅探，但显式指定 marklimit 更稳妥。
            mark(64)
        }
        val resolved = when {
            format == null || format == CompressedFormat.AUTO -> detectFormat(buffered)
            else -> format
        }
        FileLogger.i(
            TAG,
            "extractRootfsTo: resolved format=$resolved (requested format=$format, dest=${destDir.absolutePath})"
        )
        var processed = 0
        val decompressed = when (resolved) {
            CompressedFormat.GZIP, CompressedFormat.AUTO -> GZIPInputStream(buffered)
            CompressedFormat.XZ -> XZCompressorInputStream(buffered)
        }
        decompressed.use { decompIn ->
            TarArchiveInputStream(decompIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    extractEntry(destDir, tarIn, entry)
                    processed++
                    onProgress(ContainerInitState.ExtractingRootfs(processed))
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    private fun extractEntry(
        destDir: File,
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry
    ) {
        val outFile = File(destDir, entry.name)

        // 防 zip-slip：确保解压目标落在 destDir 内
        val canonicalRoot = destDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator) &&
            outFile.canonicalPath != canonicalRoot
        ) {
            FileLogger.w(TAG, "跳过越界条目: ${entry.name}")
            return
        }

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                // symlink 的目标可能是相对/绝对路径，原样创建（在容器内由 proot 解析）
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    .onFailure { FileLogger.w(TAG, "symlink 失败 ${entry.name} -> ${entry.linkName}: ${it.message}") }
            }

            entry.isLink -> {
                // 硬链接：linkName 指向 tar 内已解压的另一文件
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(target.absolutePath, outFile.absolutePath) }
                    .onFailure {
                        // 退化为复制，保证文件存在
                        FileLogger.w(TAG, "hardlink 失败 ${entry.name} -> ${entry.linkName}，改为复制: ${it.message}")
                        runCatching { target.copyTo(outFile, overwrite = true) }
                    }
            }

            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tarIn.copyTo(it) }
                applyMode(outFile, entry.mode)
            }

            else -> FileLogger.d(TAG, "忽略不支持的条目类型: ${entry.name}")
        }
    }

    /** 按 tar entry 的 mode 设置可执行位（owner 有 x 位则对所有人开放执行） */
    private fun applyMode(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0 // 0100
        if (ownerExecutable) {
            file.setExecutable(true, false)
        }
        file.setReadable(true, false)
        // owner 写位
        if ((mode and 0b010_000_000) != 0) file.setWritable(true, false)
    }

    /**
     * 写入容器内 DNS，否则 apk/npm 等联网操作会因无法解析域名而失败。
     * 用阿里云公共 DNS：国内解析更快/更稳，8.8.8.8 在部分网络环境会被拦截。
     */
    private fun configureResolvConf(targetRootfs: File = rootfsDir) {
        val etc = File(targetRootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
    }

    /**
     * 写入容器内 apk 源为阿里云国内镜像（[ALPINE_MIRROR]），替代 minirootfs 自带的官方 dl-cdn 源，
     * 否则首次 `apk add` 在国内会极慢或超时。启用 main + community 两个仓库。
     *
     * 用 http 的原因见 [ALPINE_MIRROR] 注释。provision 流程会再幂等覆盖一次以兜底存量（已解压旧 rootfs）设备。
     */
    private fun configureApkRepositories(targetRootfs: File = rootfsDir) {
        val apkDir = File(targetRootfs, "etc/apk").apply { mkdirs() }
        File(apkDir, "repositories").writeText(
            "$ALPINE_MIRROR/$ALPINE_BRANCH/main\n" +
                "$ALPINE_MIRROR/$ALPINE_BRANCH/community\n"
        )
    }
}
