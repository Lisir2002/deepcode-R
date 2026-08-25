package com.R.codecore.feature.agent.domain.tool

/**
 * L7 事件总线：核心事件类型全集。
 *
 * 命名空间格式 {namespace}.{action}，四大命名空间：file / todo / cache / state。
 * 所有事件携带通用元数据（source / sessionId / timestamp / depth / causalChain），
 * 供循环防护（深度计数 + 同源去重 + 因果链检测）与事件日志使用。
 */
sealed class ToolEvent(
    open val source: String,
    open val sessionId: String? = null,
    open val timestamp: Long = System.currentTimeMillis(),
    open val depth: Int = 0,
    open val causalChain: List<String> = emptyList()
) {
    /** 事件类型（如 "file.edited"）。 */
    abstract val type: String

    // ---------- file 命名空间 ----------

    /** 文件读取完成。 */
    data class FileRead(
        val path: String,
        val readBytes: Int,
        override val source: String = "ReadFileTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "file.read"
    }

    /** 文件编辑完成。订阅者：ToolResultCache（失效）、TodoTool（联动）。 */
    data class FileEdited(
        val path: String,
        val oldHash: String?,
        val newHash: String,
        val diffSummary: String,
        override val source: String = "EditFileTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "file.edited"
    }

    /** 文件写入完成。订阅者：ToolResultCache（失效）。 */
    data class FileWritten(
        val path: String,
        val size: Long,
        val hash: String,
        override val source: String = "WriteFileTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "file.written"
    }

    /** 文件删除完成。订阅者：ToolResultCache（失效）。 */
    data class FileDeleted(
        val path: String,
        override val source: String = "FileTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "file.deleted"
    }

    /**
     * 文件系统可能被任意命令（如 Bash）变更，具体影响文件不可静态判定。
     * 订阅者：ToolResultCache（失效所有文件类缓存，保守保证缓存新鲜度）。
     */
    data class FileSystemMutated(
        val reason: String = "",
        override val source: String = "ExecuteCommandTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "file.mutated"
    }

    // ---------- todo 命名空间 ----------

    /** 待办创建。订阅者：MemoryTool（更新记忆）、ToolSessionState（刷快照）。 */
    data class TodoCreated(
        val todoId: String,
        val title: String,
        val listId: String,
        override val source: String = "TodoTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "todo.created"
    }

    /** 待办更新。 */
    data class TodoUpdated(
        val todoId: String,
        val changedFields: List<String>,
        val fullList: List<com.R.codecore.feature.agent.domain.model.TodoItem>,
        override val source: String = "TodoTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "todo.updated"
    }

    /** 待办完成。 */
    data class TodoCompleted(
        val todoId: String,
        val completedAt: Long,
        val fullList: List<com.R.codecore.feature.agent.domain.model.TodoItem>,
        override val source: String = "TodoTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "todo.completed"
    }

    /** 待办重新打开。 */
    data class TodoReopened(
        val todoId: String,
        val reopenedAt: Long,
        val fullList: List<com.R.codecore.feature.agent.domain.model.TodoItem>,
        override val source: String = "TodoTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "todo.reopened"
    }

    /** 待办重排。 */
    data class TodoReordered(
        val listId: String,
        val newOrder: List<String>,
        val fullList: List<com.R.codecore.feature.agent.domain.model.TodoItem>,
        override val source: String = "TodoTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "todo.reordered"
    }

    /** 待办删除。 */
    data class TodoDeleted(
        val todoId: String,
        val fullList: List<com.R.codecore.feature.agent.domain.model.TodoItem>,
        override val source: String = "TodoTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "todo.deleted"
    }

    // ---------- cache 命名空间 ----------

    /** 缓存失效。订阅者：所有缓存消费者。 */
    data class CacheInvalidated(
        val key: String?,
        val pattern: String?,
        override val source: String = "ToolResultCache",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "cache.invalidated"
    }

    /** 缓存清空。 */
    data class CacheCleared(
        val namespace: String,
        override val source: String = "ToolResultCache",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "cache.cleared"
    }

    // ---------- state 命名空间 ----------

    /** 记忆更新。订阅者：SystemPromptProvider（增量刷新）。 */
    data class StateMemoryUpdated(
        val memoryKey: String,
        val summary: String,
        override val source: String = "MemoryTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "state.memory.updated"
    }

    /** 技能加载。订阅者：ToolRegistry（刷新定义）、SystemPromptProvider（增量刷新）。 */
    data class StateSkillLoaded(
        val skillName: String,
        val toolCount: Int,
        override val source: String = "LoadSkillTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "state.skill.loaded"
    }

    /** 模式切换。订阅者：SystemPromptProvider（增量刷新）。 */
    data class StateModeChanged(
        val from: String,
        val to: String,
        val reason: String,
        override val source: String = "SwitchModeTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "state.mode.changed"
    }

    /** 会话任务目标变更（goal 工具触发，action=get 不广播）。订阅者：会话快照 / 目标状态缓存。 */
    data class GoalChanged(
        val goalId: String,
        val status: String,
        override val source: String = "GoalTool",
        override val sessionId: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "state.goal.changed"
    }

    /** 会话清空。订阅者：所有层（清空状态）。 */
    data class StateSessionCleared(
        override val sessionId: String,
        override val source: String = "SessionManager",
        override val timestamp: Long = System.currentTimeMillis(),
        override val depth: Int = 0,
        override val causalChain: List<String> = emptyList()
    ) : ToolEvent(source, sessionId, timestamp, depth, causalChain) {
        override val type: String = "state.session.cleared"
    }
}

/**
 * L7 事件监听器：动态订阅回调。
 * 工具实现本接口走声明式订阅（[AgentTool.subscribedEvents]），外部消费者通过
 * [ToolEventBus.subscribe] 走动态订阅。
 */
fun interface ToolEventListener {
    suspend fun onEvent(event: ToolEvent)
}
