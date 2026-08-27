package com.R.codecore.di

import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.feature.credentials.data.repository.CredentialRepositoryImpl
import com.R.codecore.feature.credentials.data.repository.CredentialRepositoryV2Impl
import com.R.codecore.feature.credentials.domain.repository.CredentialRepository
import com.R.codecore.feature.settings.data.repository.AIProviderRepositoryImpl
import com.R.codecore.feature.settings.data.repository.AIProviderRepositoryV2Impl
import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import com.R.codecore.feature.t2i.data.repository.T2IRepositoryRoomImpl
import com.R.codecore.feature.t2i.data.repository.T2IRepositoryV2Impl
import com.R.codecore.feature.t2i.domain.repository.T2IRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 业务 Repository DI（v2-full-takeover P2 批 1）。
 *
 * 读源开关 [DataReadModeHolder] 决定注入 Room 旧实现还是 V2 新实现：
 *  - ROOM：旧实现（升级前默认，行为与现状完全一致，回退路径）；
 *  - V2：新 SQLDelight 实现（逐批切换时由 [com.R.codecore.datalayer.migration.V2TakeoverGate] 置位）。
 * P3 剔除旧层后，本模块内 Room 实现绑定整体删除（DoD 判据 3：无旧代码残留）。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAIProviderRepository(
        readMode: DataReadModeHolder,
        roomImpl: AIProviderRepositoryImpl,
        v2Impl: AIProviderRepositoryV2Impl,
    ): AIProviderRepository = when (readMode.currentModeSync()) {
        DataReadMode.V2 -> v2Impl
        DataReadMode.ROOM -> roomImpl
    }

    @Provides
    @Singleton
    fun provideCredentialRepository(
        readMode: DataReadModeHolder,
        roomImpl: CredentialRepositoryImpl,
        v2Impl: CredentialRepositoryV2Impl,
    ): CredentialRepository = when (readMode.currentModeSync()) {
        DataReadMode.V2 -> v2Impl
        DataReadMode.ROOM -> roomImpl
    }

    @Provides
    @Singleton
    fun provideT2IRepository(
        readMode: DataReadModeHolder,
        roomImpl: T2IRepositoryRoomImpl,
        v2Impl: T2IRepositoryV2Impl,
    ): T2IRepository = when (readMode.currentModeSync()) {
        DataReadMode.V2 -> v2Impl
        DataReadMode.ROOM -> roomImpl
    }
}
