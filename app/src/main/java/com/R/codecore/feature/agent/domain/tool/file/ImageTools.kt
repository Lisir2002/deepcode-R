package com.R.codecore.feature.agent.domain.tool.file

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolResult
import com.R.codecore.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import javax.inject.Inject

class ViewImageTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "viewImage"
    override val description = "查看本地图片文件。读取图片尺寸并把图片作为下一轮视觉输入提供给模型，适合检查截图、设计稿、图标和生成图。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "图片路径：~/workspace/... 为项目文件；其它绝对路径为容器系统文件；相对路径基于 ~/workspace。",
            required = true
        ),
        "detail" to ToolParameter(
            name = "detail",
            type = ParameterType.STRING,
            description = "图片细节级别。low 会缩小到较小预览；high 适合一般视觉检查；original 尽量传原图，过大时自动降级为 high。",
            required = false,
            enum = listOf("low", "high", "original")
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "可选的提问或说明（如「提取报错信息」「分析 UI 布局」）。非多模态模型使用识图服务时，识图模型会优先围绕该问题或说明进行针对性描述。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, kotlinx.serialization.json.JsonElement>): ToolResult {
        // V-4：整条解码/缩放/编码链路（含文件拷贝）放 IO 线程，避免大图 CPU 密集操作阻塞收集线程（主线程）导致卡顿/ANR
        return withContext(Dispatchers.IO) {
            try {
                val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (path.isBlank()) {
                    return@withContext ToolResult.Error("路径参数缺失", "MISSING_PATH")
                }

                val detail = args["detail"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it in SUPPORTED_DETAILS }
                    ?: "high"

                val file = fileAccess.copyToLocal(path)
                FileLogger.d(TAG, "viewImage path=$path -> ${file.absolutePath}, detail=$detail")

                if (!fileAccess.exists(path)) return@withContext ToolResult.Error("文件不存在: $path", "FILE_NOT_FOUND")
                if (!fileAccess.isFile(path)) return@withContext ToolResult.Error("路径不是文件: $path", "NOT_A_FILE")
                val fileSize = fileAccess.fileSize(path)
                if (fileSize <= 0L) return@withContext ToolResult.Error("图片文件为空: $path", "EMPTY_FILE")

                val bounds = decodeBounds(file)
                    ?: return@withContext ToolResult.Error("无法识别图片格式: $path", "UNSUPPORTED_IMAGE")
                // V-2：读取 EXIF 朝向；90/270 旋转后真实宽高互换，仅用于展示
                val exifOrientation = readOrientation(file)
                val displayBounds = if (isRotated90Or270(exifOrientation)) {
                    ImageBounds(bounds.height, bounds.width)
                } else {
                    bounds
                }
                val sourceMime = guessMimeType(file)
                if (!sourceMime.startsWith("image/")) {
                    return@withContext ToolResult.Error("不是支持的图片文件: $path", "UNSUPPORTED_IMAGE")
                }

                val encoded = if (detail == "original" && fileSize <= MAX_ORIGINAL_BYTES && sourceMime in ORIGINAL_MIME_TYPES) {
                    EncodedImage(
                        mimeType = sourceMime,
                        base64Data = Base64.encodeToString(fileAccess.readBytes(path), Base64.NO_WRAP),
                        width = bounds.width,
                        height = bounds.height,
                        detail = "original",
                        encodedBytes = fileSize
                    )
                } else {
                    encodePreview(file, bounds, detail)
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "content" to JsonPrimitive(
                                "已加载图片 ${fileAccess.toDisplayPath(path)} " +
                                    "(${displayBounds.width}x${displayBounds.height}, ${sourceMime}, ${fileSize} bytes)，" +
                                    "并作为视觉输入附加到下一轮模型上下文。"
                            ),
                            "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                            "mime_type" to JsonPrimitive(sourceMime),
                            "width" to JsonPrimitive(displayBounds.width),
                            "height" to JsonPrimitive(displayBounds.height),
                            "byte_size" to JsonPrimitive(fileSize),
                            "detail" to JsonPrimitive(encoded.detail),
                            "encoded_mime_type" to JsonPrimitive(encoded.mimeType),
                            "encoded_width" to JsonPrimitive(encoded.width),
                            "encoded_height" to JsonPrimitive(encoded.height),
                            "encoded_byte_size" to JsonPrimitive(encoded.encodedBytes),
                            "image" to JsonObject(
                                mapOf(
                                    "mime_type" to JsonPrimitive(encoded.mimeType),
                                    "base64_data" to JsonPrimitive(encoded.base64Data),
                                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path))
                                )
                            )
                        )
                    )
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "viewImage 异常", e)
                ToolResult.Error(e.message ?: "读取图片失败", "READ_IMAGE_ERROR")
            }
        }
    }

    private fun decodeBounds(file: File): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) ImageBounds(width, height) else null
    }

    /**
     * V-2：读取图片 EXIF ORIENTATION。读取失败（如非 JPEG/损坏）返回 NORMAL。
     */
    private fun readOrientation(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * V-2：按 EXIF ORIENTATION 旋转/翻转 Bitmap。
     * NORMAL / UNDEFINED 原样返回（引用相等）；旋转/翻转会生成新 Bitmap（由调用方负责回收原图）。
     */
    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(90f)
                matrix.postScale(1f, -1f)
            }
            else -> return bitmap // ORIENTATION_NORMAL / ORIENTATION_UNDEFINED
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** V-2：判断 EXIF 方向是否会使宽高互换（旋转 90/270 及两个对角线翻转方向）。 */
    private fun isRotated90Or270(orientation: Int): Boolean = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE -> true
        else -> false
    }

    private fun encodePreview(file: File, bounds: ImageBounds, detail: String): EncodedImage {
        val maxEdge = if (detail == "low") LOW_MAX_EDGE else HIGH_MAX_EDGE
        val targetBytes = if (detail == "low") LOW_TARGET_BYTES else HIGH_TARGET_BYTES
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.width, bounds.height, maxEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("无法解码图片: ${file.name}")

        // V-2：EXIF 方向修正——在缩放/编码前按 ORIENTATION 旋转 Bitmap，避免手机竖拍图被横置。
        // low/high 缩略图与预览图在同一路径统一处理，方向保持一致。
        var current = decoded
        try {
            val orientation = readOrientation(file)
            if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                val rotated = rotateBitmap(decoded, orientation)
                if (rotated !== decoded) {
                    // 旋转产生新 Bitmap：回收旧解码图，外层 finally 只回收 current，避免 double-free
                    decoded.recycle()
                    current = rotated
                }
            }

            val scaled = scaleToMaxEdge(current, maxEdge)
            try {
                val encoded = compressJpeg(scaled, targetBytes)
                return EncodedImage(
                    mimeType = "image/jpeg",
                    base64Data = Base64.encodeToString(encoded, Base64.NO_WRAP),
                    width = scaled.width,
                    height = scaled.height,
                    detail = detail,
                    encodedBytes = encoded.size.toLong()
                )
            } finally {
                if (scaled !== current) scaled.recycle()
            }
        } finally {
            current.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxEdge && halfHeight / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap, targetBytes: Int): ByteArray {
        var best = ByteArray(0)
        for (quality in JPEG_QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            best = bytes
            if (bytes.size <= targetBytes) break
        }
        return best
    }

    private fun guessMimeType(file: File): String {
        val extMime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }

    private data class ImageBounds(val width: Int, val height: Int)

    private data class EncodedImage(
        val mimeType: String,
        val base64Data: String,
        val width: Int,
        val height: Int,
        val detail: String,
        val encodedBytes: Long
    )

    private companion object {
        const val TAG = "ImageTools"
        const val LOW_MAX_EDGE = 512
        const val HIGH_MAX_EDGE = 1024
        const val LOW_TARGET_BYTES = 96 * 1024
        const val HIGH_TARGET_BYTES = 512 * 1024
        const val MAX_ORIGINAL_BYTES = 4 * 1024 * 1024
        val JPEG_QUALITIES = listOf(86, 78, 70, 62, 54)
        val SUPPORTED_DETAILS = setOf("low", "high", "original")
        val ORIGINAL_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
