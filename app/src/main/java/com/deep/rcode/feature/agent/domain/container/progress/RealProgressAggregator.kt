package com.deep.rcode.feature.agent.domain.container.progress

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.domain.container.GlobalInstallArchiveStore
import com.deep.rcode.feature.terminal.data.bundle.TerminalBundleId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * 进度聚合器（Spec v1.0 二.3 五路信号融合 + 三 EMA）。
 *
 * 5 路真实信号：
 *  1. ParallelPrefetchManager.slot 回调
 *  2. apk stdout 结构化解析 (ApkStdoutParser)
 *  3. *（后续集成时注入）ConnectivityManager NetworkCap（带宽仅开槽数用，这里不采样）
 *  4. TrafficStats UID RX 字节差分 → 当前瞬时速率
 *  5. `apk info --depends` 返回的 TOTAL 依赖数（用于安装阶段 T 兜底）
 *
 * 算法要点（与 Spec 100% 对齐）：
 *  - 阶段权重 DOWNLOAD 0.45 / INSTALL 0.50 / POST 0.05，硬编码。
 *  - 速率平滑 EMA α=0.3（α=当前，1-α=历史）。
 *  - 颜色来源：Prefetch 确认=蓝700；Traffic 估测=蓝300+「估」角标；apk 安装确认=绿700。
 *  - ETA = remainingBytes / EMA_speed，speed<1KB/s 时返回 null（不显示）。
 *  - RingBuffer 容量=200。
 */
@Singleton
class RealProgressAggregator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "ProgressAggregator"

    companion object {
        private const val LOG_CAPACITY = 200
        private const val TICK_MS = 100L

        /** 速率 EMA 系数：cur 0.3 + last 0.7 */
        private const val EMA_ALPHA = 0.3f

        /** Budget 拿不到（没查 APKINDEX size）时兜底总预算：Python 90MB，其他依次递减。 */
        private const val FALLBACK_BUDGET_BYTES: Long = 90L * 1024 * 1024
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null

    // ─── 可变内部状态（Mutex 保护） ───
    private var phase = InstallPhase.DOWNLOAD
    private var source = ProgressSource.PREFETCH_MANAGER_CONFIRMED

    private var slotsSnapshot: List<DownloadSlot> = emptyList()
    private var installingDone = 0
    private var installingTotal = 0
    private var installingCurrent: String? = null
    private var postHookLinesTotal = 0
    private var postHookLinesDone = 0

    private var budgetBytesTotal: Long = FALLBACK_BUDGET_BYTES

    private var speedEmaBps: Float = 0f
    private var lastTrafficRxBytes: Long = -1L
    private var lastTickWallClockMs: Long = 0L
    private var installStartWallClockMs: Long = 0L

    private var finishStats: FinishStats? = null
    private var failSummary: String? = null

    // ─── Fix C：日志去重态 ───
    /** 已打 DLING 日志的包名集合：同一个包的 ⬇ 行只打 1 次，不因 slot 刷新重复刷。 */
    private val loggedDlingPkgs = mutableSetOf<String>()
    /** 已打最终态日志（DONE/FAILED）的包名集合：同样只打 1 次。 */
    private val loggedFinalPkgs = mutableSetOf<String>()
    /** FAILED 按 reason 聚合：reason → pkg 列表。等 flushFailedSummary 一次性合并输出。 */
    private val failedByReason = linkedMapOf<String, MutableList<String>>()

    private val logRing = LogLineStore(LOG_CAPACITY)
    private var logSeqId: Long = 0L

    private val _state = MutableStateFlow(AggregateProgressState.INITIAL)
    val state: StateFlow<AggregateProgressState> = _state.asStateFlow()

    /** A-主方案：当前正在安装/卸载的 Bundle ID，每 startInstallSession 时注入。 */
    private var currentSessionBundleId: TerminalBundleId? = null

    /** 并发槽数：安装开始前一次性注入并画 N 个空槽。 */
    suspend fun startInstallSession(
        slots: Int,
        totalDependsEstimate: Int,
        bundleId: TerminalBundleId? = null,
    ) {
        mutex.withLock {
            // A-主方案：先把「上一个正在操作的 state」快照塞进 ArchiveStore，
            // 避免"装完 A 立刻装 B，A 的终态快照丢失"的边界问题。
            if (currentSessionBundleId != null) {
                runCatching {
                    GlobalInstallArchiveStore.saveSnapshot(currentSessionBundleId!!, _state.value)
                }
            }
            currentSessionBundleId = bundleId
            // reset
            phase = InstallPhase.DOWNLOAD
            source = ProgressSource.PREFETCH_MANAGER_CONFIRMED
            installingDone = 0
            installingTotal = if (totalDependsEstimate > 0) totalDependsEstimate else 0
            installingCurrent = null
            postHookLinesDone = 0
            postHookLinesTotal = 0
            finishStats = null
            failSummary = null
            speedEmaBps = 0f
            lastTrafficRxBytes = -1L
            installStartWallClockMs = System.currentTimeMillis()
            lastTickWallClockMs = installStartWallClockMs
            loggedDlingPkgs.clear()
            loggedFinalPkgs.clear()
            failedByReason.clear()
            logRing.clear()
            logSeqId = 0L
            slotsSnapshot = List(slots) { i ->
                DownloadSlot(
                    id = i, pkgName = null, bytesTotal = null,
                    bytesGot = 0L, speedBps = 0f, status = SlotStatus.WAITING,
                )
            }
            appendLogLine(LogLineKind.INFO, "开始安装：$slots 路并行预取 · 估算 $totalDependsEstimate 个依赖")
            publish()
        }
        ensureTickerStarted()
    }

    /**
     * 结束一次安装会话。
     *
     * @param forceFailSnapshot
     *   true 表示本次会话明确失败（apk exit!=0 或有 failedReason），需要立刻把 UI 快照切到
     *   FAILED phase 并把 slots 清空 + speed 归零，避免用户看到「FAILED 0%」但顶栏依然显示
     *   「X 槽并行 · Y KB/s」好像还在继续占网占线程的假像（RC61c 用户截图事故）。
     *   false 走原来的语义：停 ticker 不强制改状态（DONE 分支用）。
     */
    fun endSession(forceFailSnapshot: Boolean = false) {
        runCatching { tickerJob?.cancel() }
        tickerJob = null
        if (!forceFailSnapshot) return
        runCatching {
            // suspend 版本 publish 包在 suspend 函数里用更干净；这里 endSession 是普通函数，
            // 用 scope launch 保证 Mutex + publish 都在正确调度器上跑。
            scope.launch {
                mutex.withLock {
                    // 把所有可变状态切成"失败后静止"的快照：
                    //  phase=FAILED
                    //  slotsSnapshot=全空 WAITING（UI 「N 槽并行」显示为 N 个灰方块，且 N=0 更好，
                    //    但为了对齐 BundleLogDialog 顶部 header 用 slots.size 取 N，这里如果 slots
                    //    原本是 5 个保持大小，但全部置 WAITING，就不会显示任何 DLING/包名了）
                    //  speedEmaBps = 0（header 的「速率」立刻显示 0）
                    //  finishStats 清空 / failSummary 置为「安装失败」
                    //  然后 publish 一次 → UI 所有指标立刻刷新
                    phase = InstallPhase.FAILED
                    source = ProgressSource.APK_STDOUT_CONFIRMED
                    slotsSnapshot = slotsSnapshot.map {
                        it.copy(
                            pkgName = null, bytesTotal = null, bytesGot = 0L, speedBps = 0f,
                            status = SlotStatus.WAITING, failReason = null,
                        )
                    }
                    speedEmaBps = 0f
                    lastTrafficRxBytes = -1L
                    installingCurrent = null
                    finishStats = null
                    failSummary = failSummary
                        ?: if (_state.value.failSummary.isNullOrBlank()) "安装失败（已停止所有并发下载）" else _state.value.failSummary
                    // 额外打 1 条 INFO 日志进 Ring（日志最末尾出现一条"会话结束"，用户 copyAll 能看见）
                    appendLogLine(
                        kind = LogLineKind.INFO,
                        text = "安装会话结束（失败态）：已释放所有并发槽与网络连接",
                    )
                    publish()
                }
            }
        }
    }

    // ─── 对外推送真实信号（全部 Mutex 保护，可跨线程调用） ───

    suspend fun onSlotsFromPrefetch(newSlots: List<DownloadSlot>) = mutex.withLock {
        if (phase != InstallPhase.DOWNLOAD) return@withLock
        slotsSnapshot = newSlots
        // Fix C：只对「diff 上一次状态有变动的 slot」打日志；DLING 包名去重；FAILED 聚合后统一输出。
        for (s in newSlots) {
            val pkg = s.pkgName ?: continue
            when (s.status) {
                SlotStatus.DONE -> {
                    if (pkg in loggedFinalPkgs) continue
                    loggedFinalPkgs += pkg
                    val inline = when (s.bytesTotal) {
                        null -> 1f
                        0L -> 1f
                        else -> (s.bytesGot.toDouble() / s.bytesTotal).coerceIn(0.0, 1.0).toFloat()
                    }
                    // D1 Fix：前缀只由 UI 的 prefixFor() 决定，text 不再重复拼 ⬇ → 不再出现 ⬇⬇ 双箭头
                    appendLogLine(
                        kind = LogLineKind.FETCH,
                        text = "$pkg ✓",
                        inlineProgress = inline,
                        inlineSpeedBps = s.speedBps,
                    )
                }
                SlotStatus.FAILED -> {
                    if (pkg in loggedFinalPkgs) continue
                    loggedFinalPkgs += pkg
                    // 不立刻打 ERROR 单条，进入聚合 Map
                    val reason = s.failReason ?: "unknown"
                    failedByReason.getOrPut(reason) { mutableListOf() }.add(pkg)
                }
                SlotStatus.DLING -> {
                    if (pkg in loggedDlingPkgs) continue
                    loggedDlingPkgs += pkg
                    val inline = when (s.bytesTotal) {
                        null -> -1f
                        0L -> 0f
                        else -> (s.bytesGot.toDouble() / s.bytesTotal).coerceIn(0.0, 1.0).toFloat()
                    }
                    // D1 Fix：⬇ 前缀由 UI prefixFor() 统一加，避免 ⬇⬇ 双箭头重复
                    appendLogLine(
                        LogLineKind.FETCH,
                        pkg,
                        inlineProgress = inline,
                        inlineSpeedBps = s.speedBps,
                    )
                }
                SlotStatus.WAITING -> { /* noop */ }
            }
        }
        publish()
    }

    /**
     * Fix C：prefetch 全部结束时调用一次（在收到 ParallelPrefetchManager.PrefetchEvent.Finished 之后）。
     * 把相同 reason 的批量失败合并成 1 条日志输出，不 N 条刷屏。
     */
    suspend fun flushPrefetchFailures(
        failedPackages: Map<String, String>,
    ) = mutex.withLock {
        // 先把没通过 SlotUpdate 进来的包也并入聚合 Map（兜底：万一静默 slot 更新，也要让它们进入 summary）
        for ((pkg, reason) in failedPackages) {
            if (pkg in loggedFinalPkgs) continue
            loggedFinalPkgs += pkg
            failedByReason.getOrPut(reason) { mutableListOf() }.add(pkg)
        }
        if (failedByReason.isEmpty()) return@withLock
        for ((reason, pkgs) in failedByReason) {
            if (pkgs.isEmpty()) continue
            val preview = pkgs.take(3).joinToString(", ")
            val more = pkgs.size - 3
            val suffix = if (more > 0) " 等 ${pkgs.size} 个包（前 3：$preview）" else "：$preview"
            appendLogLine(
                LogLineKind.ERROR,
                "预取跳过 $suffix，原因：$reason（交给 apk 自己 fetch）",
            )
        }
        failedByReason.clear()
        publish()
    }

    suspend fun onApkLine(raw: String) = mutex.withLock {
        val parsed = ApkStdoutParser.parse(raw)
        val sem = parsed.semantic
        when (sem) {
            is ApkStdoutParser.Semantic.Fetch -> {
                source = ProgressSource.TRAFFIC_STATS_ESTIMATED
                // D1 Fix：⬇ 前缀由 UI prefixFor() 统一加
                appendLogLine(
                    LogLineKind.FETCH,
                    if (sem.isIndex) "${sem.pkgName} (repo index)" else "apk ${sem.pkgName}",
                    inlineProgress = -1f,
                    inlineSpeedBps = sem.rateMiBps?.times(1024f * 1024f) ?: 0f,
                )
            }
            is ApkStdoutParser.Semantic.Installing -> {
                phase = InstallPhase.INSTALL
                installingDone = sem.n
                installingTotal = maxOf(installingTotal, sem.total)
                installingCurrent = sem.pkg
                source = ProgressSource.APK_STDOUT_CONFIRMED
                // D1 Fix：⚙ 前缀由 UI prefixFor() 统一加
                appendLogLine(
                    LogLineKind.INSTALL_CURR,
                    "(${sem.n}/${installingTotal}) Installing ${sem.pkg} ${sem.ver.orEmpty()}",
                )
            }
            is ApkStdoutParser.Semantic.Ok -> {
                if (phase < InstallPhase.POST_HOOK) phase = InstallPhase.INSTALL
                val mibD = sem.downloadMiB
                val mibI = sem.installedMiB
                if (mibD != null) budgetBytesTotal = (mibD * 1024 * 1024).toLong().coerceAtLeast(1L)
                // D1 Fix：ⓘ 前缀由 UI prefixFor() 统一加
                appendLogLine(
                    LogLineKind.INFO,
                    buildString {
                        append("OK: ${sem.packages} packages")
                        if (mibD != null) append(", ${String.format("%.1f", mibD)} MiB ↓")
                        if (mibI != null) append(", ${String.format("%.1f", mibI)} MiB /")
                    },
                )
            }
            is ApkStdoutParser.Semantic.Error -> {
                phase = InstallPhase.FAILED
                failSummary = sem.reason
                // D1 Fix：✗ 前缀由 UI prefixFor() 统一加
                appendLogLine(LogLineKind.ERROR, "apk ERROR: ${sem.reason}")
            }
            is ApkStdoutParser.Semantic.PostLine -> {
                if (phase < InstallPhase.POST_HOOK) phase = InstallPhase.POST_HOOK
                appendLogLine(LogLineKind.POST_HOOK, sem.text)
            }
            null -> {
                // B-3：二级归一化修复 —— 基于 text 关键字兜底分类，防止其他类似 APKINDEX.tar.gz 分类错误
                //     （比如镜像输出格式变化导致正则没命中，但语义能一眼识别）
                val t = raw.trim()
                val repairedKind: LogLineKind = when {
                    // fetch 开头但正则没命中 → FETCH（比如 HTTPS、fetch + 缩写 URL 等变体）
                    t.startsWith("fetch ", ignoreCase = true) -> LogLineKind.FETCH
                    // vN.N.N-XX-gXXXXXXXXXXX [http://...] —— 典型 repo index 版本行，归 INFO
                    t.startsWith("v") && t.contains("-g") && t.contains("[http", ignoreCase = true) -> LogLineKind.INFO
                    // "N distinct packages available" —— 版本信息汇总
                    t.endsWith("distinct packages available", ignoreCase = true) ||
                        t.contains("packages available", ignoreCase = true) -> LogLineKind.INFO
                    // "* If you need ICU..." —— post-install hook 提示文本
                    t.startsWith("* ") || t.startsWith("· ") -> LogLineKind.INFO
                    // "unsatisfiable constraints" / "missing" —— apk ERROR 关键字行，即使没 "ERROR:" 前缀也算 ERROR
                    t.contains("unsatisfiable constraints", ignoreCase = true) ||
                        t.contains("(missing)", ignoreCase = true) ||
                        t.contains(" not available", ignoreCase = true) -> LogLineKind.ERROR
                    // "Configuring pkg" / "Triggering" 没被 RE_POST 命中的变体 → POST_HOOK
                    t.startsWith("Configuring ", ignoreCase = true) ||
                        t.startsWith("Triggering ", ignoreCase = true) ||
                        t.startsWith("Updating ", ignoreCase = true) -> LogLineKind.POST_HOOK
                    // POST_HOOK 阶段的普通文本：语义上就是 post-hook 输出，不落到 INFO 避免统计口径错位
                    phase == InstallPhase.POST_HOOK -> LogLineKind.POST_HOOK
                    // INSTALL 阶段纯文本：大概率是安装中上下文提示，归 INFO 不污染安装计数
                    else -> LogLineKind.INFO
                }
                appendLogLine(repairedKind, raw)
            }
        }
        publish()
    }

    suspend fun onExitCode(code: Int?, postHookDone: Boolean) = mutex.withLock {
        if (phase.isTerminal) return@withLock
        if (code == 0 && postHookDone) {
            phase = InstallPhase.DONE
            val elapsed = System.currentTimeMillis() - installStartWallClockMs
            finishStats = FinishStats(
                elapsedMs = elapsed,
                packagesInstalled = installingTotal.coerceAtLeast(installingDone),
                bytesDownloaded = slotsSnapshot.sumOf { it.bytesGot },
                bytesInstalledOnDisk = -1L,
                concurrencySlots = slotsSnapshot.size,
            )
            appendLogLine(
                LogLineKind.INFO,
                "✨ 已就绪 · 耗时 ${String.format("%.1f", elapsed / 1000f)}s · ${installingTotal.coerceAtLeast(1)} 个包",
            )
        } else if (code != null && code != 0) {
            phase = InstallPhase.FAILED
            if (failSummary == null) failSummary = "安装失败 exit=$code"
            appendLogLine(LogLineKind.ERROR, "exit=$code，安装未成功")
        } // 否则 phase 还在 POST_HOOK，继续等待 postHookDone 消息
        publish()
    }

    suspend fun setPostHookPlan(totalLines: Int) = mutex.withLock {
        if (phase == InstallPhase.POST_HOOK) {
            postHookLinesTotal = totalLines
            postHookLinesDone = 0
            publish()
        }
    }

    suspend fun advancePostHookLine() = mutex.withLock {
        if (phase == InstallPhase.POST_HOOK && postHookLinesTotal > 0) {
            postHookLinesDone = (postHookLinesDone + 1).coerceAtMost(postHookLinesTotal)
            appendLogLine(LogLineKind.POST_HOOK, "post-hook ($postHookLinesDone/$postHookLinesTotal)")
            publish()
        }
    }

    /** POST_HOOK 阶段开启：apk stdout OK 之后、跑自定义脚本之前调用。 */
    suspend fun enterPostHook(totalLines: Int) = mutex.withLock {
        phase = InstallPhase.POST_HOOK
        postHookLinesTotal = totalLines
        postHookLinesDone = 0
        appendLogLine(LogLineKind.POST_HOOK, "执行 $totalLines 行后处理脚本…")
        publish()
    }

    // ─── Tick 循环（100ms）：TrafficStats 采样 + EMA + ETA + 发 StateFlow 快照 ───

    private fun ensureTickerStarted() {
        if (tickerJob != null) return
        tickerJob = scope.launch {
            val uid = android.os.Process.myUid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            while (true) {
                delay(TICK_MS)
                mutex.withLock {
                    val now = System.currentTimeMillis()
                    val dt = (now - lastTickWallClockMs).coerceAtLeast(1L)
                    lastTickWallClockMs = now

                    // 4. TrafficStats UID rx bytes → 瞬时速率 + EMA
                    val rxBytes = runCatching { TrafficStats.getUidRxBytes(uid) }
                        .getOrNull()
                        ?.takeIf { it >= 0 }
                    if (rxBytes != null) {
                        if (lastTrafficRxBytes >= 0 && rxBytes >= lastTrafficRxBytes) {
                            val delta = rxBytes - lastTrafficRxBytes
                            val instantBps = delta * 1000f / dt
                            speedEmaBps = if (speedEmaBps == 0f) instantBps
                            else EMA_ALPHA * instantBps + (1f - EMA_ALPHA) * speedEmaBps
                        }
                        lastTrafficRxBytes = rxBytes
                    }
                    if (phase == InstallPhase.DOWNLOAD && speedEmaBps < 1f) {
                        // 看各槽平均：如果槽里上报了更高的速率，用它
                        val maxSlot = slotsSnapshot.maxOfOrNull { it.speedBps } ?: 0f
                        if (maxSlot > speedEmaBps) speedEmaBps = maxSlot
                    }
                    publish()
                }
            }
        }
    }

    // ─── publish：把内部状态 → AggregateProgressState 快照 ───

    private fun publish() {
        val phaseWeight: Float = when (phase) {
            InstallPhase.DOWNLOAD -> {
                val (got, total) = summarizeDownloadBytes()
                if (total <= 0L) 0f else (got.toDouble() / total).coerceIn(0.0, 1.0).toFloat() * phase.weight
            }
            InstallPhase.INSTALL -> {
                val instPct = if (installingTotal <= 0) 0f
                else (installingDone.toFloat() / installingTotal).coerceIn(0f, 1f)
                phase.weight + instPct * phase.weight /* placeholder，下面重算 */
                // 真正算法：前两阶段的 weight 累加；我们下面单独算。
            }
            InstallPhase.POST_HOOK -> {
                val pct = if (postHookLinesTotal <= 0) 0.5f
                else (postHookLinesDone.toFloat() / postHookLinesTotal).coerceIn(0f, 1f)
                InstallPhase.DOWNLOAD.weight + InstallPhase.INSTALL.weight + pct * InstallPhase.POST_HOOK.weight
            }
            InstallPhase.DONE -> 1f
            InstallPhase.FAILED -> _state.value.total // 失败保留当前进度值
        }
        val realPct = when (phase) {
            InstallPhase.DOWNLOAD -> {
                val (got, total) = summarizeDownloadBytes()
                if (total <= 0L) {
                    // Budget 未知：用「完成槽位占比」× weight 估算
                    val done = slotsSnapshot.count { it.status == SlotStatus.DONE }
                    val n = slotsSnapshot.size.coerceAtLeast(1)
                    (done.toFloat() / n) * phase.weight
                } else {
                    (got.toDouble() / total).coerceIn(0.0, 1.0).toFloat() * phase.weight
                }
            }
            InstallPhase.INSTALL -> {
                val instPct = if (installingTotal <= 0) 0f
                else (installingDone.toFloat() / installingTotal).coerceIn(0f, 1f)
                InstallPhase.DOWNLOAD.weight + instPct * phase.weight
            }
            InstallPhase.POST_HOOK -> phaseWeight // 上面算过了
            InstallPhase.DONE -> 1f
            InstallPhase.FAILED -> _state.value.total
        }
        val elapsed = System.currentTimeMillis() - installStartWallClockMs
        val etaMs: Long? = run {
            if (phase == InstallPhase.DONE || phase == InstallPhase.FAILED) return@run null
            val speed = speedEmaBps
            if (speed < 1024f) return@run null // <1KB/s 认为卡住不估算
            if (phase == InstallPhase.DOWNLOAD) {
                val (got, total) = summarizeDownloadBytes()
                val remain = (total - got).coerceAtLeast(0L)
                if (total <= 0L) return@run null
                (remain / speed * 1000L).roundToLong()
            } else if (phase == InstallPhase.INSTALL) {
                if (installingTotal <= 0) return@run null
                val pctDone = installingDone.toFloat() / installingTotal
                val totalEstimate = if (pctDone > 0.05f) (elapsed / pctDone).toLong() else 30_000L
                (totalEstimate - elapsed).coerceAtLeast(0L)
            } else {
                null
            }
        }
        val snap = AggregateProgressState(
            phase = phase,
            total = realPct,
            source = source,
            slots = slotsSnapshot,
            installingDone = installingDone,
            installingTotal = installingTotal,
            installingCurrent = installingCurrent,
            elapsedMs = elapsed,
            etaMs = etaMs,
            currentSpeedBps = speedEmaBps,
            logLines = logRing.snapshot(),
            revision = logRing.revision,
            finishStats = finishStats,
            failSummary = failSummary,
        )
        _state.value = snap
        // A-主方案：每 publish 就把当前快照更新进 ArchiveStore，
        // 保证用户切到别的 Bundle 再切回来，看到的是"尽量实时"的，
        // 而不是仅终态才写入（中间进度丢失）。
        currentSessionBundleId?.let { bid ->
            runCatching {
                GlobalInstallArchiveStore.updateSnapshot(bid, snap)
            }
        }
    }

    /** 下载阶段汇总（got bytes / total bytes）。total 未知为 0，调用方用 fallback 百分比估算。 */
    private fun summarizeDownloadBytes(): Pair<Long, Long> {
        var got = 0L
        var total = 0L
        var unknownCount = 0
        for (s in slotsSnapshot) {
            got += s.bytesGot.coerceAtLeast(0L)
            val t = s.bytesTotal
            if (t == null) unknownCount++ else total += t
        }
        if (total <= 0L) {
            // budget 整体未知：给调用方返回 (0, 0)，走槽位完成比例 fallback
            return got to 0L
        }
        // 有部分包 content-length 未知，把它按「已知总大小 / 已知包数 × 未知包数」匀一下，避免总预算偏小
        if (unknownCount > 0 && slotsSnapshot.size > unknownCount) {
            val known = slotsSnapshot.size - unknownCount
            val avg = total / known
            total += avg * unknownCount
        }
        total = total.coerceAtLeast(got)
        return got to total
    }

    private fun appendLogLine(kind: LogLineKind, text: String, inlineProgress: Float = -1f, inlineSpeedBps: Float = 0f) {
        val line = LogLine(
            id = ++logSeqId,
            kind = kind,
            text = text,
            inlineProgress = inlineProgress,
            inlineSpeedBps = inlineSpeedBps,
        )
        // B-方案强点：统一入口自动注入 ownerBundleId，避免调用方忘记打标签
        logRing.append(line, currentSessionBundleId)
    }
}
