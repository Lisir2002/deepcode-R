package com.R.codecore.feature.chatrender

/**
 * 任务组内二级片段类型（与 agent 层 [com.R.codecore.feature.agent.presentation.TaskSubGroupType]
 * 一一对应，但定义在本模块以保持解耦，由接入方映射转换）。
 */
enum class BubbleSubGroupType {
    /** 用户消息（本轮请求）。 */
    USER,
    /** 思考过程（reasoning 块）。 */
    REASONING,
    /** 助手文本回复。 */
    REPLY,
    /** 工具调用。 */
    TOOL
}
