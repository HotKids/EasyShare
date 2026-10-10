package me.pipi.easyshare.ui.main

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
    onRecoverReceive: () -> Unit,
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
                summary = stringResource(if (state.shizukuAvailable) R.string.shizuku_desc else R.string.shizuku_unavailable),
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
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val availableHeightDp = maxHeight.value
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
                        AllianceIntroduction(availableHeightDp)
                        LocalDeviceCard(
                            state, effectiveBrandName,
                            onNameClick = { showNameDialog = true },
                            onBrandClick = { showBrandDialog = true },
                            onRecoverReceive = onRecoverReceive,
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
                    item { AllianceIntroduction(availableHeightDp) }
                    item {
                        LocalDeviceCard(
                            state, effectiveBrandName,
                            onNameClick = { showNameDialog = true },
                            onBrandClick = { showBrandDialog = true },
                            onRecoverReceive = onRecoverReceive,
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
private fun AllianceIntroduction(availableHeightDp: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AllianceHero(availableHeightDp)
        Text(
            text = stringResource(R.string.compatibility_summary),
            modifier = Modifier.fillMaxWidth().padding(vertical = if (availableHeightDp < 480f) 12.dp else 28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LocalDeviceCard(
    state: MainUiState,
    brandName: String,
    onNameClick: () -> Unit,
    onBrandClick: () -> Unit,
    onRecoverReceive: () -> Unit,
) {
    val brandId = state.effectiveBrandId
    val transferStatus = state.transferStatus
    val canRecoverReceive = transferStatus == null && homeReceiveRecovery(state) != HomeReceiveRecovery.NONE
    val darkTheme = isSystemInDarkTheme()
    val colorRoles = remember(brandId, darkTheme) { brandCardColorRoles(brandId, darkTheme) }
    val contentColor = Color(colorRoles.onAccentContainer)
    val statusText = if (transferStatus == null) {
        stringResource(receiveAvailabilityText(state), brandName)
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
                Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).clickable(
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
                Modifier.weight(1f).heightIn(min = 60.dp)
                    .then(if (!canRecoverReceive) Modifier.clip(RoundedCornerShape(16.dp)).clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.device_name),
                        onClick = onNameClick,
                    ).semantics {
                        stateDescription = statusText
                        val progress = transferStatus?.progress
                        if (progress != null) {
                            progressBarRangeInfo = ProgressBarRangeInfo(progress / 100f, 0f..1f)
                        } else {
                            liveRegion = LiveRegionMode.Polite
                        }
                    } else Modifier),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.fillMaxWidth().then(if (canRecoverReceive) Modifier.heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp)).clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.device_name),
                        onClick = onNameClick,
                    ) else Modifier),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(state.deviceName, style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        .then(if (canRecoverReceive) Modifier.heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onRecoverReceive)
                            .semantics(mergeDescendants = true) {
                                liveRegion = LiveRegionMode.Polite
                            }
                        else Modifier.clearAndSetSemantics {}),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(statusText, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = contentColor)
                    if (canRecoverReceive) {
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

internal fun brandCardColorRoles(brandId: Int, darkTheme: Boolean): ColorRoles =
    MaterialColors.getColorRoles(DeviceUtils.devicePrimaryColorById(brandId), !darkTheme)

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
    val uri = uriString.toUri()
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
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AllianceHero(availableHeightDp: Float) {
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
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val artworkScale = homeHeroArtworkScale(maxWidth.value, availableHeightDp)
        Box(Modifier.fillMaxWidth().height(312.dp * artworkScale), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier.padding(top = 85.dp * artworkScale),
                horizontalArrangement = Arrangement.spacedBy(48.dp * artworkScale),
            ) {
                Image(
                    painter = painterResource(R.drawable.introduce_1),
                    contentDescription = null,
                    modifier = Modifier.size(width = 108.dp * artworkScale, height = 226.dp * artworkScale),
                    contentScale = ContentScale.Fit,
                    colorFilter = illustrationFilter,
                )
                Image(
                    painter = painterResource(R.drawable.introduce_2),
                    contentDescription = null,
                    modifier = Modifier.size(width = 108.dp * artworkScale, height = 226.dp * artworkScale),
                    contentScale = ContentScale.Fit,
                    colorFilter = illustrationFilter,
                )
            }
            PagAnimation(
                lightAsset = "pag/setting_bg.pag",
                darkAsset = "pag/setting_bg_dark.pag",
                modifier = Modifier
                    .size(width = 184.dp * artworkScale, height = 72.dp * artworkScale)
                    .graphicsLayer {
                        renderEffect = artworkEffect
                    },
            )
        }
    }
}

internal fun homeHeroArtworkScale(widthDp: Float, availableHeightDp: Float): Float {
    val widthScale = (widthDp / 264f).coerceIn(0f, 1f)
    // Short windows reserve the rest of the viewport for identity and receive controls.
    val heightScale = if (availableHeightDp < 480f) (availableHeightDp / 3f / 312f).coerceAtLeast(0f) else 1f
    return minOf(widthScale, heightScale)
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
    val focusRequester = remember { FocusRequester() }
    val deviceNameLabel = stringResource(R.string.device_name)
    val validationMessage = when (deviceNameError(name)) {
        DeviceNameError.EMPTY -> stringResource(R.string.device_name_empty)
        DeviceNameError.TOO_LONG -> stringResource(R.string.device_name_too_long)
        null -> null
    }
    val saveName = { if (validationMessage == null) onSave(name.trim()) }
    HomeSettingsSheet(deviceNameLabel, onDismiss) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    .then(if (validationMessage != null) Modifier.semantics { error(validationMessage) } else Modifier),
                label = { Text(deviceNameLabel) },
                isError = validationMessage != null,
                supportingText = if (validationMessage != null) { { Text(validationMessage) } } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveName() }),
                shape = RoundedCornerShape(16.dp),
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
        EasyShareSheetActions(
            secondaryActionLabel = stringResource(R.string.cancel),
            onSecondaryAction = onDismiss,
            primaryActionLabel = stringResource(R.string.save),
            onPrimaryAction = saveName,
            primaryActionEnabled = validationMessage == null,
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
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        dragHandle = null,
        // The shared container owns navigation and gesture insets.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        EasyShareSheetContainer(
            title,
            centerTitle = true,
            heightFraction = 0.48f,
            providePaneSemantics = false,
            content = content,
        )
    }
}
