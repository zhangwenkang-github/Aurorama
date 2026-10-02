package com.zhangwenkang.cinefin.presentation.setup.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.network.formatFingerprint
import com.zhangwenkang.cinefin.setup.R as SetupR
import com.zhangwenkang.cinefin.setup.presentation.certificate.CertificateTrustPrompt

/**
 * 自签证书指纹确认框（TOFU）：
 *
 * 连接被证书校验拦下时展示 host:port 与完整 SHA-256 指纹——只有用户点「信任并继续」才写入信任记录； 若此前信任过另一张证书（指纹变化），额外给出警告，需要用户再次确认。
 */
@Composable
fun CertificateTrustDialog(
    prompt: CertificateTrustPrompt,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    val previousFingerprint = prompt.previousFingerprint
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(SetupR.string.certificate_trust_title)) },
        text = {
            Column {
                Text(
                    text =
                        stringResource(
                            SetupR.string.certificate_trust_message,
                            "${prompt.host}:${prompt.port}",
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(SetupR.string.certificate_trust_fingerprint_title),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = formatFingerprint(prompt.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (previousFingerprint != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text =
                            stringResource(
                                SetupR.string.certificate_trust_changed,
                                formatFingerprint(previousFingerprint),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(text = stringResource(SetupR.string.certificate_trust_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(SetupR.string.cancel)) }
        },
    )
}
