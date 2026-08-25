package com.R.codecore.feature.agent.domain.input

import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider

/**
 * 意图问判注入（D0-3，对齐 norm-chain §3.10.2）：step 前注入「意图问判三问」，
 * 模型每轮开始内省核对——理解用户要什么、打算怎么拆、应落哪个形态；
 * 低置信（理解不确定 / 形态不确定）时主动调 askUserQuestion 澄清，不猜着做。
 *
 * importance=P1 常规（§3.1.2 八源排序中紧随 goal 之后），可被注入预算裁剪。
 * 无状态、纯文本，注入到统一 step 前注入块（StatefulAgentWorkflow CallLlm 段）。
 */
class IntentAskSource : SystemPromptProvider.PromptSource {

    override fun build(ctx: AgentContext): String? = BLOCK

    private companion object {
        const val TAG = "IntentAskSource"
        val BLOCK = """
            【意图问判】本轮开始请先内省核对：
            ① 我的理解：用户到底要什么？
            ② 我的拆解思路：打算怎么拆解、怎么做？
            ③ 应落哪个形态：goal / plan / jobs / schedule / playbook / 普通对话？
            低置信（理解不确定或形态不确定）时用 askUserQuestion 向用户澄清，不要猜着做。
        """.trimIndent()
    }
}
