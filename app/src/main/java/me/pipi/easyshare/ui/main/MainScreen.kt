package me.pipi.easyshare.ui.main

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.material.color.ColorRoles
import com.google.android.material.color.MaterialColors
import me.pipi.easyshare.R
import me.pipi.easyshare.ui.PagAnimation
import me.pipi.easyshare.ui.transfer.EasyShareSheetActions
import me.pipi.easyshare.ui.transfer.EasyShareSheetContainer
import me.pipi.easyshare.utils.DeviceUtils

private val HomeItemMinHeight = 84.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onReceiverChanged: (Boolean) -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    onBrandChanged: (Int) -> Unit,
    onChooseReceivePath: () -> Unit,
    onSecureReceiveOnlyChanged: (Boolean) -> Unit,
    onEnhancedModeChanged: (Boolean) -> Unit,
    onCaptureLogs: () -> Unit,
) {
    var showNameDialog by rememberSaveable { mutableStateOf(false) }
    var showBrandDialog by rememberSaveable { mutableStateOf(false) }
    val singlePaneListState = rememberLazyListState()
    val receiveControlsListState = rememberLazyListState()
    val introductionScrollState = rememberScrollState()

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
    val effectiveBrandName = DeviceUtils.knownDeviceNameById(state.effectiveBrandId)
        ?: stringResource(R.string.brand_android)
    val secureTransferLabel = stringResource(R.string.secure_receive_title)
    val enhancedModeLabel = stringResource(R.string.shizuku_authorization)
    val homeControls: @Composable () -> Unit = {
        ReceiveFilesCard(
            state = state,
            onReceiverChanged = onReceiverChanged,
        )
        Spacer(Modifier.height(20.dp))
    }
    val settingsContent: @Composable () -> Unit = {
        SettingsCard {
            NativeSettingItem(
                title = enhancedModeLabel,
                summary = stringResource(R.string.shizuku_desc),
                enabled = state.shizukuAvailable,
                checked = state.shizukuGranted,
                onClick = { onEnhancedModeChanged(!state.shizukuGranted) },
                trailing = {
                    Switch(
                        checked = state.shizukuGranted,
                        enabled = state.shizukuAvailable,
                        onCheckedChange = null,
                    )
                },
            )
            SettingDivider()
            NativeSettingItem(
                title = secureTransferLabel,
                summary = stringResource(R.string.secure_receive_summary),
                checked = state.secureReceiveOnly,
                onClick = { onSecureReceiveOnlyChanged(!state.secureReceiveOnly) },
                trailing = {
                    Switch(
                        checked = state.secureReceiveOnly,
                        onCheckedChange = null,
                    )
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        SettingsCard {
            NativeSettingItem(
                title = stringResource(R.string.download_path),
                summary = receivePath,
                onClick = onChooseReceivePath,
                summaryMaxLines = 2,
            )
            SettingDivider()
            NativeSettingItem(
                title = stringResource(R.string.capture_logs),
                summary = stringResource(R.string.capture_logs_desc),
                onClick = onCaptureLogs,
            )
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (usesTwoPaneHome(maxWidth.value, maxHeight.value)) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 1200.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(introductionScrollState),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AllianceIntroduction()
                        LocalDeviceCard(
                            state.deviceName, state.effectiveBrandId, effectiveBrandName,
                            wifiEnabled = state.wifiEnabled,
                            bluetoothEnabled = state.bluetoothEnabled,
                            transferStatus = state.transferStatus,
                            onNameClick = { showNameDialog = true },
                            onBrandClick = { showBrandDialog = true },
                        )
                    }
                    LazyColumn(
                        state = receiveControlsListState,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
                    ) {
                        item {
                            Column {
                                homeControls()
                                settingsContent()
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = singlePaneListState,
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 24.dp, top = 12.dp, end = 24.dp, bottom = 34.dp,
                    ),
                ) {
                    item { AllianceIntroduction() }
                    item {
                        LocalDeviceCard(
                            state.deviceName, state.effectiveBrandId, effectiveBrandName,
                            wifiEnabled = state.wifiEnabled,
                            bluetoothEnabled = state.bluetoothEnabled,
                            transferStatus = state.transferStatus,
                            onNameClick = { showNameDialog = true },
                            onBrandClick = { showBrandDialog = true },
                        )
                    }
                    item {
                        Column {
                            homeControls()
                            settingsContent()
                        }
                    }
                }
            }
        }
    }
}

internal fun usesTwoPaneHome(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 840f && heightDp >= 480f

@Composable
private fun AllianceIntroduction() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AllianceHero()
        Text(
            text = stringResource(R.string.compatibility_summary),
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LocalDeviceCard(
    deviceName: String,
    brandId: Int,
    brandName: String,
    wifiEnabled: Boolean,
    bluetoothEnabled: Boolean,
    transferStatus: HomeTransferStatus?,
    onNameClick: () -> Unit,
    onBrandClick: () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val colorRoles = remember(brandId, darkTheme) { brandCardColorRoles(brandId, darkTheme) }
    val contentColor = Color(colorRoles.onAccentContainer)
    val statusText = if (transferStatus == null) {
        stringResource(receiveAvailabilityText(wifiEnabled, bluetoothEnabled), brandName)
    } else {
        val description = if (transferStatus.progress == null) {
            stringResource(transferStatus.textRes)
        } else {
            stringResource(transferStatus.textRes, transferStatus.progress)
        }
        stringResource(R.string.home_brand_status, brandName, description)
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(colorRoles.accentContainer),
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = HomeItemMinHeight)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(60.dp).clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.device_brand),
                    onClick = onBrandClick,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(DeviceUtils.deviceIconById(brandId)),
                    contentDescription = brandName,
                    modifier = Modifier.size(56.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(
                Modifier.weight(1f).heightIn(min = 60.dp).clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.device_name),
                    onClick = onNameClick,
                ),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}

internal fun brandCardColorRoles(brandId: Int, darkTheme: Boolean): ColorRoles =
    MaterialColors.getColorRoles(DeviceUtils.devicePrimaryColorById(brandId), !darkTheme)

internal fun receiveAvailabilityText(wifiEnabled: Boolean, bluetoothEnabled: Boolean): Int = when {
    wifiEnabled && bluetoothEnabled -> R.string.brand_receive_ready
    wifiEnabled -> R.string.brand_receive_bluetooth_off
    bluetoothEnabled -> R.string.brand_receive_wifi_off
    else -> R.string.brand_receive_radios_off
}

@Composable
private fun ReceiveFilesCard(state: MainUiState, onReceiverChanged: (Boolean) -> Unit) {
    val receiveFilesLabel = stringResource(R.string.receiver_switch_title)
    val darkTheme = isSystemInDarkTheme()
    // The high-contrast monochrome scheme makes primaryContainer nearly black even in light mode.
    val containerColor = colorResource(if (darkTheme) android.R.color.system_accent1_700 else android.R.color.system_accent1_100)
    val contentColor = colorResource(if (darkTheme) android.R.color.system_accent1_50 else android.R.color.system_accent1_900)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = HomeItemMinHeight)
                .toggleable(
                    value = state.receiverEnabled,
                    role = Role.Switch,
                    onValueChange = onReceiverChanged,
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = receiveFilesLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.receiver_switch_summary),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                checked = state.receiverEnabled,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        content()
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
    checked: Boolean? = null,
    summaryMaxLines: Int = Int.MAX_VALUE,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionModifier = if (checked != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeItemMinHeight)
            .then(interactionModifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.let {
                    if (enabled) it else it.copy(alpha = 0.38f)
                },
            )
            Text(
                text = summary,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.let {
                    if (enabled) it else it.copy(alpha = 0.38f)
                },
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AllianceHero() {
    val darkTheme = isSystemInDarkTheme()
    // Keep decorative artwork lighter than controls, including monochrome palettes.
    val artworkColor = colorResource(android.R.color.system_accent1_200).toArgb()
    val illustrationColor = colorResource(android.R.color.system_accent1_400).toArgb()
    val illustrationFilter = remember(illustrationColor) {
        ColorFilter.colorMatrix(ColorMatrix(heroIllustrationColorMatrix(illustrationColor)))
    }
    val artworkEffect = remember(artworkColor, darkTheme) {
        RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(heroAnimationColorMatrix(artworkColor, darkTheme)),
        ).asComposeRenderEffect()
    }
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
                colorFilter = illustrationFilter,
            )
            Image(
                painter = painterResource(R.drawable.introduce_2),
                contentDescription = null,
                modifier = Modifier.size(width = 108.dp, height = 226.dp),
                contentScale = ContentScale.Fit,
                colorFilter = illustrationFilter,
            )
        }
        PagAnimation(
            lightAsset = "pag/setting_bg.pag",
            darkAsset = "pag/setting_bg_dark.pag",
            modifier = Modifier
                .size(width = 184.dp, height = 72.dp)
                .graphicsLayer {
                    renderEffect = artworkEffect
                },
        )
    }
}

internal fun heroAnimationColorMatrix(color: Int, darkTheme: Boolean): FloatArray {
    // The PAG variants embed near-white and slate artwork with different base tones.
    val sourceRed = if (darkTheme) 42f else 255f
    val sourceGreen = if (darkTheme) 45f else 255f
    val sourceBlue = if (darkTheme) 46f else 255f
    return floatArrayOf(
        1f, 0f, 0f, 0f, ((color ushr 16) and 255) - sourceRed,
        0f, 1f, 0f, 0f, ((color ushr 8) and 255) - sourceGreen,
        0f, 0f, 1f, 0f, (color and 255) - sourceBlue,
        0f, 0f, 0f, 1f, 0f,
    )
}

internal fun heroIllustrationColorMatrix(color: Int): FloatArray {
    // Use the coral's red/green difference so neutral phone surfaces stay unchanged.
    val redShift = (((color ushr 16) and 255) - 244f) / (244f - 103f)
    val greenShift = (((color ushr 8) and 255) - 103f) / (244f - 103f)
    val blueShift = ((color and 255) - 88f) / (244f - 103f)
    return floatArrayOf(
        1f + redShift, -redShift, 0f, 0f, 0f,
        greenShift, 1f - greenShift, 0f, 0f, 0f,
        blueShift, -blueShift, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

@Composable
private fun DeviceNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val deviceNameLabel = stringResource(R.string.device_name)
    HomeSettingsSheet(deviceNameLabel, onDismiss) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(deviceNameLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(name) }),
                shape = RoundedCornerShape(16.dp),
            )
        }
        EasyShareSheetActions(
            secondaryActionLabel = stringResource(R.string.cancel),
            onSecondaryAction = onDismiss,
            primaryActionLabel = stringResource(R.string.save),
            onPrimaryAction = { onSave(name) },
            emphasizePrimary = true,
        )
    }
}

@Composable
private fun BrandSelectionDialog(
    configuredBrandId: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    HomeSettingsSheet(stringResource(R.string.device_brand), onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
            items(DeviceUtils.getBrandList(), key = { it.first }) { (id, name) ->
                val displayName = if (id == -1) {
                    stringResource(R.string.brand_auto)
                } else {
                    name
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .selectable(
                            selected = configuredBrandId == id,
                            role = Role.RadioButton,
                            onClick = { onSelect(id) },
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = configuredBrandId == id,
                        onClick = null,
                    )
                    Spacer(Modifier.width(12.dp))
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
        EasyShareSheetActions(
            secondaryActionLabel = null,
            onSecondaryAction = null,
            primaryActionLabel = stringResource(R.string.cancel),
            onPrimaryAction = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = null,
        // The shared container owns navigation and gesture insets.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        EasyShareSheetContainer(
            title,
            centerTitle = true,
            heightFraction = 0.48f,
            fitContent = true,
            providePaneSemantics = false,
            content = content,
        )
    }
}
