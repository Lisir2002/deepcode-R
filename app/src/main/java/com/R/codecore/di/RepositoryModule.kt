package com.R.codecore.di

import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import com.R.codecore.feature.settings.data.repository.AIProviderRepositoryImpl
import com.R.codecore.feature.credentials.domain.repository.CredentialRepository
import com.R.codecore.feature.credentials.data.repository.CredentialRepositoryImpl
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
