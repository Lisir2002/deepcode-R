package com.R.codecore.feature.browser.domain

import android.content.Context
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览器收藏夹持久化存储（R1.1 收藏夹）。
 *
 * 用 SharedPreferences + JSON 保存用户手动收藏的书签（title/url），
 * 供浏览器页「收藏夹」入口列表展示、新标签页快捷入口与增删管理。
 */
@Singleton
class BrowserBookmarkStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "BrowserBookmarkStore"
        const val PREFS = "browser_bookmarks"
        const val KEY_BOOKMARKS = "bookmarks"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 全部书签（按收藏时间倒序）。 */
    fun bookmarks(): List<BrowserBookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<BrowserBookmark>>(raw)
        } catch (e: Exception) {
            FileLogger.e(TAG, "解析书签失败", e)
            emptyList()
        }
    }

    /** 是否已收藏该 URL。 */
    fun contains(url: String): Boolean = bookmarks().any { it.url == url }

    /** 新增书签；已收藏同 URL 则忽略。 */
    fun add(title: String, url: String): Boolean {
        if (url.isBlank()) return false
        val list = bookmarks().toMutableList()
        if (list.any { it.url == url }) return false
        list.add(0, BrowserBookmark(id = UUID.randomUUID().toString(), title = title, url = url, createdAt = System.currentTimeMillis()))
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(list)).apply()
        return true
    }

    /** 删除指定 URL 的书签。 */
    fun remove(url: String) {
        val list = bookmarks().filterNot { it.url == url }
        prefs.edit().putString(KEY_BOOKMARKS, json.encodeToString(list)).apply()
    }
}
