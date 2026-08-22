package com.R.codecore.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.skill.RuntimeProbe
import com.R.codecore.feature.agent.domain.skill.RuntimeProbeExpr
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillProbeExprParser
import com.R.codecore.feature.agent.domain.skill.SkillScope
import com.R.codecore.feature.agent.domain.skill.SkillSourceType
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import com.R.codecore.feature.agent.domain.skill.SkillType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * 用户技能编辑器 ViewModel（skill_edit 路由，仅 LOCAL 技能）。
 *
 * 结构化编辑：frontmatter 表单 + SKILL.md 正文 + 脚本/文件内容；保存时重建 frontmatter 写回磁盘，
 * 预校验（frontmatter 可解析、SCRIPT 的 entry 指向存在文件）。支持新增/删除文件与另存为新技能。
 */
@HiltViewModel
class SkillEditViewModel @Inject constructor(
    private val skillStateRepository: SkillStateRepository
) : ViewModel() {

    private companion object {
        const val TAG = "SkillEditViewModel"
        const val SKILL_MD = "SKILL.md"
        const val CLAUDE_MD = "CLAUDE.md"
        const val BUILTIN_MARKER = ".builtin"
    }

    // ── 只读元信息 ──
    private val _id = MutableStateFlow<String?>(null)
    val id: StateFlow<String?> = _id.asStateFlow()
    private val _source = MutableStateFlow<SkillSourceType?>(null)
    val source: StateFlow<SkillSourceType?> = _source.asStateFlow()
    private val _dir = MutableStateFlow<File?>(null)
    val dir: StateFlow<File?> = _dir.asStateFlow()

    // ── 可编辑字段 ──
    val name = MutableStateFlow("")
    val description = MutableStateFlow("")
    val version = MutableStateFlow("0.0.0")
    val author = MutableStateFlow("")
    val tags = MutableStateFlow("")
    val type = MutableStateFlow<SkillType>(SkillType.PROMPT)
    val entry = MutableStateFlow("")
    val autoTrigger = MutableStateFlow(false)
    val triggerConditions = MutableStateFlow("")
    val triggerKeywords = MutableStateFlow("")
    val scope = MutableStateFlow<SkillScope>(SkillScope.GLOBAL)
    val agentType = MutableStateFlow("")
    val mcpTool = MutableStateFlow("")
    val requiresRuntime = MutableStateFlow("")
    val body = MutableStateFlow("")

    // ── 文件列表（相对路径）与当前编辑文件 ──
    private val _files = MutableStateFlow<List<String>>(emptyList())
    val files: StateFlow<List<String>> = _files.asStateFlow()
    val currentFile = MutableStateFlow(SKILL_MD)
    val currentFileContent = MutableStateFlow("")
    val isBuiltin = MutableStateFlow(false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private var loadedSkill: Skill? = null

    /** 加载技能并初始化表单（幂等）。 */
    fun load(skillId: String) {
        if (_id.value == skillId) return
        _id.value = skillId
        viewModelScope.launch {
            val skills = runCatching { skillStateRepository.listSkills() }.getOrDefault(emptyList())
            val skill = skills.firstOrNull { it.id == skillId }
                ?: run { _message.value = "技能不存在"; return@launch }
            loadedSkill = skill
            _source.value = skill.source
            _dir.value = skill.dir
            isBuiltin.value = skill.source == SkillSourceType.BUILTIN
            name.value = skill.name
            description.value = skill.description
            version.value = skill.version.ifBlank { "0.0.0" }
            author.value = skill.author ?: ""
            tags.value = skill.tags.joinToString(", ")
            type.value = skill.type
            entry.value = skill.entry ?: ""
            autoTrigger.value = skill.autoTrigger
            triggerConditions.value = skill.triggerConditions ?: ""
            triggerKeywords.value = skill.triggerKeywords.joinToString(", ")
            scope.value = skill.scope
            agentType.value = skill.agentType ?: ""
            mcpTool.value = skill.mcpTool ?: ""
            requiresRuntime.value = skill.requiresRuntime?.toDslString() ?: ""
            body.value = skill.instructions
            refreshFileList()
            switchFile(SKILL_MD)
        }
    }

    /** 刷新技能目录文件列表（相对路径）。 */
    private fun refreshFileList() {
        val dir = _dir.value ?: return
        val list = (dir.walkTopDown()
            .filter { it.isFile && it.name != BUILTIN_MARKER }
            .map { it.relativeTo(dir).path }
            .toList()).sorted()
        _files.value = list
    }

    /** 切换到某文件并加载内容。 */
    fun switchFile(path: String) {
        val dir = _dir.value ?: return
        val file = File(dir, path)
        val content = runCatching { file.readText() }.getOrElse {
            FileLogger.w(TAG, "读取编辑文件失败: $path", it)
            ""
        }
        currentFile.value = path
        currentFileContent.value = content
    }

    /** 运行时求值树 → DSL 表达式字符串（表单展示用），往返可被 [SkillProbeExprParser.parse] 还原。 */
    private fun RuntimeProbeExpr.toDslString(): String = when (this) {
        is RuntimeProbeExpr.Leaf -> probe.toAtomString()
        is RuntimeProbeExpr.And -> children.joinToString(" && ") { it.grouped() }
        is RuntimeProbeExpr.Or -> children.joinToString(" || ") { it.grouped() }
        is RuntimeProbeExpr.Not -> "!${child.grouped()}"
    }

    /** 子表达式序列化；复合子项一律加括号，保证与解析优先级（! > && > ||）往返一致。 */
    private fun RuntimeProbeExpr.grouped(): String = when (this) {
        is RuntimeProbeExpr.Leaf, is RuntimeProbeExpr.Not -> toDslString()
        else -> "(${toDslString()})"
    }

    /** 探针 → atom 段：cmd 无前缀，其余带类型前缀，版本段 `>=` 下界 + `<=` 上界。 */
    private fun RuntimeProbe.toAtomString(): String {
        val base = if (check == RuntimeProbe.CHECK_CMD) name else "$check:$name"
        return buildString {
            append(base)
            minVersion?.let { append(">=$it") }
            maxVersion?.let { append("<=$it") }
        }
    }

    /** 新增文件：path 为相对路径（须非空、不越界、不覆盖已有文件）。返回是否成功。 */
    fun addFile(path: String): Boolean {
        val dir = _dir.value ?: return false
        if (path.isBlank() || path.contains("..") || path.startsWith("/")) return false
        val target = File(dir, path)
        if (target.exists()) {
            _message.value = "文件已存在: $path"
            return false
        }
        return try {
            target.parentFile?.mkdirs()
            target.writeText("")
            refreshFileList()
            switchFile(path)
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "新增文件失败: $path", e)
            _message.value = "新增文件失败: ${e.message}"
            false
        }
    }

    /** 删除文件（SKILL.md/CLAUDE.md 与内置技能文件不可删）。 */
    fun deleteFile(path: String): Boolean {
        if (path == SKILL_MD || path == CLAUDE_MD || isBuiltin.value) return false
        val dir = _dir.value ?: return false
        val target = File(dir, path)
        return try {
            if (!target.isFile) return false
            target.delete()
            refreshFileList()
            switchFile(SKILL_MD)
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "删除文件失败: $path", e)
            _message.value = "删除文件失败: ${e.message}"
            false
        }
    }

    /** 保存：先写当前编辑文件，再重建 SKILL.md（frontmatter + 正文）。 */
    fun save() {
        val skillId = _id.value ?: return
        val dir = _dir.value ?: return
        // 运行时依赖 DSL 语法校验：表达式非空但解析失败 → 中止保存并提示
        val rtExpr = requiresRuntime.value.trim().takeIf { it.isNotBlank() }
        if (rtExpr != null && SkillProbeExprParser.parse(rtExpr) == null) {
            _message.value = "运行时依赖表达式语法错误，请参考下方格式说明"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // 1. 写回当前编辑文件（若在编辑非 SKILL.md 文件）
                    if (currentFile.value != SKILL_MD && currentFile.value != CLAUDE_MD) {
                        File(dir, currentFile.value).writeText(currentFileContent.value)
                    }
                    // 2. 重建 SKILL.md
                    val skillMd = File(dir, SKILL_MD)
                    val (oldFrontmatter, _) = splitFrontmatter(skillMd.readText())
                    val merged = LinkedHashMap(oldFrontmatter).apply {
                        put("name", name.value.trim())
                        put("description", description.value.trim())
                        put("version", version.value.trim().ifBlank { "0.0.0" })
                        author.value.trim().takeIf { it.isNotBlank() }?.let { put("author", it) } ?: remove("author")
                        put("tags", tags.value.split(',').map { it.trim() }.filter { it.isNotBlank() })
                        put("type", type.value.name)
                        if (type.value == SkillType.SCRIPT) {
                            entry.value.trim().takeIf { it.isNotBlank() }?.let { put("entry", it) } ?: remove("entry")
                        } else {
                            remove("entry")
                        }
                        put("auto_trigger", autoTrigger.value)
                        triggerConditions.value.trim().takeIf { it.isNotBlank() }?.let { put("trigger_conditions", it) } ?: remove("trigger_conditions")
                        triggerKeywords.value.split(',').map { it.trim() }.filter { it.isNotBlank() }.let {
                            if (it.isNotEmpty()) put("trigger_keywords", it) else remove("trigger_keywords")
                        }
                        put("scope", scope.value.name)
                        if (scope.value == SkillScope.AGENT) {
                            agentType.value.trim().takeIf { it.isNotBlank() }?.let { put("agent_type", it) } ?: remove("agent_type")
                        } else {
                            remove("agent_type")
                        }
                        mcpTool.value.trim().takeIf { it.isNotBlank() }?.let { put("mcp_tool", it) } ?: remove("mcp_tool")
                        requiresRuntime.value.trim().takeIf { it.isNotBlank() }
                            ?.let { put("requires_runtime", it) } ?: remove("requires_runtime")
                    }
                    val yaml = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK })
                    val frontmatterStr = yaml.dump(merged).trimEnd()
                    val newText = buildString {
                        append("---\n")
                        append(frontmatterStr)
                        append("\n---\n\n")
                        append(body.value.trim())
                    }
                    skillMd.writeText(newText)
                    true
                } catch (e: Exception) {
                    FileLogger.e(TAG, "保存技能失败: $skillId", e)
                    _message.value = "保存失败: ${e.message}"
                    false
                }
            }
            if (ok) {
                _saved.value = true
                _message.value = "已保存"
            }
        }
    }

    /** 另存为新技能：复制目录到新 id（原名-copy），返回新技能 id（null=失败）。 */
    fun duplicate(): String? {
        val src = _dir.value ?: return null
        val root = skillStateRepository.skillsRoot()
        var newId = "${_id.value ?: "skill"}-copy"
        var counter = 2
        while (File(root, newId).exists()) {
            newId = "${_id.value ?: "skill"}-copy$counter"
            counter++
        }
        val target = File(root, newId)
        return try {
            src.copyRecursively(target, overwrite = true)
            File(target, BUILTIN_MARKER).delete() // 副本不继承内置标记
            // 副本指向新目录并初始化字段
            _id.value = newId
            _dir.value = target
            _source.value = SkillSourceType.LOCAL
            isBuiltin.value = false
            name.value = loadedSkill?.name ?: ""
            refreshFileList()
            switchFile(SKILL_MD)
            newId
        } catch (e: Exception) {
            FileLogger.e(TAG, "复制技能失败", e)
            runCatching { target.deleteRecursively() }
            _message.value = "复制失败: ${e.message}"
            null
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 从 SKILL.md 文本拆分 frontmatter map 与正文。 */
    private fun splitFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized
        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")
        val map = runCatching {
            val loaded = Yaml().load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        }.getOrDefault(emptyMap())
        return map to rest
    }
}
