package com.R.codecore.feature.agent.presentation.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.R.codecore.R
import com.R.codecore.feature.agent.domain.model.AgentImage
import com.R.codecore.feature.agent.presentation.AgentAttachment
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class UploadedWorkspaceFile(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null
)

internal data class PendingUploadAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val image: AgentImage? = null,
)

internal val PendingUploadAttachment.isImage: Boolean
    get() = image != null

private fun List<PendingUploadAttachment>.toAttachmentText(context: Context): String {
    if (isEmpty()) return ""
    return buildString {
        append(context.getString(R.string.chat_attachment_prefix))
        this@toAttachmentText.forEach { attachment ->
            append('\n')
            append("- ")
            append(attachment.fileName)
            append("：")
            append(attachment.containerPath)
        }
    }
}

internal fun appendAttachmentsToRequest(
    context: Context,
    request: String,
    attachments: List<PendingUploadAttachment>
): String {
    val attachmentText = attachments.toAttachmentText(context)
    if (attachmentText.isBlank()) return request
    if (request.isBlank()) return attachmentText
    return request.trimEnd() + "\n\n" + attachmentText
}

internal fun UploadedWorkspaceFile.toPendingAttachment(): PendingUploadAttachment =
    PendingUploadAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        image = image
    )

private fun PendingUploadAttachment.toAgentAttachment(): AgentAttachment =
    AgentAttachment(
        fileName = fileName,
        containerPath = containerPath,
        localPath = localPath,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        isImage = isImage
    )

internal fun List<PendingUploadAttachment>.toAgentAttachments(): List<AgentAttachment> =
    map { it.toAgentAttachment() }

private fun PendingUploadAttachment.toAgentImage(): AgentImage? = image

internal fun List<PendingUploadAttachment>.toAgentImages(): List<AgentImage> =
    mapNotNull { it.toAgentImage() }

internal fun maxAttachmentMessage(context: Context, max: Int): String =
    context.getString(R.string.chat_attachment_max, max)

internal fun uploadSuccessMessage(context: Context, count: Int): String =
    context.getString(R.string.chat_attachment_uploaded, count)

internal fun partialUploadMessage(context: Context, count: Int): String =
    context.getString(R.string.chat_attachment_uploaded_partial, count)

internal fun selectedAttachments(
    uris: List<Uri>,
    currentCount: Int
): List<Uri> = uris.take((MAX_PENDING_ATTACHMENTS - currentCount).coerceAtLeast(0))

internal fun hasAttachmentSlots(currentCount: Int): Boolean =
    currentCount < MAX_PENDING_ATTACHMENTS

internal fun remainingAttachmentSlots(currentCount: Int): Int =
    (MAX_PENDING_ATTACHMENTS - currentCount).coerceAtLeast(0)

private fun imageLimitError(context: Context): String =
    context.getString(R.string.chat_image_too_large, formatBytes(MAX_IMAGE_UPLOAD_BYTES))

private fun attachmentsRoot(workspace: File): File =
    File(File(workspace, ".rcodecore"), "attachments").apply { mkdirs() }

private fun workspaceContainerPath(relativePath: String): String =
    "~/workspace/$relativePath"

private fun attachmentRelativePath(workspace: File, target: File): String =
    target.relativeTo(workspace).invariantSeparatorsPath

private fun pickedFileToastPath(context: Context, path: String): String =
    context.getString(R.string.chat_uploaded_to, path)

internal fun emptyWorkspaceMessage(context: Context): String =
    context.getString(R.string.chat_select_workspace_first)

internal fun unreadableFileMessage(context: Context): String =
    context.getString(R.string.chat_read_file_failed)

private fun unavailableWorkspaceMessage(context: Context): String =
    context.getString(R.string.chat_workspace_unavailable)

internal fun uploadFallbackError(context: Context): String =
    context.getString(R.string.chat_upload_failed)

private fun unsupportedImageTypeError(context: Context): String =
    context.getString(R.string.chat_unsupported_image_type)

private fun uploadFileName(context: Context, uri: Uri): String =
    context.displayName(uri).ifBlank { "upload" }

private fun safeUploadFileName(context: Context, uri: Uri): String =
    sanitizeUploadFileName(uploadFileName(context, uri))

private fun imageMimeType(context: Context, uri: Uri, fileName: String): String =
    resolveImageMimeType(context, uri, fileName)

private fun fileMimeType(context: Context, uri: Uri, fileName: String): String =
    context.contentResolver.getType(uri)?.lowercase()
        ?: when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

internal suspend fun copyUriToWorkspace(
    context: Context,
    uri: Uri,
    workspacePath: String,
    includeImageData: Boolean = false
): UploadedWorkspaceFile = withContext(Dispatchers.IO) {
    val workspace = File(workspacePath)
    require(workspace.isDirectory) { unavailableWorkspaceMessage(context) }

    val uploadsDir = attachmentsRoot(workspace)
    val target = uniqueUploadFile(uploadsDir, safeUploadFileName(context, uri))

    val input = context.contentResolver.openInputStream(uri) ?: error(unreadableFileMessage(context))
    input.use { source ->
        target.outputStream().use { output ->
            source.copyTo(output)
        }
    }

    val relativePath = attachmentRelativePath(workspace, target)
    val mimeType = if (includeImageData) {
        runCatching { imageMimeType(context, uri, target.name) }
            .getOrElse { error ->
                runCatching { target.delete() }
                throw error
            }
    } else {
        fileMimeType(context, uri, target.name)
    }
    val image = if (includeImageData) {
        if (target.length() > MAX_IMAGE_UPLOAD_BYTES) {
            runCatching { target.delete() }
            error(imageLimitError(context))
        }
        AgentImage(
            mimeType = mimeType,
            base64Data = Base64.getEncoder().encodeToString(target.readBytes()),
            path = workspaceContainerPath(relativePath)
        )
    } else {
        null
    }
    UploadedWorkspaceFile(
        fileName = target.name,
        containerPath = workspaceContainerPath(relativePath),
        localPath = target.absolutePath,
        mimeType = mimeType,
        sizeBytes = target.length(),
        image = image
    )
}

private fun Context.displayName(uri: Uri): String {
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
    }.orEmpty()
}

private fun sanitizeUploadFileName(name: String): String {
    val cleaned = name
        .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
        .joinToString("")
        .trim()
        .trim('.')
    return cleaned.ifBlank { "upload" }.take(160)
}

private fun uniqueUploadFile(dir: File, fileName: String): File {
    var candidate = File(dir, fileName)
    if (!candidate.exists()) return candidate

    val dotIndex = fileName.lastIndexOf('.')
    val stem = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (candidate.exists()) {
        candidate = File(dir, "$stem-$index$extension")
        index += 1
    }
    return candidate
}

private fun resolveImageMimeType(context: Context, uri: Uri, fileName: String): String {
    val mime = context.contentResolver.getType(uri)?.lowercase()
    if (mime != null && mime in SUPPORTED_IMAGE_MIME_TYPES) return mime

    val byExtension = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
    if (byExtension != null) return byExtension
    error(unsupportedImageTypeError(context))
}

private val SUPPORTED_IMAGE_MIME_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp"
)

private const val MAX_IMAGE_UPLOAD_BYTES = 5L * 1024 * 1024
internal const val MAX_PENDING_ATTACHMENTS = 8

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
