package com.R.codecore.feature.browser.presentation

import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.LocalAppDarkMode
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.browser.domain.BrowserBookmark
import com.R.codecore.feature.browser.domain.BrowserController
import com.R.codecore.feature.browser.domain.BrowserCredentialStore
import com.R.codecore.feature.browser.domain.BrowserDownloadInfo
import com.R.codecore.feature.browser.domain.BrowserHistoryEntry
import com.R.codecore.feature.browser.domain.BrowserLoginPromptManager
import com.R.codecore.feature.browser.domain.BrowserTabInfo
import com.R.codecore.feature.browser.domain.BrowserTakeoverManager
import com.R.codecore.feature.browser.domain.LoginPromptAnswer
import com.R.codecore.feature.browser.domain.TakeoverAnswer
import kotlinx.coroutines.launch
import java.io.File

/**
 * 内置服务浏览器页。
 *
 * 用户侧：地址栏 + 前进/后退/刷新 + 「更多」菜单（历史/收藏/下载/凭据/无痕/桌面版/缩放/
 * 页内查找/分享/复制链接）+ 新标签页主页 + WebView 容器；模型侧：[BrowserController.agentStatus]
 * 实时展示模型正在进行的操作。用户与模型共享同一个 WebView 会话（同一份 Cookie/登录态）。
 *
 * 同时承载三类异步交互弹窗：
 *  - 页面 alert/confirm（[BrowserController.pendingDialog]）——模型或页面发起，用户确认；
 *  - 登录凭据输入（[BrowserLoginPromptManager.pendingPrompt]）——模型登录时请求用户提供账号密码；
 *  - 用户接管提示（[BrowserTakeoverManager.pending]）——模型请求用户亲自完成验证码/支付等。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceBrowserScreen(
    browserController: BrowserController,
    loginPromptManager: BrowserLoginPromptManager,
    takeoverManager: BrowserTakeoverManager,
    credentialStore: BrowserCredentialStore,
    initialUrl: String? = null,
    onNavigateBack: () -> Unit
) {
    val uiState by browserController.uiState.collectAsStateWithLifecycle()
    val tabs by browserController.tabsState.collectAsStateWithLifecycle()
    val agentStatus by browserController.agentStatus.collectAsStateWithLifecycle()
    val pendingDialog by browserController.pendingDialog.collectAsStateWithLifecycle()
    val pendingLoginPrompt by loginPromptManager.pendingPrompt.collectAsStateWithLifecycle()
    val pendingTakeover by takeoverManager.pending.collectAsStateWithLifecycle()
    val downloads by browserController.downloads.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var addressText by remember { mutableStateOf(uiState.currentUrl) }

    // 「更多」菜单与各功能面板开关
    var showMore by remember { mutableStateOf(false) }
    var findVisible by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }

    // 页面 URL 变化时同步地址栏
    LaunchedEffect(uiState.currentUrl) {
        if (addressText != uiState.currentUrl) addressText = uiState.currentUrl
    }

    // 首次进入：预创建首个激活标签（避免组合期间改 activeTabId 引发 AndroidView 重复挂载崩溃），再按需导航
    LaunchedEffect(Unit) {
        browserController.ensureActiveTab()
        if (!initialUrl.isNullOrBlank()) {
            browserController.navigate(initialUrl)
        }
    }

    // 卸载时解除绑定（保留 WebView 与登录态）
    DisposableEffect(Unit) {
        onDispose { browserController.unbind() }
    }

    fun navigate() {
        val url = addressText.trim()
        if (url.isBlank()) return
        scope.launch { browserController.navigate(url) }
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        scope.launch { browserController.navigate(url) }
    }

    fun switchTab(id: String) {
        if (id == uiState.activeTabId) return
        scope.launch { browserController.switchTab(id) }
    }

    fun closeTab(id: String) {
        scope.launch { browserController.closeTab(id) }
    }

    fun newTab() {
        scope.launch { browserController.newTab(null) }
    }

    fun shareCurrent() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, uiState.title)
            putExtra(Intent.EXTRA_TEXT, uiState.currentUrl.ifBlank { uiState.title })
        }
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
    }

    fun copyCurrentLink() {
        val url = uiState.currentUrl
        if (url.isBlank()) return
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(context, context.getString(R.string.browser_link_copied), Toast.LENGTH_SHORT).show()
    }

    fun openDownload(info: BrowserDownloadInfo) {
        val file = browserController.downloadHostFile(info) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, context.getString(R.string.browser_open_failed), Toast.LENGTH_SHORT).show() }
    }

    fun retryDownload(info: BrowserDownloadInfo) {
        scope.launch { browserController.retryDownload(info) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                AppTopAppBar(
                    title = stringResource(R.string.browser_title),
                    onNavigateBack = onNavigateBack,
                    navigationIcon = Icons.AutoMirrored.Rounded.ArrowBack,
                    navigationContentDescription = stringResource(R.string.common_back)
                )
                // 地址栏 + 导航按钮 + 「更多」菜单
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    IconButton(onClick = { browserController.goBack() }, enabled = uiState.canGoBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.browser_back),
                            tint = if (uiState.canGoBack) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    IconButton(onClick = { browserController.goForward() }, enabled = uiState.canGoForward, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.browser_forward),
                            tint = if (uiState.canGoForward) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(onGo = { navigate() }),
                        shape = RoundedCornerShape(Radius.md),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    IconButton(onClick = { browserController.reload() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.browser_refresh),
                            tint = if (LocalAppDarkMode.current) Color(0xFF4ADE80) else Color(0xFF22C55E)
                        )
                    }
                    IconButton(onClick = { navigate() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = stringResource(R.string.browser_go),
                            tint = if (LocalAppDarkMode.current) Color(0xFF7C9FFF) else Color(0xFF4C8DFF)
                        )
                    }
                    // 「更多」菜单入口
                    Box {
                        IconButton(onClick = { showMore = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.browser_more),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BrowserMoreMenu(
                            expanded = showMore,
                            onDismiss = { showMore = false },
                            bookmarked = uiState.currentUrl.isNotBlank() && browserController.isBookmarked(uiState.currentUrl),
                            incognito = uiState.incognito,
                            desktopMode = uiState.desktopMode,
                            onFind = { showMore = false; findVisible = true; findText = "" },
                            onToggleBookmark = {
                                showMore = false
                                if (uiState.currentUrl.isNotBlank()) {
                                    if (browserController.isBookmarked(uiState.currentUrl)) {
                                        browserController.removeBookmark(uiState.currentUrl)
                                    } else {
                                        browserController.addBookmark()
                                    }
                                }
                            },
                            onHistory = { showMore = false; showHistory = true },
                            onBookmarks = { showMore = false; showBookmarks = true },
                            onDownloads = { showMore = false; showDownloads = true },
                            onCredentials = { showMore = false; showCredentials = true },
                            onShare = { showMore = false; shareCurrent() },
                            onCopyLink = { showMore = false; copyCurrentLink() },
                            onIncognito = { browserController.setIncognito(!uiState.incognito) },
                            onDesktopMode = { browserController.toggleDesktopMode() },
                            onZoom = { showMore = false; showZoom = true }
                        )
                    }
                }
                // 页内查找条
                AnimatedVisibility(visible = findVisible, enter = fadeIn(), exit = fadeOut()) {
                    FindOnPageBar(
                        text = findText,
                        onTextChange = { findText = it; browserController.findOnPage(it) },
                        onPrev = { browserController.findNextOnPage(false) },
                        onNext = { browserController.findNextOnPage(true) },
                        onClose = {
                            findVisible = false
                            findText = ""
                            browserController.clearFindOnPage()
                        }
                    )
                }
                // 标签栏：多标签切换 / 新建 / 关闭
                BrowserTabBar(
                    tabs = tabs,
                    activeTabId = uiState.activeTabId,
                    onSelect = { switchTab(it) },
                    onClose = { closeTab(it) },
                    onNewTab = { newTab() }
                )
                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        progress = { (uiState.progress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        bottomBar = {
            Column {
                // 模型操作状态条
                AnimatedVisibility(
                    visible = agentStatus.active,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = agentStatus.text.ifBlank { stringResource(R.string.browser_agent_working) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // WebView 容器：按激活标签 key 切换，每个标签独占一个 WebView 实例。
            // 首个标签尚未创建（activeTabId 为空）时先渲染占位，避免在组合期间创建标签引发 key 跳变导致崩溃。
            if (uiState.activeTabId.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    key(uiState.activeTabId) {
                        val webView = remember { browserController.bind() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White)
                        ) {
                            AndroidView(
                                factory = { webView },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (uiState.isLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                    // 新标签页主页（R1.1）：当前标签尚无 URL 时展示搜索/收藏/最近访问快捷入口
                    if (uiState.currentUrl.isBlank()) {
                        BrowserHomePage(
                            addressText = addressText,
                            onAddressChange = { addressText = it },
                            onNavigate = { navigate() },
                            bookmarks = remember { browserController.bookmarks() },
                            recentVisits = remember { browserController.history().take(6) },
                            onOpen = { openUrl(it) }
                        )
                    }
                }
            }
        }
    }

    // ── 页面 alert/confirm 对话框 ──
    pendingDialog?.let { d ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (d.type == "alert") stringResource(R.string.browser_dialog_alert) else stringResource(R.string.browser_dialog_confirm_title)) },
            text = { Text(d.message) },
            confirmButton = {
                TextButton(onClick = { scope.launch { browserController.handleDialog(true) } }) {
                    Text(stringResource(R.string.workspace_confirm))
                }
            },
            dismissButton = {
                if (d.type != "alert") {
                    TextButton(onClick = { scope.launch { browserController.handleDialog(false) } }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        )
    }

    // ── 登录凭据输入对话框（模型登录时请求用户提供账号密码） ──
    pendingLoginPrompt?.let { p ->
        LoginCredentialDialog(
            host = p.host,
            onConfirm = { username, password ->
                loginPromptManager.resolve(
                    p.requestId,
                    LoginPromptAnswer(username = username, password = password, cancelled = false)
                )
            },
            onCancel = { loginPromptManager.cancel(p.requestId) }
        )
    }

    // ── 用户接管提示对话框（模型请求用户亲自完成验证码/支付/二次认证等） ──
    pendingTakeover?.let { p ->
        AlertDialog(
            onDismissRequest = { takeoverManager.cancel(p.requestId) },
            title = { Text(p.title) },
            text = { Text(p.message) },
            confirmButton = {
                TextButton(onClick = { takeoverManager.resolve(p.requestId, TakeoverAnswer(confirmed = true)) }) {
                    Text(stringResource(R.string.browser_takeover_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { takeoverManager.cancel(p.requestId) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // ── 历史记录面板（R1.1） ──
    if (showHistory) {
        HistoryDialog(
            entries = remember { browserController.history() },
            onOpen = { url -> showHistory = false; openUrl(url) },
            onClear = {
                browserController.clearHistory()
                showHistory = false
            },
            onDismiss = { showHistory = false }
        )
    }

    // ── 收藏夹面板（R1.1） ──
    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = remember { browserController.bookmarks() },
            onOpen = { url -> showBookmarks = false; openUrl(url) },
            onRemove = { browserController.removeBookmark(it) },
            onDismiss = { showBookmarks = false }
        )
    }

    // ── 下载管理面板（R1.2） ──
    if (showDownloads) {
        DownloadsDialog(
            downloads = downloads,
            onOpen = { openDownload(it) },
            onRetry = { retryDownload(it) },
            onClear = { browserController.clearDownloads() },
            onDismiss = { showDownloads = false }
        )
    }

    // ── 凭据管理面板（R1.2） ──
    if (showCredentials) {
        CredentialsDialog(
            credentialStore = credentialStore,
            onDismiss = { showCredentials = false }
        )
    }

    // ── 页面缩放面板（R1.3） ──
    if (showZoom) {
        ZoomDialog(
            percent = uiState.textZoom,
            onLess = { browserController.setTextZoom(uiState.textZoom - 10) },
            onMore = { browserController.setTextZoom(uiState.textZoom + 10) },
            onReset = { browserController.setTextZoom(100) },
            onDismiss = { showZoom = false }
        )
    }
}

/** 地址栏「更多」下拉菜单：用户侧功能统一入口。 */
@Composable
private fun BrowserMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    bookmarked: Boolean,
    incognito: Boolean,
    desktopMode: Boolean,
    onFind: () -> Unit,
    onToggleBookmark: () -> Unit,
    onHistory: () -> Unit,
    onBookmarks: () -> Unit,
    onDownloads: () -> Unit,
    onCredentials: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onIncognito: () -> Unit,
    onDesktopMode: () -> Unit,
    onZoom: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_find)) },
            onClick = onFind,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (bookmarked) stringResource(R.string.browser_remove_bookmark)
                    else stringResource(R.string.browser_add_bookmark)
                )
            },
            onClick = onToggleBookmark,
            leadingIcon = {
                Icon(
                    if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    null
                )
            }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_history)) },
            onClick = onHistory,
            leadingIcon = { Icon(Icons.Rounded.History, null) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_bookmarks)) },
            onClick = onBookmarks,
            leadingIcon = { Icon(Icons.Rounded.Bookmark, null) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_downloads)) },
            onClick = onDownloads,
            leadingIcon = { Icon(Icons.Rounded.Download, null) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_credentials)) },
            onClick = onCredentials,
            leadingIcon = { Icon(Icons.Rounded.Key, null) }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_share)) },
            onClick = onShare,
            leadingIcon = { Icon(Icons.Rounded.Share, null) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_copy_link)) },
            onClick = onCopyLink,
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_incognito)) },
            onClick = onIncognito,
            leadingIcon = { Icon(Icons.Rounded.PrivacyTip, null) },
            trailingIcon = {
                Switch(checked = incognito, onCheckedChange = { onIncognito() }, modifier = Modifier.size(32.dp))
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_desktop_mode)) },
            onClick = onDesktopMode,
            leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) },
            trailingIcon = {
                Switch(checked = desktopMode, onCheckedChange = { onDesktopMode() }, modifier = Modifier.size(32.dp))
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.browser_zoom)) },
            onClick = onZoom,
            leadingIcon = { Icon(Icons.Rounded.ZoomIn, null) }
        )
    }
}

/** 页内查找条（R1.1）：输入框 + 上/下一个 + 关闭。 */
@Composable
private fun FindOnPageBar(
    text: String,
    onTextChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.browser_find_hint)) },
            shape = RoundedCornerShape(Radius.md),
            textStyle = MaterialTheme.typography.bodySmall
        )
        IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                contentDescription = stringResource(R.string.browser_find_prev)
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.browser_find_next)
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.browser_find_close)
            )
        }
    }
}

/** 新标签页主页（R1.1）：搜索框 + 最近访问 + 收藏夹快捷入口。 */
@Composable
private fun BrowserHomePage(
    addressText: String,
    onAddressChange: (String) -> Unit,
    onNavigate: () -> Unit,
    bookmarks: List<BrowserBookmark>,
    recentVisits: List<BrowserHistoryEntry>,
    onOpen: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = addressText,
            onValueChange = onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.browser_home_search_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onNavigate() }),
            shape = RoundedCornerShape(Radius.md),
            leadingIcon = { Icon(Icons.Rounded.Search, null) }
        )
        Spacer(Modifier.height(Spacing.md))
        if (recentVisits.isNotEmpty()) {
            Text(
                text = stringResource(R.string.browser_recent_visits),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(Spacing.xs))
            recentVisits.forEach { entry ->
                HomeLinkRow(
                    title = entry.title.ifBlank { entry.url },
                    subtitle = entry.url,
                    onClick = { onOpen(entry.url) }
                )
            }
            Spacer(Modifier.height(Spacing.md))
        }
        if (bookmarks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.browser_bookmarks),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(Spacing.xs))
            bookmarks.take(8).forEach { bm ->
                HomeLinkRow(
                    title = bm.title.ifBlank { bm.url },
                    subtitle = bm.url,
                    onClick = { onOpen(bm.url) }
                )
            }
        }
    }
}

/** 主页单条快捷入口。 */
@Composable
private fun HomeLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 历史记录面板（R1.1）：列表 + 回跳 + 清空。 */
@Composable
private fun HistoryDialog(
    entries: List<BrowserHistoryEntry>,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.browser_history), modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(stringResource(R.string.browser_clear_history))
                    }
                }
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.browser_history_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry.url) }
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title.ifBlank { entry.url },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.browser_clear_history)) },
            text = { Text(stringResource(R.string.browser_clear_history_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) {
                    Text(stringResource(R.string.workspace_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 收藏夹面板（R1.1）：列表 + 回跳 + 删除。 */
@Composable
private fun BookmarksDialog(
    bookmarks: List<BrowserBookmark>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var removeTarget by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_bookmarks)) },
        text = {
            if (bookmarks.isEmpty()) {
                Text(stringResource(R.string.browser_bookmarks_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(bm.url) }
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bm.title.ifBlank { bm.url },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = bm.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { removeTarget = bm.url }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )

    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.browser_remove_bookmark)) },
            text = { Text(removeTarget!!) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(removeTarget!!)
                    removeTarget = null
                }) {
                    Text(stringResource(R.string.workspace_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 下载管理面板（R1.2）：列表 / 打开 / 重试 / 清除。 */
@Composable
private fun DownloadsDialog(
    downloads: List<BrowserDownloadInfo>,
    onOpen: (BrowserDownloadInfo) -> Unit,
    onRetry: (BrowserDownloadInfo) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.browser_downloads), modifier = Modifier.weight(1f))
                if (downloads.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.browser_downloads_clear))
                    }
                }
            }
        },
        text = {
            if (downloads.isEmpty()) {
                Text(stringResource(R.string.browser_downloads_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(downloads, key = { it.id }) { info ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = info.fileName.ifBlank { info.url },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when (info.status) {
                                        "done" -> info.path
                                        "error" -> info.error.ifBlank { info.url }
                                        else -> info.url
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (info.status) {
                                        "error" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (info.status == "done") {
                                TextButton(onClick = { onOpen(info) }) {
                                    Text(stringResource(R.string.browser_open))
                                }
                            } else if (info.status == "error") {
                                IconButton(onClick = { onRetry(info) }) {
                                    Icon(
                                        Icons.Rounded.Replay,
                                        contentDescription = stringResource(R.string.browser_retry)
                                    )
                                }
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/** 凭据管理面板（R1.2）：已存登录凭据列表（明文查看受保护）+ 删除。 */
@Composable
private fun CredentialsDialog(
    credentialStore: BrowserCredentialStore,
    onDismiss: () -> Unit
) {
    val hosts = remember { credentialStore.hosts() }
    val revealed = remember { mutableStateOf<String?>(null) }
    var deleteHost by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_credentials)) },
        text = {
            if (hosts.isEmpty()) {
                Text(stringResource(R.string.browser_credentials_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(hosts, key = { it }) { host ->
                        val cred = credentialStore.find(host)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = host,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (revealed.value == host && cred != null) {
                                        stringResource(R.string.browser_credential_detail, cred.username, cred.password)
                                    } else {
                                        stringResource(R.string.browser_credential_username, cred?.username.orEmpty())
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { revealed.value = if (revealed.value == host) null else host }) {
                                Icon(
                                    if (revealed.value == host) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deleteHost = host }) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.browser_credentials_delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )

    if (deleteHost != null) {
        AlertDialog(
            onDismissRequest = { deleteHost = null },
            title = { Text(stringResource(R.string.browser_credentials_delete)) },
            text = { Text(stringResource(R.string.browser_credentials_delete_confirm, deleteHost!!)) },
            confirmButton = {
                TextButton(onClick = {
                    credentialStore.delete(deleteHost!!)
                    deleteHost = null
                }) {
                    Text(stringResource(R.string.workspace_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteHost = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 页面缩放面板（R1.3）：textZoom 百分比调整（50–200）。 */
@Composable
private fun ZoomDialog(
    percent: Int,
    onLess: () -> Unit,
    onMore: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_zoom)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    IconButton(onClick = onLess) {
                        Icon(Icons.Rounded.ZoomOut, contentDescription = stringResource(R.string.browser_zoom_less))
                    }
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.browser_zoom_reset))
                    }
                    IconButton(onClick = onMore) {
                        Icon(Icons.Rounded.ZoomIn, contentDescription = stringResource(R.string.browser_zoom_more))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

/** 登录凭据输入对话框。 */
@Composable
private fun LoginCredentialDialog(
    host: String,
    onConfirm: (username: String, password: String) -> Unit,
    onCancel: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.browser_login_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.browser_login_hint, host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.common_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.browser_login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(username.trim(), password) },
                enabled = username.isNotBlank() && password.isNotBlank()
            ) {
                Text(stringResource(R.string.browser_login_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/** 浏览器标签栏：横向滚动，可切换 / 关闭 / 新建标签。 */
@Composable
private fun BrowserTabBar(
    tabs: List<BrowserTabInfo>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs, key = { it.id }) { tab ->
            val active = tab.id == activeTabId
            Surface(
                shape = RoundedCornerShape(Radius.sm),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clickable { onSelect(tab.id) }
            ) {
                Row(
                    modifier = Modifier.padding(start = Spacing.sm, end = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.title.ifBlank { tab.url.ifBlank { stringResource(R.string.browser_tab_empty) } },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp)
                    )
                    IconButton(
                        onClick = { onClose(tab.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.browser_close_tab),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            IconButton(onClick = onNewTab, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.browser_new_tab),
                    tint = if (LocalAppDarkMode.current) Color(0xFF7C9FFF) else Color(0xFF4C8DFF)
                )
            }
        }
    }
}
