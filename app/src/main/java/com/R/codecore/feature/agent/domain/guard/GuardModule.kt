package com.R.codecore.feature.agent.domain.guard

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * 工具护栏 multibinding（D1-3）：把每个 [ToolGuard] 实现汇集为 Set，
 * 供 workflow（guard 段）构造注入遍历执行。
 *
 * 新增护栏时在此追加一行 `@Binds @IntoSet` 绑定即可（模式对齐 HookModule / SlashCommandModule）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GuardModule {

    @Binds
    @IntoSet
    abstract fun bindFileObservationGuard(guard: FileObservationGuard): ToolGuard
}
