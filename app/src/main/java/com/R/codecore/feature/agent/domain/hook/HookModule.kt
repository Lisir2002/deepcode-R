package com.R.codecore.feature.agent.domain.hook

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hook multibinding：把每个 [HookHandler] 实现汇集为 Set，供 [HookDispatcher] 构造注入。
 *
 * 新增 hook 时在此追加一行 `@Binds @IntoSet` 绑定即可（模式对齐 SlashCommandModule）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HookModule {

    @Binds
    @IntoSet
    abstract fun bindCommitDisciplineHook(handler: CommitDisciplineHook): HookHandler
}
