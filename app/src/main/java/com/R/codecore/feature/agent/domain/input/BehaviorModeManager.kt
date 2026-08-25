package com.R.codecore.feature.agent.domain.input

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 行为模式会话级状态（D0-6，对齐 norm-chain §3.10 增量 5）：
 *
 * 四档 behaviorMode（与五形态正交）——design（只设计不写码）/ execute（默认执行）/
 * research（只读调研）/ chat（纯问答）。默认每轮按新输入重判（意图变则模式自然变）；
 * 显式指令（`/mode` 切换、`?` 咨询标记）把模式**锁定**到会话级覆盖，直到显式解除。
 *
 * 内存缓存（对齐 ToolSessionState 会话级内存态定位），进程重启后回到按输入重判。
 */
@Singleton
class BehaviorModeManager @Inject constructor() {

    private val overrides = ConcurrentHashMap<String, String>()

    companion object {
        const val MODE_DESIGN = "design"
        const val MODE_EXECUTE = "execute"
        const val MODE_RESEARCH = "research"
        const val MODE_CHAT = "chat"
        const val MODE_DEFAULT = "default"

        val MODES = listOf(MODE_DESIGN, MODE_EXECUTE, MODE_RESEARCH, MODE_CHAT)

        fun isValidMode(mode: String): Boolean = MODES.contains(mode)
    }

    /** 设置会话级行为模式覆盖（锁定）。非法值忽略。 */
    fun setOverride(sessionId: String, mode: String) {
        if (isValidMode(mode)) overrides[sessionId] = mode
    }

    /** 解除会话级行为模式覆盖（恢复按输入重判）。 */
    fun clearOverride(sessionId: String) {
        overrides.remove(sessionId)
    }

    /** 会话当前生效的显式行为模式覆盖；无覆盖返回 null（按输入重判）。 */
    fun overrideFor(sessionId: String): String? = overrides[sessionId]
}
