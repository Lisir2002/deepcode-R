package com.deep.rcode.feature.agent.domain.container.progress

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.CommandEvent
import com.deep.rcode.feature.agent.domain.container.ContainerInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 并行预取管理器（Spec v1.0 二.2 Level 3a + 5.3 Q2 fail-open）。
 *
 * 职责：
 *  1. 查询 apk 依赖图：`apk info --depends pkg1 pkg2 ...` → 拿到完整 list<pkg-ver>
 *  2. 拼 URL：`$ALPINE_MIRROR/$ALPINE_BRANCH/{main,community}/$ARCH/$pkg.apk`
 *     （Alpine APKINDEX 里包按仓库分目录，我们这里先尝试 main，404 再试 community）
 *  3. 按 [PrefetchConcurrencyPolicy] 算出的槽数跑 curl，并发写入 `/var/cache/apk/$pkg.apk`
 *     —— 与 `apk --cache-dir /var/cache/apk add ...` 的 cache 目录完全对齐。
 *  4. 流式逐包发布 [PrefetchEvent]：槽进度、速率、成功/失败标记。
 *     失败 fail-open：让 apk add 自己 fetch 兜底，不中断整体安装。
 *
 * Alpine 真机架构：当前发布 arm64-v8a 单架构，ARCH = aarch64。为兼容 x86_64 模拟器本地 debug，
 * 运行时 `uname -m` 查询一次，不要硬编码。
 */
class ParallelPrefetchManager(
    private val slotsCount: Int,
    /** 容器内同步执行（execCaptured 级），返回 (stdout 完整文本, exitCode)。 */
    private val runSync: suspend (cmd: String, timeoutMs: Long) -> Pair<String, Int?>,
    /** 容器内流式执行（streamExec 级），stdout 逐行 Flow + 末尾 Exit(code)。 */
    private val streamShell: (cmd: String, timeoutMs: Long) -> Flow<CommandEvent>,
) {
    private val TAG = "ParallelPrefetch"

    sealed interface PrefetchEvent {
        /** 一次性发射：依赖列表解析完成，总字节预算。 */
        data class DependsResolved(val totalPackages: Int, val budgetBytes: Long?) : PrefetchEvent

        /** 槽位状态/字节变动：Aggregator 直接映射成 DownloadSlot。 */
        data class SlotUpdate(val slot: DownloadSlot) : PrefetchEvent

        /** 全部结束：列出最终状态（成功包集合 + 失败包集合），Aggregator 据此切到 INSTALL 阶段。 */
        data class Finished(
            val successPackages: Set<String>,
            val failedPackages: Map<String, String>,
        ) : PrefetchEvent
    }

    private val _events = MutableSharedFlow<PrefetchEvent>(extraBufferCapacity = 64)
    val events: Flow<PrefetchEvent> = _events.asSharedFlow()

    private val _slots = MutableStateFlow<List<DownloadSlot>>(
        List(slotsCount) { id -> emptySlot(id) },
    )
    val slots: StateFlow<List<DownloadSlot>> = _slots.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = Semaphore(slotsCount)

    // ─────────────────────────── RC61c S3+S4 Fix ────────────────────────────
    /** 所有已经 fire 的 async jobs。失败时 shutdown 能 cancel 所有正在 curl/wget 的包。 */
    private val inflightJobs = mutableListOf<kotlinx.coroutines.Deferred<*>>()
    /** shutdown 调用后置 true，prefetch 的循环/single download 会立刻抛 Cancellation 退出。 */
    @Volatile
    private var shutdownRequested = false
    /** APKINDEX fetch 连续 IO ERROR 计数（resolveDependencies + 主 apk update 公用）。
     *  达到 2 立刻熔断：resolveDependencies 返回空 deps，prefetch 不启动。 */
    @Volatile
    private var consecutiveFetchIoErrors = 0
    /** 线程安全地增加 consecutiveFetchIoErrors；达到 2 就返回 true 让调用方熔断。 */
    @Synchronized
    private fun incFetchIoErrorAndShouldCircuitBreak(): Boolean {
        consecutiveFetchIoErrors += 1
        return consecutiveFetchIoErrors >= 2
    }
    @Synchronized
    private fun resetFetchIoErrors() { consecutiveFetchIoErrors = 0 }

    /**
     * RC61c S3 Fix：外部（通常是 installBundle 的 catch/finally/exitCode≠0 分支）调用
     * 本方法，立刻释放所有并发资源：
     *   - cancel 所有 inflight jobs（curl/wget streamShell 会收 CancellationException 停掉 Socket）
     *   - cancel scope（所有 downloadSingle 子协程立刻停，不再占线程）
     *   - 把所有 slot 立刻置 FAILED（释放 Permit 语义等价：sem.withPermit 退出后自动释放 permit）
     *   - 发一次 Finished 事件，让 Aggregator 立刻结束，避免 UI 永远显示「X 槽并行 · Y KB/s」。
     *   - RC61f：最后 cleanupPartial() 清理 /var/cache/apk/*.part 半截下载垃圾，
     *     防止 rootfs 里越积越多占用户手机存储。
     *
     * 幂等：多次调用安全。
     */
    fun shutdown(reason: String = "安装会话已结束") {
        shutdownRequested = true
        // 1) cancel 所有 inflight curl/wget（顺序不重要，cancel 是同步语义）
        synchronized(inflightJobs) {
            for (j in inflightJobs) {
                runCatching { j.cancel("$reason: ParallelPrefetchManager.shutdown()") }
            }
            inflightJobs.clear()
        }
        // 2) cancel scope（子协程里挂起函数收 CancellationException → 立刻释放 Permit）
        runCatching { scope.cancel("$reason: scope shutdown") }
        // 3) UI 立刻更新：把所有 slot 清空 WAITING + 发一次 Finished，
        //    Aggregator 收到后会把 slots 快照同步为 N 个空槽，UI 并行数立刻归 0。
        runCatching {
            val cur = _slots.value
            val cleaned = cur.map {
                it.copy(
                    pkgName = null, bytesTotal = null, bytesGot = 0L, speedBps = 0f,
                    status = SlotStatus.WAITING, failReason = null,
                )
            }
            _slots.value = cleaned
        }
        runCatching {
            val fin = PrefetchEvent.Finished(
                successPackages = linkedSetOf(),
                failedPackages = linkedMapOf("<shutdown>" to reason),
            )
            scope.launch { _events.emit(fin) }
        }
        // RC61f：4) 无论成功/失败，shutdown 时统一清理所有 .part 半截文件（用户反馈：失败不删垃圾、占存储）
        runCatching { cleanupPartialCache(timeoutMs = 1500) }
        FileLogger.i(TAG, "shutdown(reason=$reason) 执行完成，inflight 已释放 + 已清理 .part 垃圾")
    }

    // ─── RC61f：下载垃圾清理 / 续传 / 中间文件（原子 mv）管理 ───

    /**
     * 清理 /var/cache/apk 下的 .part 半截下载 + 可选清已安装的 apk 实体（成功后释放空间）。
     *
     * 为什么要做？
     *  用户反馈：FAIELD 后不删失败下载的东西，反复重试多次，手机存储里越积越多半截
     *  `python3-xxx.apk.part`（每张几 MB~几十 MB）→ 严重占用手机存储空间 + 扫盘 I/O 开销。
     *
     *  失败/成功会话结束都跑：
     *   - `.part`：永远是「写到一半」的不完整 apk，安全删除。
     *   - 如果 cleanupInstalledApks=true（成功安装后传 true），再额外删 /var/cache/apk/*.apk
     *     （apk --no-cache 默认不写 cache，但我们预取写到了 cache 目录，apk add 不会自动清理。
     *      成功安装完后若要更省空间，可把本参数传 true；缺省 false 为了「断点续传+命中缓存」。）
     */
    suspend fun cleanupPartialCache(
        cleanupInstalledApks: Boolean = false,
        timeoutMs: Long = 3000,
    ) {
        val what = buildString {
            append("rm -f /var/cache/apk/*.part 2>/dev/null; ")
            if (cleanupInstalledApks) append("rm -f /var/cache/apk/*.apk 2>/dev/null; ")
            append("sync >/dev/null 2>&1; echo OK")
        }
        val (o, c) = runSync("mkdir -p /var/cache/apk && $what", timeoutMs)
        FileLogger.i(
            TAG,
            "cleanupPartialCache(installed=$cleanupInstalledApks) exit=$c " +
                "${if (c == 0 && o.contains("OK")) "OK" else "timeout/fail: $o"}"
        )
    }

    /**
     * 启动时一次性清理历史遗留垃圾（proot crash/kill -9 没跑 shutdown 就会留下 .part）。
     * 放在 ContainerInstaller.provisionIfNeed 里异步调一次，不阻塞首屏。
     */
    suspend fun cleanupLeftoversFromLastRun(timeoutMs: Long = 3000) =
        cleanupPartialCache(cleanupInstalledApks = false, timeoutMs = timeoutMs)

    // ────────────────────────────────────────────────────────────────────────

    /**
     * 查询架构（一次）。失败兜底 aarch64（真机发布单架构）。
     */
    suspend fun resolveArch(): String {
        val (out, code) = runSync("uname -m 2>/dev/null", 5000)
        val raw = if (code == 0) out.trim() else ""
        return when (raw) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86_64"
            "armv7l", "armv8l" -> "armhf"
            else -> "aarch64" // 真机默认
        }
    }

    /**
     * 查询依赖清单：每个顶层包的所有传递依赖展开后完整 apk 文件名列表
     * （形如 listOf("python3-3.12.4-r2", "libssl3-3.5.0-r0", ...)）。
     *
     * **关键去重逻辑（Fix A）**：同一「裸名 = 带版本前缀」的条目只保留**带版本**那个
     * （e.g. `python3` 与 `python3-3.12.13-r0` 并存 → 只留后者）。
     * 原因：`<name>.apk` 在 Alpine mirrors 上通常 404，必须带 `<name>-<ver>-<rel>.apk` 才命中。
     */
    suspend fun resolveDependencies(
        topPackages: List<String>,
        timeoutMs: Long = 60_000,
    ): List<String> {
        if (topPackages.isEmpty()) return emptyList()
        val mirror = ContainerInstaller.ALPINE_MIRROR
        val branch = ContainerInstaller.ALPINE_BRANCH
        val (updateOut, updateCode) = runSync(
            buildString {
                append("mkdir -p /etc/apk && cat > /etc/apk/repositories <<'EOF'\n")
                append("$mirror/$branch/main\n")
                append("$mirror/$branch/community\n")
                append("EOF\n")
                append("apk update > /dev/null 2>&1; echo UPD_DONE\n")
                append("apk info --depends ${topPackages.joinToString(" ")} 2>/dev/null\n")
            },
            timeoutMs,
        )
        if (updateCode != 0 && !updateOut.contains("UPD_DONE")) {
            FileLogger.w(TAG, "resolve deps apk update may have failed exit=$updateCode")
        }
        val lines = updateOut.lineSequence().map { it.trim() }.filterNot(String::isEmpty).toList()
        // ───────────── RC61c S4 Fix: APKINDEX fetch IO ERROR 连续 2 次熔断 ─────────────
        // 截图里 WARNING fetching community: IO ERROR → 镜像挂/本地网络差，继续 resolve/prefetch
        // 只会浪费更多 Socket/线程；立刻返回空 deps，让下游 prefetch 不启动、apk add 直接走 apk 自带 fetch。
        val lowerAll = updateOut.lowercase()
        val hasIoWarn = (lowerAll.contains("io error") || lowerAll.contains("warning: fetching")) &&
            (updateCode != 0 || lowerAll.contains("unable to select packages"))
        if (hasIoWarn) {
            val shouldBreak = incFetchIoErrorAndShouldCircuitBreak()
            FileLogger.w(
                TAG,
                "resolveDependencies apk update 检测到镜像 IO ERROR（exit=$updateCode），" +
                    "consecutiveFetchIoErrors=$consecutiveFetchIoErrors，circuit_break=$shouldBreak",
            )
            if (shouldBreak) {
                // 连续 2 次命中：直接返回空，prefetch 不启动，apk add 自己负责 fetch
                FileLogger.w(TAG, "FETCH 连续 IO ERROR 熔断：resolveDependencies 返回空 deps，跳过预取")
                return emptyList()
            }
        } else {
            resetFetchIoErrors()
        }
        // ──────────────────────────────────────────────────────────────────────────
        val raw = LinkedHashSet<String>()
        for (line in lines) {
            if (line.endsWith("depends on:")) {
                raw += line.substringBeforeLast(" depends on:").trim()
            } else if (line.startsWith(" ") || line.startsWith("\t")) {
                raw += line.trim()
            }
        }
        // 兜底补回顶层包（避免 apk info 无输出时漏）
        raw += topPackages
        // ─── 去重 A：裸名/带版本同名合并，只保留带版本的那个 ───
        //   1) 所有带版本名 `<name>-<ver>-<rel>`：取 "<name>" 作 key 建 Map
        //   2) 所有裸名：若 key 在带版本名 Map 里已存在就丢弃；否则保留（apk 会用裸名 404 fail-open）
        //   3) 顺序：按带版本名在 raw 中出现顺序输出
        val versioned = linkedMapOf<String, String>() // key=裸名 → value=带版本名（按出现顺序）
        val pureNames = linkedMapOf<String, String>() // key=裸名 → value=自身
        val versionedPat = Regex("""^(.+?)-(\d[\w.\-]*)-r(\d+)$""")
        for (p in raw) {
            val m = versionedPat.matchEntire(p)
            if (m != null) {
                val bare = m.groupValues[1]
                if (bare !in versioned) versioned[bare] = p
            } else {
                if (p !in pureNames) pureNames[p] = p
            }
        }
        val deduped = LinkedHashSet<String>()
        // 先放带版本名（按 raw 顺序）
        for (p in raw) if (versionedPat.matches(p)) deduped.add(p)
        // 再放纯名（仅当没被带版本名覆盖时）
        for ((bare, orig) in pureNames) if (bare !in versioned) deduped.add(orig)
        FileLogger.i(
            TAG,
            "resolved deps raw=${raw.size} → deduped=${deduped.size} " +
                "(pkgs=$topPackages versioned=${versioned.size} pure-only=${deduped.size - versioned.size})",
        )
        return deduped.toList()
    }

    /**
     * 并发预取 [packages] 到 /var/cache/apk/。
     * [mirror] / [branch] 默认走 [ContainerInstaller] 常量，与 apk 脚本一致。
     *
     * **Fix B：curl/wget 一次性探测**：prefetch 入口只查 1 次工具，全局缺工具时跳过所有包、
     * 发 1 条聚合 INFO（而不是 N 个包各自跑 which + 各报一条 N×ERROR 刷屏）。
     *
     * **Fix B-2：失败 reason 聚合**：相同错误信息的所有包只标记为批量失败，Finished 事件里
     * 用 `Map<String,String>` 的 value 作为去重后的 error reason，方便 Aggregator 聚合只打 1 条。
     *
     * @return 最终成功（cache 已写入，或已存在校验通过）的包名集合；失败的包 apk 会兜底。
     */
    suspend fun prefetch(
        packages: List<String>,
        mirror: String = ContainerInstaller.ALPINE_MIRROR,
        branch: String = ContainerInstaller.ALPINE_BRANCH,
        perPackageTimeoutMs: Long = 180_000,
    ): PrefetchEvent.Finished {
        val arch = resolveArch()
        val success = linkedSetOf<String>()
        val failed = linkedMapOf<String, String>()
        val sem = semaphore

        // 初始化空槽：Aggregator 拿到的 slots 快照有 N 个 WAITING，UI 立刻画 N 个方块
        _slots.value = List(slotsCount) { id -> emptySlot(id) }
        _events.emit(
            PrefetchEvent.DependsResolved(
                totalPackages = packages.size,
                budgetBytes = null,
            ),
        )

        // ─── Fix B：一次性探测下载工具 ───
        val (tools, _) = runSync("which curl wget 2>/dev/null; echo done", 3000)
        val hasCurl = tools.lineSequence().any { it.trim() == "curl" }
        val hasWget = tools.lineSequence().any { it.trim() == "wget" }
        val globalSkipReason =
            if (!hasCurl && !hasWget) "容器内没有 curl/wget，跳过预取（apk add 会自己 fetch）"
            else null

        if (globalSkipReason != null) {
            // 所有包批量标记失败：每个包 failed map 放同一个 reason 字符串
            //   （Aggregator 会按 reason 聚合只打 1 条日志，不会 N 条刷屏）
            packages.forEach { failed[it] = globalSkipReason }
            // 立刻把 slot 全部切换到 FAILED（空槽用一批 pkg 名循环填，最多 slotsCount 个显式展示）
            val nShow = minOf(slotsCount, packages.size)
            for (i in 0 until nShow) {
                markSlotFailed(idx = i, pkg = packages[i], reason = globalSkipReason, bulk = true)
            }
            val finish = PrefetchEvent.Finished(success, failed)
            _events.emit(finish)
            FileLogger.i(TAG, "prefetch skipped (no curl/wget), pkgs=${packages.size}")
            return finish
        }

        // RC61c S3：预取前确保没被 cancel；如果失败分支已经触发 shutdown → 立刻短路返回空
        if (shutdownRequested) {
            val reason = "prefetch 启动前已 shutdown，跳过"
            packages.forEach { failed[it] = reason }
            val fin = PrefetchEvent.Finished(success, failed)
            runCatching { _events.emit(fin) }
            return fin
        }

        val jobs = packages.map { pkg ->
            scope.async {
                sem.withPermit {
                    // RC61c S3：permit 拿到后再查一次 shutdownRequested，shutdown 发生在
                    // sem.withPermit 进入后也能立刻释放 Permit 不占 semaphore。
                    if (shutdownRequested) {
                        throw CancellationException("prefetch cancelled (shutdown in sem.withPermit)")
                    }
                    val slotIdx = acquireFreeSlotIndex()
                    try {
                        downloadSingle(
                            slotIdx = slotIdx,
                            pkg = pkg,
                            mirror = mirror,
                            branch = branch,
                            arch = arch,
                            perPkgTimeoutMs = perPackageTimeoutMs,
                            hasCurl = hasCurl,
                            hasWget = hasWget,
                        )
                        success += pkg
                    } catch (ce: CancellationException) {
                        // CancellationException 正常不打 WARN（正常的 cancel 路径）
                        failed[pkg] = "cancelled"
                        markSlotFailed(slotIdx, pkg, "cancelled", bulk = true)
                    } catch (t: Throwable) {
                        failed[pkg] = (t.message ?: t.javaClass.simpleName)
                        markSlotFailed(slotIdx, pkg, (t.message ?: t.javaClass.simpleName))
                        // RC61c S4：fail-open，单包失败不再每条打 FileLogger.w 刷屏（最多打 3 条聚合）；
                        // Aggregator 的 flushPrefetchFailures 统一输出。
                        if (failed.size <= 3) {
                            FileLogger.w(TAG, "prefetch single fail pkg=$pkg (${failed.size}/3 聚合上限)", t)
                        }
                    }
                }
            }.also { job ->
                // RC61c S3：把 job 注册进 inflightJobs，shutdown() 能逐个 cancel
                synchronized(inflightJobs) { inflightJobs += job }
                // 跑完后从 inflightJobs 移除（正常结束的包不需要 cancel）
                job.invokeOnCompletion {
                    synchronized(inflightJobs) { inflightJobs -= job }
                }
            }
        }
        for (j in jobs) {
            runCatching { j.await() } // fail-open：await 失败也不抛
                .onFailure { t ->
                    FileLogger.w(TAG, "prefetch job await failed", t)
                }
        }
        val finish = PrefetchEvent.Finished(success, failed)
        _events.emit(finish)
        FileLogger.i(
            TAG,
            "prefetch finished pkgs=${packages.size} slots=$slotsCount " +
                "success=${success.size} failed=${failed.size}",
        )
        return finish
    }

    // ──────────────────────────────── 内部辅助 ────────────────────────────────

    private fun emptySlot(id: Int): DownloadSlot = DownloadSlot(
        id = id,
        pkgName = null,
        bytesTotal = null,
        bytesGot = 0L,
        speedBps = 0f,
        status = SlotStatus.WAITING,
    )

    /** 找第一个 WAITING 空槽；若都忙则随机选一个 DLING 槽（理论上不会到这因为 semaphore 限制）。 */
    private fun acquireFreeSlotIndex(): Int {
        val cur = _slots.value
        val free = cur.indexOfFirst { it.status == SlotStatus.WAITING }
        return if (free >= 0) free else (cur.indices).random()
    }

    private fun updateSlot(idx: Int, silent: Boolean = false, transform: (DownloadSlot) -> DownloadSlot) {
        _slots.getAndUpdate { list ->
            val old = list[idx]
            val new = transform(old)
            list.toMutableList().also { it[idx] = new }
        }
        if (!silent) {
            val after = _slots.value[idx]
            // 把槽更新同步广播给 Aggregator（它要写入日志行 + RingBuffer）
            scope.launch { _events.emit(PrefetchEvent.SlotUpdate(after)) }
        }
    }

    private fun markSlotFailed(idx: Int, pkg: String, reason: String, bulk: Boolean = false) {
        updateSlot(idx, silent = bulk) { s ->
            s.copy(
                pkgName = pkg,
                status = SlotStatus.FAILED,
                failReason = reason,
            )
        }
    }

    /**
     * 单个包下载（RC61f 大改：中间文件 + 失败即删 + 断点续传）：
     *  0. cache hit：/var/cache/apk/$pkg.apk 已存在且非空 → 直接 DONE。
     *  1. 否则先准备临时文件 /var/cache/apk/$pkg.apk.part：
     *     - 若 .part 已存在且非空（上次下载到一半）→ 作为续传起点，curl -C - / wget -c 断点续传。
     *     - 成功：原子 `mv .part .apk`，写入 slot=DONE；apk --cache-dir 可后续命中。
     *     - 失败（main+community 都 404/超时/半关闭）：`rm -f .part` 立刻删除半截垃圾，不留占盘。
     *  2. shutdownRequested：在 repo 循环里/续传开始前都检查 → 抛 CancellationException（释放 sem）。
     */
    private suspend fun downloadSingle(
        slotIdx: Int,
        pkg: String,
        mirror: String,
        branch: String,
        arch: String,
        perPkgTimeoutMs: Long,
        hasCurl: Boolean,
        hasWget: Boolean,
    ) {
        // 先标记 DLING + pkgName
        updateSlot(slotIdx) { s ->
            s.copy(pkgName = pkg, status = SlotStatus.DLING, bytesGot = 0L, bytesTotal = null, speedBps = 0f)
        }

        val finalPath = "/var/cache/apk/$pkg.apk"
        val partPath = "$finalPath.part"

        // 0. cache hit 探测（apk cache 目录和 apk --cache-dir 对齐）
        run {
            val (sizeOut, sizeCode) = runSync(
                "if [ -s $finalPath ]; then stat -c%s $finalPath 2>/dev/null || wc -c < $finalPath; fi",
                3000,
            )
            if (sizeCode == 0) {
                val sz = sizeOut.trim().toLongOrNull()
                if (sz != null && sz > 0) {
                    updateSlot(slotIdx) { s ->
                        s.copy(
                            bytesTotal = sz, bytesGot = sz,
                            speedBps = 0f, status = SlotStatus.DONE,
                        )
                    }
                    return
                }
            }
        }

        // 1. 工具探测已在 prefetch() 入口做过；这里双负保险
        if (!hasCurl && !hasWget) {
            error("容器内没有 curl/wget，跳过预取（apk add 会自己 fetch）")
        }

        // RC61f：.part 如果上次写到一半且有字节数，算「已有字节」，传给 curl/wget 做续传
        val alreadyBytes: Long = runSync(
            "if [ -s $partPath ]; then stat -c%s $partPath 2>/dev/null || wc -c < $partPath; fi",
            3000,
        ).let { (o, c) ->
            if (c == 0) o.trim().toLongOrNull() ?: 0L else 0L
        }.coerceAtLeast(0L)

        val repos = listOf("main", "community")
        var lastErr: String? = null
        var success = false
        for (repo in repos) {
            if (shutdownRequested) {
                lastErr = "shutdown requested"
                break
            }
            val url = "$mirror/$branch/$repo/$arch/$pkg.apk"
            val ok = if (hasCurl) {
                runCurl(
                    url = url,
                    outPart = partPath,
                    resumeOffset = alreadyBytes,
                    slotIdx = slotIdx,
                    timeoutMs = perPkgTimeoutMs,
                )
            } else {
                runWget(
                    url = url,
                    outPart = partPath,
                    resume = alreadyBytes > 0,
                    timeoutMs = perPkgTimeoutMs,
                )
            }
            if (ok) { success = true; break }
            lastErr = "repo $repo 4xx/5xx or too small"
        }

        // RC61f：分支善后（原子 mv / 失败 rm）
        if (success) {
            // 成功：原子 mv → finalPath（apk --cache-dir 能识别）
            val (mvO, mvC) = runSync(
                "mv -f $partPath $finalPath 2>/dev/null && [ -s $finalPath ] && echo OK",
                4000,
            )
            if (mvC == 0 && mvO.trim() == "OK") {
                updateSlot(slotIdx) { s -> s.copy(status = SlotStatus.DONE) }
                return
            }
            // mv 失败（少见）：退化为「认为预取失败」，让 apk add 自己 fetch；但也要清 part
            lastErr = "mv .part → .apk 失败 ($mvO)"
            runSync("rm -f $partPath 2>/dev/null", 2000)
        } else {
            // 明确失败：立即 rm -f part，不留半截垃圾占手机存储
            runSync("rm -f $partPath 2>/dev/null", 2000)
        }
        // shutdownRequested 情况直接抛 CancellationException（释放 semaphore）
        if (shutdownRequested) {
            throw CancellationException("downloadSingle cancelled during repos loop ($lastErr)")
        }
        val reason = lastErr ?: "预取失败（所有仓库未命中），pkg=$pkg"
        markSlotFailed(slotIdx, pkg, reason, bulk = true)
        throw IllegalStateException(reason)
    }

    /**
     * curl（RC61f：改写到 .part + -C - 续传 + -w 解析）
     *
     * - -f：http!=2xx 算失败；-sSL：静默跟随重定向；
     * - -C -：自动按 outPart 的当前大小续传；alreadyBytes=0 时 -C - 等价全新下载
     *   （curl 见 out 不存在也会静默回退为从 0 开始，安全）
     * - -w：尾部写 size_download http_code（Alpine busybox curl 仍支持 -w）
     */
    private suspend fun runCurl(
        url: String,
        outPart: String,
        resumeOffset: Long,
        slotIdx: Int,
        timeoutMs: Long,
    ): Boolean {
        val resume = if (resumeOffset > 0) "-C -" else ""
        val script =
            "mkdir -p /var/cache/apk && curl -fsSL $resume -o '$outPart' " +
                "-w '\n%{size_download} %{http_code}\n' '$url' 2>&1 || true"
        var sizeGot = 0L
        var http = 0
        streamShell(script, timeoutMs).collect { ev ->
            when (ev) {
                is CommandEvent.Line -> {
                    val parts = ev.text.trim().split(' ').filterNot(String::isEmpty)
                    if (parts.size == 2) {
                        val s = parts[0].toLongOrNull()
                        val h = parts[1].toIntOrNull()
                        if (s != null) sizeGot = s
                        if (h != null) http = h
                    }
                }
                is CommandEvent.Exit -> { if (ev.code == null) http = 0 }
            }
        }
        // 2xx 且（这次下载字节数>0 或 断点续传下 part 本身就有字节数算有数据）
        val hasData = runSync("if [ -s '$outPart' ]; then echo YES; fi", 2000).let { (o, c) ->
            c == 0 && o.trim() == "YES"
        }
        if (http in 200..299 && hasData) {
            val total = (resumeOffset + sizeGot).coerceAtLeast(sizeGot)
            updateSlot(slotIdx) { s ->
                s.copy(bytesGot = total, bytesTotal = total, speedBps = 0f)
            }
            return true
        }
        return false
    }

    /**
     * busybox wget（RC61f：改写到 .part + -c 续传）
     * - -O 固定写 part；-c 表示 continue 从已存在的 part 追加。
     */
    private suspend fun runWget(
        url: String,
        outPart: String,
        resume: Boolean,
        timeoutMs: Long,
    ): Boolean {
        val cont = if (resume) "-c" else ""
        val script =
            "mkdir -p /var/cache/apk && wget -q $cont -O '$outPart' '$url' >/dev/null 2>&1; " +
                "if [ -s '$outPart' ]; then echo OK_$(wc -c < '$outPart'); else echo FAIL; fi"
        val (o, c) = runSync(script, timeoutMs)
        if (c == 0 && o.startsWith("OK_")) {
            val sz = o.removePrefix("OK_").trim().toLongOrNull() ?: 0L
            return sz > 0
        }
        return false
    }
}
