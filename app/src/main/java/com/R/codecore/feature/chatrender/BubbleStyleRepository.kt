package com.R.codecore.feature.chatrender

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.bubbleStyleDataStore by preferencesDataStore(name = "bubble_style_prefs")

/**
 * 持久化聊天回复样式。默认 [BubbleStyle.DEFAULT]（终端日志）。
 * 样式切换只影响渲染层，不触碰任何对话数据。
 */
@Singleton
class BubbleStyleRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val BUBBLE_STYLE_KEY = stringPreferencesKey("bubble_style")
    }

    val bubbleStyleFlow: Flow<BubbleStyle> = context.bubbleStyleDataStore.data.map { prefs ->
        BubbleStyle.fromPersisted(prefs[BUBBLE_STYLE_KEY]) ?: BubbleStyle.DEFAULT
    }

    suspend fun setBubbleStyle(style: BubbleStyle) {
        context.bubbleStyleDataStore.edit { it[BUBBLE_STYLE_KEY] = style.name }
    }

    /** 备份快照：返回当前持久化的样式名（未设置时返回默认名，导入时回退默认）。 */
    suspend fun snapshot(): String? = bubbleStyleFlow.first().name

    /** 从备份还原样式；null 时清除键回退默认。 */
    suspend fun restore(value: String?) {
        context.bubbleStyleDataStore.edit {
            if (value == null) it.remove(BUBBLE_STYLE_KEY) else it[BUBBLE_STYLE_KEY] = value
        }
    }
}
