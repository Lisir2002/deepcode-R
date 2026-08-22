package com.R.codecore.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.feature.backup.data.AutoBackupManager
import com.R.codecore.feature.backup.data.guard.DataSentinel
import com.R.codecore.feature.backup.data.guard.SentinelVerdict
import com.R.codecore.feature.backup.domain.BackupDecryptionException
import com.R.codecore.feature.backup.domain.BackupManager
import com.R.codecore.feature.backup.domain.BackupOptions
import com.R.codecore.feature.backup.domain.RestoreStats
import dagger.hilt.android.lifecycle.HiltViewModel
import com.R.codecore.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.OutputStream
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    data class Error(val message: String) : BackupState()
}

/** 数据保全（哨兵 + 本机自动备份）的 UI 状态。 */
data class DataSafetyUiState(
    /** 最近一次哨兵判定结果；null 表示尚未检查。 */
    val verdict: SentinelVerdict? = null,
    /** 最近一次自动备份时间（epoch ms）；null 表示从未自动备份。 */
    val lastBackupTime: Long? = null,
    /** 本机自动备份份数。 */
    val backupCount: Int = 0,
    /** 正在执行备份/恢复。 */
    val working: Boolean = false,
    /** 最近一次「立即备份」是否成功。 */
    val justBackedUp: Boolean = false,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val dataSentinel: DataSentinel,
    private val autoBackupManager: AutoBackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _dataSafety = MutableStateFlow(DataSafetyUiState())
    val dataSafety: StateFlow<DataSafetyUiState> = _dataSafety.asStateFlow()

    /** 流式导出到 [output]（调用方打开，本方法负责关闭）。 */
    fun export(password: String, options: BackupOptions, output: OutputStream) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                backupManager.export(pw, options, output)
                _state.value = BackupState.ExportDone
            } catch (e: Exception) {
                _state.value = BackupState.Error(e.message ?: context.getString(R.string.backup_export_failed))
            } finally {
                runCatching { output.close() }
            }
        }
    }

    /** 从 SAF Uri 流式导入并还原。 */
    fun import(uri: Uri, password: String) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                val input = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                }
                input.use { backupManager.import(it, pw) }
                    .onSuccess { _state.value = BackupState.ImportSuccess(it) }
                    .onFailure { _state.value = BackupState.Error(describeImportError(it)) }
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeImportError(e))
            }
        }
    }

    private fun describeImportError(e: Throwable): String = when (e) {
        is BackupDecryptionException -> e.message ?: context.getString(R.string.backup_wrong_password)
        else -> e.message ?: context.getString(R.string.backup_import_failed)
    }

    fun reset() {
        _state.value = BackupState.Idle
    }

    // ── 数据保全：哨兵 + 本机自动备份 ──────────────────────────────

    /** 刷新数据安全状态：跑一次哨兵 + 读取本机自动备份信息。 */
    fun refreshDataSafety() {
        viewModelScope.launch {
            val verdict = dataSentinel.check()
            _dataSafety.value = DataSafetyUiState(
                verdict = verdict,
                lastBackupTime = autoBackupManager.lastBackupTime(),
                backupCount = autoBackupManager.backups().size,
            )
        }
    }

    /** 立即全量备份到本机私有目录。 */
    fun backupNow() {
        _dataSafety.update { it.copy(working = true) }
        viewModelScope.launch {
            val ok = autoBackupManager.backupNow()
            _dataSafety.value = DataSafetyUiState(
                verdict = _dataSafety.value.verdict,
                lastBackupTime = autoBackupManager.lastBackupTime(),
                backupCount = autoBackupManager.backups().size,
                justBackedUp = ok,
            )
            if (!ok) {
                _state.value = BackupState.Error(context.getString(R.string.backup_auto_failed))
            }
        }
    }

    /** 从最近的自动备份恢复（自动备份为无口令明文，导入时 password=null）。 */
    fun restoreFromLatest() {
        val file = autoBackupManager.latestBackup()
        if (file == null) {
            _state.value = BackupState.Error(context.getString(R.string.backup_auto_none))
            return
        }
        _dataSafety.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileInputStream(file).use { backupManager.import(it, null) }
            }
            result.onSuccess { stats ->
                _dataSafety.update { it.copy(working = false) }
                _state.value = BackupState.ImportSuccess(stats)
            }.onFailure { e ->
                _dataSafety.update { it.copy(working = false) }
                _state.value = BackupState.Error(
                    e.message ?: context.getString(R.string.backup_auto_restore_failed)
                )
            }
        }
    }
}
