package com.deep.rcode.feature.agent.domain.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource
) {
    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。 */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            val projectSource = ProjectMemorySource(projectRoot)
            allMemories.addAll(projectSource.listMemories())
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）
        return allMemories
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val projectSource = ProjectMemorySource(projectRoot)
            val content = projectSource.loadContent(name)
            if (content != null) return content
        }
        // 回退到全局读取
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        projectRoot: String?,
        tags: List<String> = emptyList()
    ): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content, tags)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else ProjectMemorySource(projectRoot).saveMemory(name, description, content, tags)
            }
        }
    }

    /** M-4：记录一次记忆访问命中（read 命中时递增访问次数）。项目级优先，未命中回退全局。 */
    fun recordAccess(name: String, projectRoot: String?) {
        if (!projectRoot.isNullOrBlank()) {
            val projectSource = ProjectMemorySource(projectRoot)
            val exists = projectSource.listMemories().any { it.name.equals(name, ignoreCase = true) }
            if (exists) {
                projectSource.recordAccess(name)
                return
            }
        }
        globalMemorySource.recordAccess(name)
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else ProjectMemorySource(projectRoot).editMemory(name, edits)
            }
        }
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else ProjectMemorySource(projectRoot).deleteMemory(name)
            }
        }
    }
}
