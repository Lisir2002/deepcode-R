package com.core.deepcode.feature.agent.domain.tool

/**
 * L2 共享会话状态：编辑器选区快照。
 *
 * 相比 [com.core.deepcode.feature.agent.domain.model.AgentContext.selectedCode] 的裸字符串，
 * 这里额外携带来源文件与行列区间，供工具（如 EditFileTool）在无 UI 上下文时也能定位选区。
 */
data class CodeSelection(
    /** 选区所在文件路径（相对 workspace），可能为 null（如临时输入框）。 */
    val file: String? = null,
    /** 选中的代码文本。 */
    val code: String,
    /** 选区起始行（1-based），未知时为 null。 */
    val startLine: Int? = null,
    /** 选区结束行（1-based），未知时为 null。 */
    val endLine: Int? = null
)
