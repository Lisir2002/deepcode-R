package com.R.codecore.feature.browser.presentation

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.R.codecore.R
import com.R.codecore.core.theme.AppTopAppBar
import com.R.codecore.core.theme.LocalAppDarkMode
import com.R.codecore.core.theme.Radius
import com.R.codecore.core.theme.Spacing
import com.R.codecore.feature.browser.domain.BrowserController
import com.R.codecore.feature.browser.domain.BrowserLoginPromptManager
import com.R.codecore.feature.browser.domain.BrowserTabInfo
import com.R.codecore.feature.browser.domain.BrowserTakeoverManager
import com.R.codecore.feature.browser.domain.LoginPromptAnswer
import com.R.codecore.feature.browser.domain.TakeoverAnswer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import kotlinx.coroutines.launch

/**
 * 内置服务浏览器页。
 *
 * 用户侧：地址栏 + 前进/后退/刷新 + WebView 容器；模型侧：[BrowserController.agentStatus]
 * 实时展示模型正在进行的操作。用户与模型共享同一个 WebView 会话（同一份 Cookie/登录态）。
 *
 * 同时承载两类异步交互弹窗：
 *  - 页面 alert/confirm（[BrowserController.pendingDialog]）——模型或页面发起，用户确认；
 *  - 登录凭据输入（[BrowserLoginPromptManager.pendingPrompt]）——模型登录时请求用户提供账号密码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceBrowserScreen(
    browserController: BrowserController,
    loginPromptManager: BrowserLoginPromptManager,
    takeoverManager: BrowserTakeoverManager,
    initialUrl: String? = null,
    onNavigateBack: () -> Unit
) {
    val uiState by browserController.uiState.collectAsStateWithLifecycle()
    val tabs by browserController.tabsState.collectAsStateWithLifecycle()
    val agentStatus by browserController.agentStatus.collectAsStateWithLifecycle()
    val pendingDialog by browserController.pendingDialog.collectAsStateWithLifecycle()
    val pendingLoginPrompt by loginPromptManager.pendingPrompt.collectAsStateWithLifecycle()
    val pendingTakeover by takeoverManager.pending.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var addressText by remember { mutableStateOf(uiState.currentUrl) }

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
                // 地址栏 + 导航按钮
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
