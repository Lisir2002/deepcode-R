package com.R.codecore.feature.agent.domain.container.progress

import androidx.compose.runtime.Immutable
import com.R.codecore.feature.terminal.data.bundle.TerminalBundleId

/** 安装流程总阶段。权重硬编码基准（国内镜像历史 100 次均值），后续埋点校准。 */
enum class InstallPhase(val weight: Float) {
    /** 依赖图查询 → 并行预取 curl → apk 自己 fetch（fallback）。占 45%。 */
    DOWNLOAD(weight = 0.45f),
    /** (N/T) Installing → 解压 → post-install trigger。占 50%。 */
    INSTALL(weight = 0.50f),
    /** git config / .bashrc PS1 / profile 等自定义脚本。占 5%。 */
    POST_HOOK(weight = 0.05f),
    /** 终态 success。 */
    DONE(weight = 0f),
    /** 终态 failed。 */
    FAILED(weight = 0f),
    ;

    val isTerminal: Boolean get() = this == DONE || this == FAILED
}

/** 进度条尾部 4dp 条的颜色标记来源：永不混用，UI 必须区分。 */
enum class ProgressSource {
    /** curl/prefetch manager bytesGot 已确认。 */
    PREFETCH_MANAGER_CONFIRMED,
    /** TrafficStats 应用侧收包字节差分估算（带 10sp 角标「估」）。 */
    TRAFFIC_STATS_ESTIMATED,
    /** apk stdout 结构化 (N/T) Installing 行确认。 */
    APK_STDOUT_CONFIRMED,
}

/** 单个并行下载槽状态。id 范围 0..N-1（N 动态）。 */
@Immutable
data class DownloadSlot(
    val id: Int,
    /** 当前正在下载的包名（带版本号，如 python3-3.12.4-r2），null = 空槽。 */
    val pkgName: String?,
    /** HTTP Content-Length；拿不到（chunked/无 header）就 null，UI 上仅显示跑马灯进度条。 */
    val bytesTotal: Long?,
    /** 已收到字节数（cur 回调真实值）。 */
    val bytesGot: Long,
    /** 瞬时速率 B/s（注意单位，UI 自动转 KB/MB/s）。 */
    val speedBps: Float,
    val status: SlotStatus,
    /** 失败原因（HTTP code / curl exit msg），成功/空槽为 null。 */
    val failReason: String? = null,
    /** true = 该文件走 apk 自带 fetch（非 prefetch）。UI 在方块左上画个小 ◤ 浅蓝三角。 */
    val isApkNativeFallback: Boolean = false,
)

enum class SlotStatus {
    WAITING, DLING, DONE, FAILED,
}

/** 一行日志。RingBuffer 200 行上限。id 用作 LazyColumn stable key。 */
@Immutable
data class LogLine(
    val id: Long,
    val kind: LogLineKind,
    val text: String,
    /** -1f = 行内无进度；0f..1f = 行内右侧画一条微进度块。 */
    val inlineProgress: Float = -1f,
    /** 行内瞬时速率 B/s，0 = 不显示。 */
    val inlineSpeedBps: Float = 0f,
    /** B-方案强点：每行日志带 ownerBundleId 标签，便于跨 Bundle 日志溯源 & 全局一条环形日志时过滤。 */
    val ownerBundleId: TerminalBundleId? = null,
)

enum class LogLineKind {
    /** ⬇ 蓝色，prefetch 或 apk fetch。 */
    FETCH,
    /** ⚙ 深灰 + 旋转 loading 圈，当前安装中的包。 */
    INSTALL_CURR,
    /** ✓ 淡绿底 + 毫秒耗时（或完成标记）。 */
    INSTALL_OK,
    /** ⓘ 浅灰（repository 写入、config 写入等通知）。 */
    INFO,
    /** ✗ 淡红底 + 可复制长按。 */
    ERROR,
    /** 🔧 紫色（bashrc/passwd 等后处理脚本步骤）。 */
    POST_HOOK,
}

/** 完成后统计，显示在面板最后一行「✨ 已就绪」。 */
@Immutable
data class FinishStats(
    val elapsedMs: Long,
    val packagesInstalled: Int,
    val bytesDownloaded: Long,
    val bytesInstalledOnDisk: Long,
    /** 0..N 并发槽当时算出的值，仅做展示/埋点用。 */
    val concurrencySlots: Int,
)

/**
 * 所有 UI 数据源头 —— RealProgressAggregator 每 100ms 产出一个不可变快照，
 * StateFlow 发给卡片与 Dialog。字段结构与 Spec v1.0 一一对应。
 */
@Immutable
data class AggregateProgressState(
    val phase: InstallPhase,
    /** 0.0..1.0 总进度。 */
    val total: Float,
    /** 进度颜色编码来源。 */
    val source: ProgressSource,

    /** N 个并行下载槽。槽数由 PrefetchConcurrencyPolicy 在开始时一次性算出。 */
    val slots: List<DownloadSlot>,

    /** 安装阶段计数：N/TOTAL；TOTAL=0 表示还没拿到。 */
    val installingDone: Int,
    val installingTotal: Int,
    val installingCurrent: String?,

    /** 3 行卡片的 L2：elapsed 毫秒 + ETA 毫秒（null = 不显示 ⏳）。 */
    val elapsedMs: Long,
    val etaMs: Long?,

    /** 3 行卡片的 L3：瞬时速率 B/s（installing 阶段可允许为 0，L3 让位给当前安装包文本）。 */
    val currentSpeedBps: Float,

    /** RingBuffer 行：最近 200 行。卡片只渲染最后 3 条可摘要项。 */
    val logLines: List<LogLine>,

    /**
     * B-方案 revision：LogLineStore 每次结构/内容变更（append / remove / replaceAt / clear）单调 +1。
     * UI 端 counts / filtered / jumpTargets 全部 derivedStateOf 显式读该字段，避免「size 不变但 element 变了」
     * （比如 INFO→FETCH kind 原位替换）时 remember(key=size) 不重算导致统计失真。
     */
    val revision: Long = 0L,

    /** 终端态：成功不为 null；失败可 null 或填错误摘要。 */
    val finishStats: FinishStats? = null,
    val failSummary: String? = null,
) {
    val isTerminal: Boolean get() = phase.isTerminal

    companion object {
        /** 初始空状态。slots 为空，等 concurrency 算出来后立刻 emit 一个带 N 空槽的首帧。 */
        val INITIAL: AggregateProgressState = AggregateProgressState(
            phase = InstallPhase.DOWNLOAD,
            total = 0f,
            source = ProgressSource.PREFETCH_MANAGER_CONFIRMED,
            slots = emptyList(),
            installingDone = 0,
            installingTotal = 0,
            installingCurrent = null,
            elapsedMs = 0L,
            etaMs = null,
            currentSpeedBps = 0f,
            logLines = emptyList(),
            revision = 0L,
        )
    }
}

/** 固定容量环形缓冲；追加超过容量时头丢弃。线程安全：单写多读，调用方（Aggregator tick）在 Mutex 下写。 */
open class RingBuffer<T : Any>(open val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be >0: $capacity" }
    }

    protected val deque = ArrayDeque<T>(capacity)

    open val size: Int get() = deque.size

    open fun append(value: T) {
        if (deque.size >= capacity) deque.removeFirst()
        deque.addLast(value)
    }

    open fun snapshot(): List<T> = deque.toList()

    open fun clear() = deque.clear()
}

/**
 * 日志行权威存储（B 方案）：RingBuffer + revision 单调递增 + 统一写入 API。
 *
 * 基础原则：任何对日志行的变更（append/remove/原位 kind 修正/清空）**必须经过统一入口**，
 * 每一次成功的写入都会 bump `revision`，下游 Compose 的 derivedStateOf 读 revision 即可
 * 100% 捕获到「size 不变但 element 变了」的场景（比如 INFO 行原位替换 kind→FETCH）。
 */
class LogLineStore(capacity: Int) : RingBuffer<LogLine>(capacity) {
    private var _revision: Long = 0L

    /** 每次结构/内容变更 +1，单调递增，UI 端所有派生统计显式读它当硬 key。 */
    val revision: Long get() = _revision

    private fun bump() { _revision++ }

    override fun append(value: LogLine) {
        super.append(value)
        bump()
    }

    /**
     * B-方案强点封装：按当前会话 bundle 上下文追加，自动写入 ownerBundleId 标签。
     * 调用方（Aggregator）不用每次手动拼字段，避免漏打标签。
     */
    fun append(value: LogLine, ownerBundleId: TerminalBundleId?): LogLine {
        val enriched = if (ownerBundleId != null && value.ownerBundleId == null) {
            value.copy(ownerBundleId = ownerBundleId)
        } else value
        super.append(enriched)
        bump()
        return enriched
    }

    /** 批量移除，返回移除条数；哪怕没移除（pred 全 false）为了与外部调用方的空快照统一仍 bump。 */
    fun removeIf(predicate: (LogLine) -> Boolean): Int {
        var removed = 0
        val it = deque.iterator()
        while (it.hasNext()) {
            if (predicate(it.next())) {
                it.remove()
                removed++
            }
        }
        bump()
        return removed
    }

    /**
     * 原位替换（关键 API：语义修正 —— 比如 INFO 的 "fetch APKINDEX.tar.gz" 改 kind=FETCH）。
     * 返回替换后的新行；index 越界为 null。
     * 注意：默认保留旧行的 ownerBundleId，除非 transform 显式覆盖它。
     */
    fun replaceAt(index: Int, transform: (LogLine) -> LogLine): LogLine? {
        if (index < 0 || index >= deque.size) return null
        val old = deque[index]
        val newRaw = transform(old)
        val new = if (newRaw.ownerBundleId == null && old.ownerBundleId != null) {
            newRaw.copy(ownerBundleId = old.ownerBundleId)
        } else newRaw
        deque[index] = new
        bump()
        return new
    }

    override fun clear() {
        super.clear()
        bump()
    }

    /** 按 id 查找并替换（聚合器做"同一包 FETCH 行补 inline 进度"常用）。 */
    fun replaceById(id: Long, transform: (LogLine) -> LogLine): LogLine? {
        val idx = deque.indexOfFirst { it.id == id }
        if (idx < 0) return null
        return replaceAt(idx, transform)
    }
}
