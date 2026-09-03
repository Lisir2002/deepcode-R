package com.R.codecore.datalayer.engine

import app.cash.sqldelight.db.SqlDriver

/**
 * 连接池（设计 §12.1）。
 *
 * 当前为「单连接 holder」：每库一个 SqlDriver（WAL + synchronous=FULL，读写同连接），
 * 通过此接口暴露，守护单写者、零并发写风险。抽象保留以便未来升级为真正的多连接池而不破 API。
 *
 * v2-full-takeover P3-紧急加固：在首次创建 driver 后、返回给任何调用者之前，触发 [onOpened] 回调。
 * 这确保 MigrationEngine.ensureSchema + SchemaSelfHealer 自愈**总是在任何业务查询之前完成**——
 * 哪怕 DataRegistryModule.provideDataProviders 先于 provideAgentDb 调用 [driver]，拿到的也已经是
 * 表结构完整（含 id 列）的库，不会再出现 no such column: agent_message.id 的启动即崩。
 *
 * [onOpened] 在 ConnectionPool 构造时由 DataLayerModule 注入；单进程内、单库只触发一次（幂等）。
 */
class ConnectionPool(
    private val factory: DatabaseDriverFactory,
    private val onOpened: ((LibName, SqlDriver) -> Unit)? = null,
) {

    private val drivers = mutableMapOf<LibName, SqlDriver>()

    @Synchronized
    fun driver(lib: LibName): SqlDriver {
        val existing = drivers[lib]
        if (existing != null) return existing
        val created = factory.create(lib)
        drivers[lib] = created
        // 立即对齐 schema + 自愈，再让任何业务代码访问。
        onOpened?.invoke(lib, created)
        return created
    }

    @Synchronized
    fun closeAll() {
        drivers.values.forEach { runCatching { it.close() } }
        drivers.clear()
    }
}
