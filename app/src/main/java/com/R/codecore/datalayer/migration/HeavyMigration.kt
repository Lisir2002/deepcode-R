package com.R.codecore.datalayer.migration

/**
 * 标记「重版本 / 危险迁移」（设计 §5.3）。
 * 被标注的迁移除文件级快照外，叠加逻辑备份（SQL dump / 表级导出），可跨版本、可选择性、可审计恢复。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class HeavyMigration
