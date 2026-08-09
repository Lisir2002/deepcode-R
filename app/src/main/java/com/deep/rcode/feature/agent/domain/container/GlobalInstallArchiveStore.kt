package com.deep.rcode.feature.agent.domain.container

import com.deep.rcode.feature.agent.domain.container.progress.AggregateProgressState
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import android.util.LruCache

/**
 * A-方案核心：每个 Bundle 独立存档 + 历史快照（全局 LRU 单例）。
 *
 * 设计意图：
 *  - 用户在 Bundle B（未下载/已下载）打开日志对话框时，
 *    能看到 Bundle A 之前安装过的完整日志快照（L1-L3、搜索、筛选全部复用），
 *    不需要 A 此刻正在"当前会话"中。
 *  - 容量上限：最近 10 个不同 Bundle（枚举一共 6 个，10 完全覆盖）；
 *    超出时 LRU 淘汰最早没被写入的那个 Bundle 的快照。
 *  - B-方案强点融合：
 *      1. 存档的每行日志本身就带着 ownerBundleId 标签（由 LogLineStore.append 注入），
 *         将来如果要做"跨 Bundle 全局一条环形大日志"，直接把各存档的 logLines 合并
 *         + 按 id 排序即可，不需要再做二次打标。
 *      2. globalRevision：每次任何 Bundle 的快照写入/更新，全局 revision 单调 +1；
 *         UI 端如果监听整个 ArchiveStore 的变化（而非单个 bundleId），
 *         用它当硬 key 可以 100% 捕获到"有新存档写入"的信号。
 *
 * 线程安全：单写多读，写入方是 RealProgressAggregator（已经在 Mutex 下调用），
 * 读取方是 Compose UI，拿到的是不可变快照（AggregateProgressState 是 Immutable data class）。
 */
object GlobalInstallArchiveStore {

    private const val MAX_BUNDLES = 10

    /** A-方案 LRU：最近 10 个 Bundle 的安装进度 + 日志全量快照。 */
    private val lruCache = object : LruCache<TerminalBundleId, AggregateProgressState>(MAX_BUNDLES) {
        override fun entryRemoved(
            evicted: Boolean,
            key: TerminalBundleId?,
            oldValue: AggregateProgressState?,
            newValue: AggregateProgressState?,
        ) {
            bumpGlobalRevision()
        }
    }

    /**
     * B-方案强点：全局 revision。
     *  任何 saveSnapshot / updateSnapshot / clear / LRU 淘汰都会 +1；
     *  下游 UI 如果需要"全局存档视图"刷新（比如下拉显示最近 N 次安装历史），
     *  直接 remember(key = globalRevision) 即可。
     */
    @Volatile
    private var _globalRevision: Long = 0L
    val globalRevision: Long get() = _globalRevision

    private fun bumpGlobalRevision() {
        _globalRevision++
    }

    /** 当前内存里有快照的 BundleId 集合（顺序 = LRU 最近访问顺序，最旧在前）。 */
    @Synchronized
    fun snapshotKeys(): List<TerminalBundleId> {
        val snapshot = lruCache.snapshot()
        return snapshot.keys.toList()
    }

    /** 当前有多少个 Bundle 的存档在内存里。 */
    fun size(): Int = lruCache.size()

    /**
     * 保存一个全新的快照（通常在 startInstallSession 之前把「上一个正在操作的 state」塞进来，
     * 或者在 exit=0/exit≠0 的终态后做永久存档）。
     */
    @Synchronized
    fun saveSnapshot(bundleId: TerminalBundleId, state: AggregateProgressState) {
        lruCache.put(bundleId, state)
        bumpGlobalRevision()
    }

    /**
     * 更新一个已存在 Bundle 的最新快照（每 100ms 一次的 publish 会调这里，
     * 保证 ArchiveStore 里拿出来的也是"尽量实时"的，而不是仅终态才存档）。
     * 如果该 Bundle 还没 saveSnapshot 过，等价于 saveSnapshot。
     */
    @Synchronized
    fun updateSnapshot(bundleId: TerminalBundleId, state: AggregateProgressState) {
        lruCache.put(bundleId, state)
        bumpGlobalRevision()
    }

    /** 读取某个 Bundle 的存档快照；没有返回 null。 */
    @Synchronized
    fun getSnapshot(bundleId: TerminalBundleId): AggregateProgressState? {
        return lruCache.get(bundleId)
    }

    /** 是否存在某 Bundle 的存档。 */
    @Synchronized
    fun hasSnapshot(bundleId: TerminalBundleId): Boolean = lruCache.get(bundleId) != null

    /** 清空所有存档（容器卸载 / App 重置设置时使用）。 */
    @Synchronized
    fun clear() {
        lruCache.evictAll()
        bumpGlobalRevision()
    }

    /** 仅移除单个 Bundle 的存档（卸载该 Bundle 时使用）。 */
    @Synchronized
    fun remove(bundleId: TerminalBundleId) {
        lruCache.remove(bundleId)
        bumpGlobalRevision()
    }
}
