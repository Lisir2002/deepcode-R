package com.core.deepcode.feature.agent.domain.hook

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Hook multibinding：把每个 [HookHandler] 实现汇集为 Set，供 [HookDispatcher] 构造注入。
 *
 * - 代码级 hook：`@Binds @IntoSet` 绑定一行即可（模式对齐 SlashCommandModule）；
 * - 声明式 hooks.json：经 [HookConfigLoader] 读取内置 + 用户覆盖配置并转换为 handler 列表，
 *   与代码级 handler 合并进 [HookDispatcher]（同名 id 声明式覆盖代码级）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HookModule {

    @Binds
    @IntoSet
    abstract fun bindCommitDisciplineHook(handler: CommitDisciplineHook): HookHandler

    companion object {
        /** 声明式 hooks.json 产出的 handler（每次解析现读，用户改动即时生效）。 */
        @Provides
        @Singleton
        fun provideDeclarativeHooks(loader: HookConfigLoader): List<@JvmSuppressWildcards HookHandler> =
            loader.load()
    }
}
