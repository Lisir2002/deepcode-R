package com.core.deepcode.feature.settings.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行模式的同步缓存：在 App 启动时从 [ExecutionModeRepository] 读首帧模式缓存到内存，
 * 供 DI `@Provides` 方法同步读取当前模式来决定注入哪个实现。
 *
 * DataStore 的 flow 是异步的，而 Hilt `@Provides` 是同步的——故需此中间层。
 * 模式切换后（设置页）调 [setMode] 更新缓存，但已注入的 Singleton 不会自动切换——
 * 需重启 App 或后续改为 Provider 工厂模式。首版接受这一点：切换模式后提示用户重启。
 */
@Singleton
class ExecutionModeHolder @Inject constructor() {
    private val _mode = MutableStateFlow(ExecutionMode.LOCAL_PROOT)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    fun setMode(mode: ExecutionMode) {
        _mode.value = mode
    }

    fun currentMode(): ExecutionMode = _mode.value
}
