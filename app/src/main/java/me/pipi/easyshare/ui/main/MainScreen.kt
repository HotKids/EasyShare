package me.pipi.easyshare.ui.main

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pipi.easyshare.R
import me.pipi.easyshare.ui.PagAnimation
import me.pipi.easyshare.utils.DeviceUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onReceiverChanged: (Boolean) -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    onBrandChanged: (Int) -> Unit,
    onChooseReceivePath: () -> Unit,
    onEnhancedModeChanged: (Boolean) -> Unit,
    onCaptureLogs: () -> Unit,
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var showBrandDialog by remember { mutableStateOf(false) }

    if (showNameDialog) {
        DeviceNameDialog(
            currentName = state.deviceName,
            onDismiss = { showNameDialog = false },
            onSave = {
                onDeviceNameChanged(it)
                showNameDialog = false
            },
        )
    }

    if (showBrandDialog) {
        BrandSelectionDialog(
            configuredBrandId = state.configuredBrandId,
            onDismiss = { showBrandDialog = false },
            onSelect = {
                onBrandChanged(it)
                showBrandDialog = false
            },
        )
    }

    val receivePath = state.receivePath?.let(::displayReceivePath)
        ?: stringResource(R.string.default_path)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.padding(contentPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AllianceHero()
                    Text(
                        text = stringResource(R.string.compatibility_summary),
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    NativeSettingItem(
                        title = stringResource(R.string.receiver_switch_title),
                        summary = stringResource(R.string.receiver_switch_summary),
                        enabled = !state.busy,
                        onClick = { onReceiverChanged(!state.receiverEnabled) },
                        trailing = {
                            Switch(
                                checked = state.receiverEnabled,
                                enabled = !state.busy,
                                onCheckedChange = onReceiverChanged,
                            )
                        },
                    )
                    SettingDivider()
                    NativeSettingItem(
                        title = stringResource(R.string.shizuku_authorization),
                        summary = stringResource(R.string.shizuku_desc),
                        enabled = state.shizukuAvailable,
                        onClick = { onEnhancedModeChanged(!state.shizukuGranted) },
                        trailing = {
                            Switch(
                                checked = state.shizukuGranted,
                                enabled = state.shizukuAvailable,
                                onCheckedChange = onEnhancedModeChanged,
                            )
                        },
                    )
                    SettingDivider()
                    NativeSettingItem(
                        title = stringResource(R.string.device_name),
                        summary = state.deviceName,
                        onClick = { showNameDialog = true },
                    )
                    SettingDivider()
                    NativeSettingItem(
                        title = stringResource(R.string.device_brand),
                        summary = DeviceUtils.knownDeviceNameById(state.effectiveBrandId)
                            ?: stringResource(R.string.brand_android),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(
                                        DeviceUtils.deviceIconById(state.effectiveBrandId)
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    contentScale = ContentScale.Fit,
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = null,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        },
                        onClick = { showBrandDialog = true },
                    )
                    SettingDivider()
                    NativeSettingItem(
                        title = stringResource(R.string.download_path),
                        summary = receivePath,
                        onClick = onChooseReceivePath,
                    )
                    SettingDivider()
                    NativeSettingItem(
                        title = stringResource(R.string.capture_logs),
                        summary = stringResource(R.string.capture_logs_desc),
                        onClick = onCaptureLogs,
                    )
                }
            }
        }
    }
}

private fun displayReceivePath(uriString: String): String {
    val uri = Uri.parse(uriString)
    val documentPath = uri.lastPathSegment ?: uri.path ?: uriString
    val relativePath = documentPath.substringAfter(':', documentPath).trim('/')
    return relativePath.takeIf { it.isNotBlank() }?.let { "/$it" } ?: uriString
}

@Composable
private fun NativeSettingItem(
    title: String,
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        headlineContent = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Text(
                text = summary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = leading,
        trailingContent = trailing ?: {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
            )
        },
    )
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AllianceHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(312.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier.padding(top = 85.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.introduce_1),
                contentDescription = null,
                modifier = Modifier.size(width = 108.dp, height = 226.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painter = painterResource(R.drawable.introduce_2),
                contentDescription = null,
                modifier = Modifier.size(width = 108.dp, height = 226.dp),
                contentScale = ContentScale.Fit,
            )
        }
        PagAnimation(
            lightAsset = "pag/setting_bg.pag",
            darkAsset = "pag/setting_bg_dark.pag",
            modifier = Modifier.size(width = 184.dp, height = 72.dp),
        )
    }
}

@Composable
private fun DeviceNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_name)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun BrandSelectionDialog(
    configuredBrandId: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_brand)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(DeviceUtils.getBrandList(), key = { it.first }) { (id, name) ->
                    val displayName = if (id == -1) {
                        stringResource(R.string.brand_auto)
                    } else {
                        name
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = configuredBrandId == id,
                            onClick = { onSelect(id) },
                        )
                        Image(
                            painter = painterResource(
                                DeviceUtils.deviceIconById(
                                    id.takeUnless { it == -1 },
                                ),
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = displayName,
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
