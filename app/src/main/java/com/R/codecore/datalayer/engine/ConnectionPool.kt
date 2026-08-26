package com.R.codecore.datalayer.engine

import app.cash.sqldelight.db.SqlDriver

/**
 * 连接池（设计 §12.1）。
 *
 * 当前为「单连接 holder」：每库一个 SqlDriver（WAL + synchronous=FULL，读写同连接），
 * 通过此接口暴露，守护单写者、零并发写风险。抽象保留以便未来升级为真正的多连接池而不破 API。
 */
class ConnectionPool(private val factory: DatabaseDriverFactory) {

    private val drivers = mutableMapOf<LibName, SqlDriver>()

    @Synchronized
    fun driver(lib: LibName): SqlDriver = drivers.getOrPut(lib) { factory.create(lib) }

    @Synchronized
    fun closeAll() {
        drivers.values.forEach { runCatching { it.close() } }
        drivers.clear()
    }
}
