package com.R.codecore.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.R.codecore.R
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.skill.Skill
import com.R.codecore.feature.agent.domain.skill.SkillSourceType
import com.R.codecore.feature.agent.domain.skill.SkillStateRepository
import com.R.codecore.feature.settings.domain.SkillExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 技能查看页 ViewModel（skill_detail 路由）。
 *
 * 负责加载指定技能、构建其目录树（相对路径，隐藏 .builtin 标记）、按需读取文件内容，
 * 并提供 LOCAL 技能的 zip 导出/分享。
 */
@HiltViewModel
class SkillDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillStateRepository: SkillStateRepository,
    private val skillExporter: SkillExporter
) : ViewModel() {

    private companion object {
        const val TAG = "SkillDetailViewModel"
        val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp",
            "zip", "gz", "tar", "jar", "apk", "bin", "so", "a", "o"
        )
        val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        val CODE_EXT = setOf(
            "py", "sh", "bash", "js", "ts", "kt", "kts", "java", "go",
            "c", "h", "cpp", "cc", "cxx", "hpp", "cs", "rb", "php",
            "swift", "rs", "dart", "pl", "pm", "sql", "json", "yaml", "yml", "xml", "toml"
        )
    }

    /** 目录树节点：name 显示名，path 相对技能目录路径，isDirectory 是否目录。 */
    data class SkillFileNode(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val children: List<SkillFileNode> = emptyList()
    )

    private val _skill = MutableStateFlow<Skill?>(null)
    val skill: StateFlow<Skill?> = _skill.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _fileTree = MutableStateFlow<List<SkillFileNode>>(emptyList())
    val fileTree: StateFlow<List<SkillFileNode>> = _fileTree.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 加载技能与目录树（每次重新扫描磁盘，保证从编辑器返回后能看到最新内容；
     * 数据量小，不做幂等短路，避免「编辑保存后查看页不刷新」）。
     */
    fun load(skillId: String) {
        viewModelScope.launch {
            _loading.value = true
            _skill.value = null
            _fileTree.value = emptyList()
            val skills = runCatching { skillStateRepository.listSkills() }.getOrDefault(emptyList())
            val skill = skills.firstOrNull { it.id == skillId }
            _skill.value = skill
            _fileTree.value = buildTree(skill?.dir)
            if (skill == null) {
                _message.value = context.getString(R.string.skill_not_found)
            }
            _loading.value = false
        }
    }

    /** 目录树默认展示文件：优先 SKILL.md / CLAUDE.md，否则取第一个文件；无文件返回 null。 */
    fun findDefaultPath(): String? {
        val files = mutableListOf<SkillFileNode>()
        fun collect(nodes: List<SkillFileNode>) {
            nodes.forEach { if (it.isDirectory) collect(it.children) else files.add(it) }
        }
        collect(_fileTree.value)
        return files.firstOrNull { it.name.equals("SKILL.md", true) || it.name.equals("CLAUDE.md", true) }?.path
            ?: files.firstOrNull()?.path
    }

    /** 读取技能目录下某文件的文本内容（IO 线程）；二进制/超大/不存在返回 null。 */
    suspend fun readFile(relPath: String): String? = withContext(Dispatchers.IO) {
        val dir = _skill.value?.dir ?: return@withContext null
        val file = File(dir, relPath)
        if (!file.isFile) return@withContext null
        try {
            val bytes = file.readBytes()
            if (bytes.size > 1_000_000) return@withContext null // 超大文件不预览
            if (bytes.any { it.toInt() == 0 }) return@withContext null // 含 NUL → 二进制
            bytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取技能文件失败: $relPath", e)
            null
        }
    }

    /** LOCAL 技能打包分享 zip（IO 线程异步，结果经 message 提示）。 */
    fun export() {
        val skill = _skill.value ?: return
        if (skill.source == SkillSourceType.BUILTIN) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { skillExporter.shareZip(skill) }.getOrDefault(false)
            }
            _message.value = context.getString(
                if (ok) R.string.skill_export_success else R.string.skill_export_failed
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun buildTree(dir: File?): List<SkillFileNode> {
        if (dir == null || !dir.isDirectory) return emptyList()
        fun walk(f: File, rel: String): SkillFileNode? {
            if (f.name == ".builtin") return null
            val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            if (f.isDirectory) {
                val children = (f.listFiles() ?: emptyArray()).mapNotNull { walk(it, childRel) }
                return SkillFileNode(f.name, childRel, isDirectory = true, children = children)
            }
            return SkillFileNode(f.name, childRel, isDirectory = false)
        }
        return (dir.listFiles() ?: emptyArray())
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .mapNotNull { walk(it, "") }
    }

    /** 判断相对路径对应的文件展示类别。 */
    fun classifyFile(path: String): FileKind {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            path.endsWith(".md", true) || path.endsWith(".markdown", true) -> FileKind.MARKDOWN
            ext in IMAGE_EXT -> FileKind.IMAGE
            ext in CODE_EXT -> FileKind.CODE
            ext in BINARY_EXT -> FileKind.BINARY
            else -> FileKind.TEXT
        }
    }

    enum class FileKind { MARKDOWN, CODE, IMAGE, BINARY, TEXT }
}
