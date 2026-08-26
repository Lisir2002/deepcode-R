package com.R.codecore.datalayer.engine

import android.content.Context
import app.cash.sqldelight.db.SqlDriver

/**
 * SQLCipher 驱动工厂（设计 §8 / §12.2）—— 当前为「启用接缝」。
 *
 * 设计目标：自测期明文（[PlainDriverFactory] 生效）；未来一键开 SQLCipher 时，
 * 仅需在 DI 把绑定从 PlainDriverFactory 换成本类，并完成下方 create() 内的 SQLCipher 接线：
 *   1. 引入依赖 net.zetetic:android-database-sqlcipher:4.x；
 *   2. 用其 SupportSQLiteOpenHelper.Factory（基于 net.sqlcipher.database.SQLiteOpenHelper）
 *      构造 AndroidSqliteDriver(schema, context, name, cipherFactory)；
 *   3. 密钥经 Android Keystore 提供（与现有备份/订阅敏感字段加密一致）。
 * 切换只改 DI 绑定，业务/迁移/备份代码无感。
 */
class CipherDriverFactory(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val pathProvider: DatabasePathProvider,
) : DatabaseDriverFactory {

    override fun create(lib: LibName): SqlDriver {
        error(
            "CipherDriverFactory 尚未启用：请先引入 net.zetetic:android-database-sqlcipher " +
                "并按 DataLayerModule 注释完成 SQLCipher SupportSQLiteOpenHelper.Factory 接线，再切换 DI 绑定。",
        )
    }
}
