package com.core.deepcode.feature.agent.domain.tool.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject

/**
 * 设备存储护栏工具：在「设备外存储目录」内做结构化的 list / read / write / delete。
 *
 * 背景：开启「共享设备存储」后容器可经 shell 访问整张外存，而 Bash 的路径白名单无法可靠解析
 * 任意 shell 语义（管道/重定向）。本工具提供一条独立于 shell、可控的读写通道：
 *  - [ToolPermissionPolicy.ASK]：每次调用都弹确认卡，敏感操作需用户显式放行；
 *  - 路径归一化后强制仍在设备外存储根之内，拒绝 `..` 越界；
 *  - 主要面向文本/小文件，供模型读写本机文件，不替代 Bash 的重活。
 *
 * 权限说明：targetSdk=28 走 legacy storage，`WRITE_EXTERNAL_STORAGE` 授予后即可读写，
 * 无需 MANAGE_EXTERNAL_STORAGE（见设计评审）。运行时未授权时返回可读错误。
 */
class StorageTool @Inject constructor(
    @ApplicationContext private val context: Context
) : AgentTool() {

    private companion object {
        const val TAG = "StorageTool"
        const val MAX_TEXT = 200 * 1024 // 读取文本上限 200KB
        const val MAX_ENTRIES = 1000
    }

    private val externalRoot: File get() = Environment.getExternalStorageDirectory()

    override val name = "device_storage"
    override val description = "访问设备外存储（/storage/emulated/0 及共享目录）的结构化工具。action ∈ {list, read, write, delete}；path 必须是设备外存储内绝对路径。示例：{\"action\":\"read\",\"path\":\"/storage/emulated/0/Download/a.txt\"}。所有操作都会请求用户确认。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.ACCESS_DEVICE_STORAGE)

    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "要执行的操作：list / read / write / delete", enum = listOf("list", "read", "write", "delete")),
        "path" to ToolParameter("path", ParameterType.STRING, "设备外存储内的绝对路径，例如 /storage/emulated/0/Download/a.txt"),
        "content" to ToolParameter("content", ParameterType.STRING, "write 时的文本内容", required = false),
        "recursive" to ToolParameter("recursive", ParameterType.BOOLEAN, "delete 时是否递归删除目录（默认 false）", required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少 action", "MISSING_ACTION")
        val path = args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少 path", "MISSING_PATH")
        val file = resolveSafe(path)
            ?: return ToolResult.Error("路径越界或不可访问（必须位于 $externalRoot 内）：$path", "PATH_OUT_OF_BOUNDS")
        if (!hasStoragePermission()) {
            return ToolResult.Error(
                "未获得外部存储权限。请在系统设置中授予「存储」权限后重试（targetSdk=28 走 legacy storage，WRITE_EXTERNAL_STORAGE 即可）。",
                "STORAGE_PERMISSION_DENIED"
            )
        }
        return try {
            when (action) {
                "list" -> doList(file)
                "read" -> doRead(file)
                "write" -> {
                    val content = args["content"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.Error("write 需要 content", "MISSING_CONTENT")
                    doWrite(file, content)
                }
                "delete" -> {
                    val recursive = args["recursive"]?.jsonPrimitive?.content?.toBoolean() ?: false
                    doDelete(file, recursive)
                }
                else -> ToolResult.Error("未知 action：$action", "UNSUPPORTED_ACTION")
            }
        } catch (e: SecurityException) {
            FileLogger.w(TAG, "存储访问被拒: ${e.message}")
            ToolResult.Error("外部存储访问被拒（可能未授权）：${e.message}", "STORAGE_PERMISSION_DENIED")
        } catch (e: Exception) {
            FileLogger.w(TAG, "device_storage 失败: ${e.message}")
            ToolResult.Error("操作失败：${e.message}", "STORAGE_FAILED")
        }
    }

    /** 把 path 归一化并校验仍在设备外存储根内；越界返回 null。 */
    private fun resolveSafe(path: String): File? {
        val base = externalRoot.absoluteFile
        val target = File(path).absoluteFile
        val basePath = base.canonicalPath.trimEnd(File.separatorChar)
        val targetPath = target.canonicalPath
        if (targetPath == basePath) return base
        if (!targetPath.startsWith("$basePath${File.separatorChar}")) return null
        return target
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun doList(dir: File): ToolResult {
        if (!dir.exists()) return ToolResult.Error("路径不存在：${dir.absolutePath}", "FILE_NOT_FOUND")
        if (!dir.isDirectory) return ToolResult.Error("不是目录：${dir.absolutePath}", "NOT_A_DIR")
        val children = dir.listFiles()?.sortedWith(compareBy({ it.isDirectory.not() }, { it.name }))
            ?: emptyList()
        val entries = children.take(MAX_ENTRIES).map { f ->
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(f.name),
                    "type" to JsonPrimitive(if (f.isDirectory) "dir" else "file"),
                    "size" to JsonPrimitive(if (f.isDirectory) -1L else f.length()),
                )
            )
        }
        val truncated = children.size > entries.size
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "path" to JsonPrimitive(dir.absolutePath),
                    "count" to JsonPrimitive(entries.size),
                    "truncated" to JsonPrimitive(truncated),
                    "entries" to JsonArray(entries)
                )
            )
        )
    }

    private fun doRead(file: File): ToolResult {
        if (!file.exists()) return ToolResult.Error("文件不存在：${file.absolutePath}", "FILE_NOT_FOUND")
        if (file.isDirectory) return ToolResult.Error("是目录（用 list 查看）：${file.absolutePath}", "NOT_A_FILE")
        if (file.length() > MAX_TEXT) {
            return ToolResult.Error(
                "文件过大（${file.length()} B），超出 $MAX_TEXT 字节读取上限，请用 Bash + sed/head 处理",
                "FILE_TOO_LARGE"
            )
        }
        val text = file.readText().let { t -> if (t.length > MAX_TEXT) t.take(MAX_TEXT) else t }
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "path" to JsonPrimitive(file.absolutePath),
                    "size" to JsonPrimitive(file.length()),
                    "truncated" to JsonPrimitive(text.length < file.length()),
                    "content" to JsonPrimitive(text)
                )
            )
        )
    }

    private fun doWrite(file: File, content: String): ToolResult {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "written" to JsonPrimitive(file.length()),
                    "path" to JsonPrimitive(file.absolutePath)
                )
            )
        )
    }

    private fun doDelete(file: File, recursive: Boolean): ToolResult {
        if (!file.exists()) return ToolResult.Error("路径不存在：${file.absolutePath}", "FILE_NOT_FOUND")
        val deleted = if (file.isDirectory) {
            if (!recursive) return ToolResult.Error("目录删除需 recursive=true：${file.absolutePath}", "NEED_RECURSIVE")
            file.deleteRecursively()
        } else {
            file.delete()
        }
        if (!deleted) return ToolResult.Error("删除失败：${file.absolutePath}", "DELETE_FAILED")
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "deleted" to JsonPrimitive(file.absolutePath),
                    "recursive" to JsonPrimitive(recursive)
                )
            )
        )
    }
}