package com.mini.me_core.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mini.me_core.R
import com.mini.me_core.core.network.HttpWarningBridge

/**
 * 全局明文 HTTP 警告弹窗宿主：订阅 [HttpWarningBridge.warning]，非 null 即渲染警告弹窗。
 *
 * 挂在应用根 Composable（覆盖所有页面，对齐 GlobalCredentialDialogHost 模式）：当模型/应用向
 * 非回环的 `http://` 地址发起明文请求时，提示用户数据未加密、有被窃听/篡改风险，引导改用 HTTPS。
 * 仅提示不阻断（本地明文端点属预期行为）。
 */
@Composable
fun GlobalHttpWarningDialogHost(bridge: HttpWarningBridge) {
    val warning by bridge.warning.collectAsStateWithLifecycle()
    warning?.let { w ->
        AlertDialog(
            onDismissRequest = { bridge.dismiss() },
            title = { Text(stringResource(R.string.security_http_warning_title)) },
            text = { Text(stringResource(R.string.security_http_warning_desc, w.host)) },
            confirmButton = {
                TextButton(onClick = { bridge.dismiss() }) {
                    Text(stringResource(R.string.common_got_it))
                }
            }
        )
    }
}
