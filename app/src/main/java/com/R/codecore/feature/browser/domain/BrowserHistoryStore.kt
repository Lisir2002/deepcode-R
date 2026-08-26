package com.R.codecore.feature.browser.domain

import android.content.Context
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器访问历史持久化存储（R1.1 历史记录）。
 *
 * 用 SharedPreferences + JSON 按时间倒序保存最近访问记录（title/url/timestamp），
 * 供浏览器页「历史记录」入口列表展示与回跳。无痕模式下不写入。
 */
@Singleton
class BrowserHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "BrowserHistoryStore"
        const val PREFS = "browser_history"
        const val KEY_ENTRIES = "entries"
        /** 最多保留条数（防无限膨胀）。 */
        const val MAX_ENTRIES = 200
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 全部历史（按时间倒序）。 */
    fun entries(): List<BrowserHistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<BrowserHistoryEntry>>(raw)
        } catch (e: Exception) {
            FileLogger.e(TAG, "解析历史失败", e)
            emptyList()
        }
    }

    /** 追加一条访问记录；同 URL 会去重上移，超上限裁剪最旧条目。 */
    fun record(title: String, url: String) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        val list = entries()
            .filterNot { it.url == url } // 同 URL 去重（相同页面再次访问只保留最新时间）
            .toMutableList()
        list.add(0, BrowserHistoryEntry(id = now.toString(), title = title, url = url, timestamp = now))
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(list)).apply()
    }

    /** 清空历史。 */
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }
}
