package com.deep.rcode.di

import com.deep.rcode.feature.settings.domain.repository.AIProviderRepository
import com.deep.rcode.feature.settings.data.repository.AIProviderRepositoryImpl
import com.deep.rcode.feature.credentials.domain.repository.CredentialRepository
import com.deep.rcode.feature.credentials.data.repository.CredentialRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAIProviderRepository(
        aiProviderRepositoryImpl: AIProviderRepositoryImpl
    ): AIProviderRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        credentialRepositoryImpl: CredentialRepositoryImpl
    ): CredentialRepository
}
