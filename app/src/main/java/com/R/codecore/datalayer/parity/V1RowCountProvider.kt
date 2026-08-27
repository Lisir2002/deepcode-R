package com.R.codecore.datalayer.parity

/**
 * V1（Room 域库）行数读取抽象（v2-full-takeover P1-3）。
 *
 * 由 Android 实现基于 5 个 Room 域库提供各表行数；
 * 纯 JVM 单测可注入 fake 便于验证 [V2ParityChecker] 的比对逻辑。
 */
interface V1RowCountProvider {
    /** 返回指定 Room 表名（V1 表名）的行数；表不存在或读失败返回 null。 */
    fun rowCount(table: String): Long?
}
