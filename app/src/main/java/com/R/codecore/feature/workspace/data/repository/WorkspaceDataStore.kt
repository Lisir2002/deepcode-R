package com.R.codecore.feature.workspace.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * workspace 模块统一 DataStore（数据层重构 T2：「旧版写法」workspace 2 个碎片 →
 * 「新写法」每模块一个）。
 *
 * 旧 `ftp_server_prefs`（FtpServerManager 专用）收敛进本文件，与 WorkspaceRepository 共用
 * 同一个 delegate 实例（避免同文件多 delegate 并发写风险）。旧文件值由
 * [com.R.codecore.feature.workspace.data.repository.WorkspaceDataStoreMigrator] 一次性搬迁后删除。
 *
 * 各 repository 的 PreferencesKey 名盘点唯一（current_workspace_name / port / username /
 * password / anonymous / auto_start），合并后语义不变。
 */
val Context.workspaceDataStore by preferencesDataStore(name = "workspace_prefs")
