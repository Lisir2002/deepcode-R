package com.R.codecore.di

import com.R.codecore.feature.credentials.data.repository.CredentialRepositoryV2Impl
import com.R.codecore.feature.credentials.domain.repository.CredentialRepository
import com.R.codecore.feature.settings.data.repository.AIProviderRepositoryV2Impl
import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import com.R.codecore.feature.t2i.data.repository.T2IRepositoryV2Impl
import com.R.codecore.feature.t2i.domain.repository.T2IRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 业务 Repository DI（v2-full-takeover P2 批 1，去双路径后）。
 *
 * 数据层已完全由 V2 SQLDelight 接管，直接注入 V2 实现。
 * 旧 Room 实现类在 P3 剔除。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAIProviderRepository(v2Impl: AIProviderRepositoryV2Impl): AIProviderRepository = v2Impl

    @Provides
    @Singleton
    fun provideCredentialRepository(v2Impl: CredentialRepositoryV2Impl): CredentialRepository = v2Impl

    @Provides
    @Singleton
    fun provideT2IRepository(v2Impl: T2IRepositoryV2Impl): T2IRepository = v2Impl
}
