package com.core.deepcode.datalayer.engine

import app.cash.sqldelight.db.SqlDriver

/**
 * 驱动工厂统一接口（设计 §12.2）。
 * 业务/迁移/备份只依赖此接口，不感知底层是明文还是加密。
 * 未来开 SQLCipher 只需在 DI 把绑定从 [PlainDriverFactory] 换成 [CipherDriverFactory]。
 */
interface DatabaseDriverFactory {
    fun create(lib: LibName): SqlDriver
}
