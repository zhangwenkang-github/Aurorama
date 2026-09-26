package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.util.fastFilterNotNull
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference as PreferenceBackend
import com.zhangwenkang.cinefin.settings.presentation.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceLongInput

@Composable
fun SettingsIntInputCard(
    preference: PreferenceIntInput,
    onUpdate: (value: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    val prefix = preference.prefixRes?.let { stringResource(it) }

    val suffix = preference.suffixRes?.let { stringResource(it) }

    SettingsNumberInputCard(
        preference = preference,
        text = listOf(prefix, preference.value, suffix).fastFilterNotNull().joinToString(" "),
        onClick = { showDialog = true },
        modifier = modifier,
    )

    if (showDialog) {
        SettingsIntInputDialog(
            preference = preference,
            onUpdate = { value ->
                showDialog = false
                onUpdate(value)
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Composable
fun SettingsLongInputCard(
    preference: PreferenceLongInput,
    onUpdate: (value: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    val prefix = preference.prefixRes?.let { stringResource(it) }

    val suffix = preference.suffixRes?.let { stringResource(it) }

    SettingsNumberInputCard(
        preference = preference,
        text = listOf(prefix, preference.value, suffix).fastFilterNotNull().joinToString(" "),
        onClick = { showDialog = true },
        modifier = modifier,
    )

    if (showDialog) {
        SettingsLongInputDialog(
            preference = preference,
            onUpdate = { value ->
                showDialog = false
                onUpdate(value)
            },
            onDismissRequest = { showDialog = false },
        )
    }
}

@Composable
fun SettingsNumberInputCard(
    preference: Preference,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsBaseCard(preference = preference, onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacings.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (preference.iconDrawableId != null) {
                Icon(
                    painter = painterResource(preference.iconDrawableId!!),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.default))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(preference.nameStringResource),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview
@Composable
private fun SettingsIntInputCardPreview() {
    CinefinTheme {
        SettingsIntInputCard(
            preference =
                PreferenceIntInput(
                    nameStringResource = SettingsR.string.settings_cache_size,
                    backendPreference = PreferenceBackend("", 0),
                    suffixRes = SettingsR.string.mb,
                    value = 25,
                ),
            onUpdate = {},
        )
    }
}

@Preview
@Composable
private fun SettingsLongInputCardPreview() {
    CinefinTheme {
        SettingsLongInputCard(
            preference =
                PreferenceLongInput(
                    nameStringResource = SettingsR.string.settings_cache_size,
                    backendPreference = PreferenceBackend("", 0L),
                    suffixRes = SettingsR.string.mb,
                    value = 25,
                ),
            onUpdate = {},
        )
    }
}
