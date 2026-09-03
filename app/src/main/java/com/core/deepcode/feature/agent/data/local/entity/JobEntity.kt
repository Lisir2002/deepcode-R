package com.core.deepcode.feature.agent.data.local.entity

import com.core.deepcode.core.util.EnumSafe

/**
 * 会话级「后台任务」持久化（对齐 DSH JobStart/JobRegistry）：
 * 长任务（容器内编译/测试/构建、远程同步）状态落库，App 切后台/进程回收不丢；
 * 进程句柄放内存注册表（回收即置 [JobStatus.INTERRUPTED]，恢复提示重跑）。
 *
 * 表名 agent_jobs，agent 库 v1→v2 新增（见 AgentDatabaseMigrations）。
 */

data class JobEntity(
     val jobId: String,
    val sessionId: String,
    /** 任务类型：如 container（容器命令）/ ssh（远程命令）。 */
    val kind: String,
    /** 人类可读标题（展示/日志用）。 */
    val title: String,
    /** [JobStatus] 名称。 */
    
    val status: String = JobStatus.RUNNING.name,
    /** 退出码：null 表示未知/未结束（进程被回收等）。 */
    val exitCode: Int? = null,
    /** 输出定位器（如日志文件路径 / 内存缓冲区 key）；空串表示无独立输出。 */
    
    val outputLocator: String = "",
    /** 创建时间毫秒。 */
    val createdAtMs: Long,
    /** 结束时间毫秒（RUNNING 前为 null）。 */
    val finishedAtMs: Long? = null,
    /** 最后一次更新时间毫秒。 */
    val updatedAtMs: Long
) {
    fun statusEnum(): JobStatus =
        EnumSafe.valueOf(status, JobStatus.RUNNING, tag = "JobEntity.status")
}

/** 后台任务生命周期状态（对齐 DSH job status）。 */
enum class JobStatus {
    /** 运行中（或已启动未结束）。 */
    RUNNING,
    /** 正常完成（exitCode == 0 语义由调用方保证）。 */
    DONE,
    /** 执行失败。 */
    FAILED,
    /** 被 kill（job_kill）。 */
    KILLED,
    /** 进程被回收/宿主不可用导致中断，恢复需重跑。 */
    INTERRUPTED
}
