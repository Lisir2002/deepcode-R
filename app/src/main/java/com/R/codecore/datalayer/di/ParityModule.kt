package com.R.codecore.datalayer.di

import com.R.codecore.datalayer.parity.AndroidV1RowCountProvider
import com.R.codecore.datalayer.parity.V1RowCountProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层 V2 全面接管：V1（Room 域库）行数读取抽象的 Hilt 绑定。
 *
 * [V2ParityChecker] 依赖抽象 [V1RowCountProvider]，Android 实现为 [AndroidV1RowCountProvider]；
 * 该绑定在把 [com.R.codecore.datalayer.migration.V2TakeoverGate] 接入启动注入链后变得必需
 * （此前 V2ParityChecker 未被任何组件引用，绑定缺失未被 Dagger 暴露）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ParityModule {

    @Binds
    @Singleton
    abstract fun bindV1RowCountProvider(impl: AndroidV1RowCountProvider): V1RowCountProvider
}