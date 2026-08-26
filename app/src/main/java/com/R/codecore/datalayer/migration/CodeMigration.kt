package com.R.codecore.datalayer.migration

import app.cash.sqldelight.db.SqlDriver

/**
 * 代码式数据转换迁移单元（设计 §5.1 / §5.6）。
 *
 * 与 SQLDelight 的 .sqm（DDL）链配合：每个版本一个「迁移单元」，DDL 由 .sqm 应用后，
 * 同版本的数据转换逻辑（行重组 / 字段拆分 / 旧值清洗）用本类补充。
 * 因 DDL 已在单元内先行应用，block 面向「该版本最终 schema」编写。
 */
data class CodeMigration(
    val from: Int,
    val to: Int,
    val block: SqlDriver.() -> Unit,
)
