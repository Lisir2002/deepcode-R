package com.deep.rcode.feature.agent.domain.memory

import java.io.File

/**
 * 解析后的单个 Memory 模型。
 *
 * @param name 记忆名称（供大模型调用的唯一标识，通常对应文件名不含扩展名）
 * @param description 记忆描述（一句话摘要，注入到系统提示词中）
 * @param scope 记忆的作用域（GLOBAL 或 PROJECT）
 * @param file 记忆对应的本地文件
 * @param content 记忆正文（剥离 Frontmatter 后的详细内容）
 * @param tags 记忆标签（M-3：save 时声明，list 按 tag 过滤）
 * @param accessCount 访问次数（M-4：read/注入命中时递增，列表按降序展示）
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String,
    val tags: List<String> = emptyList(),
    val accessCount: Int = 0
)

enum class MemoryScope {
    GLOBAL, PROJECT
}
