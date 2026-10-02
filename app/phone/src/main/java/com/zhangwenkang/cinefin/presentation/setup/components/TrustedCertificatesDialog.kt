package com.zhangwenkang.cinefin.presentation.setup.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.network.TrustedCertificate
import com.zhangwenkang.cinefin.network.formatFingerprint
import com.zhangwenkang.cinefin.setup.R as SetupR

/** 已信任证书管理：列出每个「地址 → 指纹」，可随时清除。 清除后对应地址立即回到默认证书校验（再次连接需要重新确认指纹）。 */
@Composable
fun TrustedCertificatesDialog(
    certificates: List<TrustedCertificate>,
    onClear: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(SetupR.string.certificate_trusted_title)) },
        text = {
            if (certificates.isEmpty()) {
                Text(
                    text = stringResource(SetupR.string.certificate_trusted_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(certificates, key = { it.trustKey }) { certificate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = certificate.trustKey,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = formatFingerprint(certificate.fingerprint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onClear(certificate.trustKey) }) {
                                Text(text = stringResource(SetupR.string.certificate_trust_clear))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(SetupR.string.confirm)) }
        },
    )
}
