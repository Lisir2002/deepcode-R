package com.R.codecore.feature.agent.data.local.entity

/**
 * 文件编辑快照（F-3 hunk 落库）：记录一次 readFile/writeFile/editFile 造成的文件变化，
 * 持久化 hunk 差异与旧/新内容快照，为「撤销编辑」等能力提供数据基础。
 */

data class FileEditHunkEntity(
     val id: String,
    val sessionId: String,
    val filePath: String,
    /** read / write / edit 之一。 */
    val operation: String,
    /** unified diff 文本（hunk）。 */
    val hunk: String,
    /** 操作前的内容快照（大文件可截断）。 */
    val oldContent: String,
    /** 操作后的内容快照。 */
    val newContent: String,
    val createdAtMs: Long
)
