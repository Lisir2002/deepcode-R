package com.R.codecore.feature.agent.data.local.entity

/**
 * 统一唤醒队列（WakeQueue，SCHEMA v48 新增）。
 *
 * 单一 Room 队列承载 #4 hook 后台审查结果与 #10 耗时任务结果（一套机制两处消费），
 * 下轮会话开始前注入 system-reminder + 消费确认（防重复/防丢失），Room 持久化支撑
 * App 被杀后下次启动重扫待注入队列继续唤醒。
 *
 * 设计依据：docs/plan-docs/claude-code-study-design.md 第 11 节（11.3 asyncRewake 下轮注入）
 * 与第 16 节（16.2 统一 WakeQueue）。
 *
 * @param wakeId 唤醒唯一标识（UUID）。
 * @param sessionId 归属会话；空串表示全局（不按会话过滤）。
 * @param source 来源标识（如 `hook.commit-discipline` / 耗时任务名）。
 * @param type 事件类型（如 `post-tool-use` / `stop`）。
 * @param content 唤醒内容（结构化文本，注入时原样拼入 system-reminder）。
 * @param status 队列状态：[STATUS_PENDING] 待注入 / [STATUS_CONSUMED] 已注入。
 * @param createdAtMs 入队时间（毫秒）。
 */

data class WakeItemEntity(
    
    
    val wakeId: String,

    
    val sessionId: String,

    
    val source: String,

    
    val type: String,

    
    val content: String,

    
    val status: String,

    
    val createdAtMs: Long
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONSUMED = "CONSUMED"
    }
}
