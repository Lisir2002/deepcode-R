package com.R.codecore.feature.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillSourceType
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import com.R.codecore.feature.settings.domain.SkillExporter
import com.R.codecore.feature.settings.domain.SkillImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 技能中心 ViewModel（RC74 新增；本期扩展：导入/导出/作用域覆盖）。
 *
 * 承载技能列表、启用/卸载、统一导入管线状态机（prepare → confirm）、导出与分享。
 */
@HiltViewModel
class SkillsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillStateRepository: SkillStateRepository,
    private val skillImporter: SkillImporter,
    private val skillExporter: SkillExporter
) : ViewModel() {

    private companion object {
        const val TAG = "SkillsViewModel"
    }

    /** 技能列表（含启用状态，响应式）。 */
    val skills: StateFlow<List<Skill>> = skillStateRepository.skillsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ───────────── 导入状态机（prepare → confirm） ─────────────

    sealed interface ImportUiState {
        data object Idle : ImportUiState
        data object Preparing : ImportUiState
        data class Ready(val preview: SkillImporter.SkillImportPreview, val stageDir: File) : ImportUiState
        data class Illegal(val errors: List<String>) : ImportUiState
        data class Conflict(val existingSource: SkillSourceType, val stageDir: File) : ImportUiState
        data class Done(val message: String) : ImportUiState
    }

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** 导入源选择弹窗开关（UI 侧 remember，不落 ViewModel）。 */

    /** 启用/禁用技能（即时生效）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { skillStateRepository.setEnabled(id, enabled) }
                .onFailure { e ->
                    FileLogger.e(TAG, "切换技能启用状态失败: $id", e)
                    _message.value = "切换启用状态失败: ${e.message}"
                }
        }
    }

    /** 卸载技能。 */
    fun uninstall(skill: Skill) {
        viewModelScope.launch {
            _loading.value = true
            runCatching { skillStateRepository.uninstall(skill.id) }
                .onSuccess { ok ->
                    _message.value = if (ok) "已卸载技能「${skill.name}」" else "卸载技能「${skill.name}」失败"
                }
                .onFailure { e ->
                    FileLogger.e(TAG, "卸载技能失败: ${skill.id}", e)
                    _message.value = "卸载技能失败: ${e.message}"
                }
            _loading.value = false
        }
    }

    /** 设置作用域用户覆盖（NULL=清除覆盖，跟随 frontmatter 声明）。仅 LOCAL 技能可改。 */
    fun setScopeOverride(id: String, scope: SkillScope?, agentType: String? = null) {
        viewModelScope.launch {
            runCatching { skillStateRepository.setScopeOverride(id, scope, agentType) }
                .onFailure { e ->
                    FileLogger.e(TAG, "设置技能作用域覆盖失败: $id", e)
                    _message.value = "设置作用域失败: ${e.message}"
                }
        }
    }

    // ───────────── 导入管线 ─────────────

    /** 选择 ZIP 文件后准备（解析 + 预校验）。 */
    fun prepareZip(uri: Uri) {
        viewModelScope.launch {
            _importState.value = ImportUiState.Preparing
            val result = runCatching<SkillImporter.SkillPrepareResult> { skillImporter.prepareFromZip(uri) }
                .getOrElse {
                    FileLogger.e(TAG, "ZIP 准备失败", it)
                    _importState.value = ImportUiState.Illegal(listOf("读取失败: ${it.message ?: "未知错误"}"))
                    return@launch
                }
            _importState.value = mapPrepareResult(result)
        }
    }

    /** 单 MD（选文件）：读取 Uri 文本内容后走 [prepareMarkdown]。fileName 用作 id 与回退名。 */
    fun prepareMarkdownFromUri(uri: Uri, fileName: String) {
        viewModelScope.launch {
            _importState.value = ImportUiState.Preparing
            val content = runCatching {
                @Suppress("DEPRECATION")
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (content == null) {
                _importState.value = ImportUiState.Illegal(listOf("无法读取所选文件"))
                return@launch
            }
            val result = runCatching<SkillImporter.SkillPrepareResult> { skillImporter.prepareFromMarkdown(fileName, content) }
                .getOrElse {
                    FileLogger.e(TAG, "MD 准备失败", it)
                    _importState.value = ImportUiState.Illegal(listOf("读取失败: ${it.message ?: "未知错误"}"))
                    return@launch
                }
            _importState.value = mapPrepareResult(result)
        }
    }

    /** 单 MD（选文件 / 粘贴文本）准备。fileName 用作 id 与回退名。 */
    fun prepareMarkdown(fileName: String, content: String) {
        viewModelScope.launch {
            _importState.value = ImportUiState.Preparing
            val result = runCatching<SkillImporter.SkillPrepareResult> { skillImporter.prepareFromMarkdown(fileName, content) }
                .getOrElse {
                    FileLogger.e(TAG, "MD 准备失败", it)
                    _importState.value = ImportUiState.Illegal(listOf("读取失败: ${it.message ?: "未知错误"}"))
                    return@launch
                }
            _importState.value = mapPrepareResult(result)
        }
    }

    /** URL 下载准备。 */
    fun prepareUrl(url: String) {
        viewModelScope.launch {
            _importState.value = ImportUiState.Preparing
            val result = runCatching<SkillImporter.SkillPrepareResult> { skillImporter.prepareFromUrl(url) }
                .getOrElse {
                    FileLogger.e(TAG, "URL 准备失败", it)
                    _importState.value = ImportUiState.Illegal(listOf("下载失败: ${it.message ?: "未知错误"}"))
                    return@launch
                }
            _importState.value = mapPrepareResult(result)
        }
    }

    private fun mapPrepareResult(result: SkillImporter.SkillPrepareResult): ImportUiState = when (result) {
        is SkillImporter.SkillPrepareResult.Ok -> ImportUiState.Ready(result.preview, result.stageDir)
        is SkillImporter.SkillPrepareResult.Illegal -> ImportUiState.Illegal(result.errors)
    }

    /** 用户确认导入（可携带 overwrite 决策处理冲突）。 */
    fun confirmImport(overwrite: Boolean) {
        val current = _importState.value
        val stageDir = when (current) {
            is ImportUiState.Ready -> current.stageDir
            is ImportUiState.Conflict -> current.stageDir
            else -> return
        }
        viewModelScope.launch {
            val result = runCatching { skillImporter.commit(stageDir, overwrite) }.getOrElse {
                FileLogger.e(TAG, "提交导入失败", it)
                _importState.value = ImportUiState.Illegal(listOf("导入失败: ${it.message ?: "未知错误"}"))
                return@launch
            }
            when (result) {
                is SkillImporter.SkillCommitResult.Installed -> {
                    val suffix = if (result.updated) "已覆盖更新" else "已安装"
                    _importState.value = ImportUiState.Done("技能「${result.skill.name}」$suffix")
                }
                is SkillImporter.SkillCommitResult.Conflict -> {
                    _importState.value = ImportUiState.Conflict(result.existingSource, stageDir)
                }
                is SkillImporter.SkillCommitResult.Failed -> {
                    _importState.value = ImportUiState.Illegal(listOf(result.message))
                }
            }
        }
    }

    /** 取消当前导入（清理临时目录）。 */
    fun cancelImport() {
        val current = _importState.value
        (current as? ImportUiState.Ready)?.stageDir?.let { runCatching { it.deleteRecursively() } }
        (current as? ImportUiState.Conflict)?.stageDir?.let { runCatching { it.deleteRecursively() } }
        _importState.value = ImportUiState.Idle
    }

    /** 关闭结果/非法提示，回到空闲。 */
    fun dismissImport() {
        _importState.value = ImportUiState.Idle
    }

    /** 消费一次性完成提示（同时复位状态机，Toast 后进入 Idle）。 */
    fun consumeImportDone() {
        _importState.value = ImportUiState.Idle
    }

    // ───────────── 导出 / 分享 ─────────────

    /** 打包 LOCAL 技能为 zip，返回文件（null=失败）。 */
    fun exportZip(skill: Skill): File? = skillExporter.exportToZip(skill)

    /** 系统分享技能 zip。 */
    fun shareZip(skill: Skill): Boolean {
        val ok = skillExporter.shareZip(skill)
        if (!ok) _message.value = "导出/分享技能失败"
        return ok
    }

    fun consumeMessage() {
        _message.value = null
    }
}
