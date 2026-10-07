package me.pipi.easyshare.ui.transfer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.pipi.easyshare.R
import me.pipi.easyshare.models.ReceivedFile

enum class TransferDirection { SEND, RECEIVE }

enum class TransferVisualState {
    FILE, CONNECTING, PROGRESS, FINALIZING, SUCCESS, PARTIAL, FAILURE, CANCELED,
}

enum class AttachmentKind { FILE, IMAGE, VIDEO, TEXT, MULTIPLE }

fun attachmentKind(fileName: String, mimeType: String?, isText: Boolean, fileCount: Int): AttachmentKind {
    if (isText) return AttachmentKind.TEXT
    if (mimeType?.startsWith("image/") == true) return AttachmentKind.IMAGE
    if (mimeType?.startsWith("video/") == true) return AttachmentKind.VIDEO
    if (fileCount > 1) return AttachmentKind.MULTIPLE
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif" -> AttachmentKind.IMAGE
        "mp4", "mkv", "webm", "mov", "3gp" -> AttachmentKind.VIDEO
        else -> AttachmentKind.FILE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyShareSheetContainer(
    title: String,
    centerTitle: Boolean = false,
    heightFraction: Float = 0.42f,
    fitContent: Boolean = false,
    providePaneSemantics: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
        val sheetHeight = sheetHeightDp(maxHeight.value, topInset.value, heightFraction).dp
        val heightModifier = if (fitContent) Modifier.heightIn(max = sheetHeight) else Modifier.height(sheetHeight)
        Surface(
            modifier = Modifier.widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxWidth().then(heightModifier)
                .then(if (providePaneSemantics) Modifier.semantics { paneTitle = title } else Modifier)
                .then(if (onDismiss != null) Modifier.semantics {
                    dismiss { onDismiss(); true }
                } else Modifier),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().windowInsetsPadding(
                    WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom),
                ),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 22.dp, end = 24.dp, bottom = 12.dp)
                        .semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
                content()
            }
        }
    }
}

internal fun sheetHeightDp(windowHeightDp: Float, topInsetDp: Float, requestedFraction: Float): Float {
    val fraction = if (windowHeightDp < 480f) 1f else requestedFraction
    return (windowHeightDp * fraction).coerceAtMost((windowHeightDp - topInsetDp).coerceAtLeast(0f))
}

@Composable
fun TransferSheet(
    title: String,
    partyText: String,
    @DrawableRes partyIconRes: Int,
    headlineText: String,
    supportingText: String?,
    attachmentKind: AttachmentKind,
    visualState: TransferVisualState,
    progress: Int?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    direction: TransferDirection = TransferDirection.RECEIVE,
    message: String? = null,
    emphasizePrimary: Boolean = true,
    primaryActionEnabled: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    receivedFiles: List<ReceivedFile> = emptyList(),
) {
    EasyShareSheetContainer(title, centerTitle = true, heightFraction = 1f, fitContent = true,
        onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Image(painterResource(partyIconRes), null, Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(partyText, style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
            }
            if (!message.isNullOrBlank() && message != title) {
                Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            if (direction == TransferDirection.SEND) {
                Box(Modifier.padding(top = 12.dp, bottom = 20.dp)) {
                    AttachmentText(headlineText, supportingText, TextAlign.Center)
                }
            } else {
                Spacer(Modifier.height(20.dp))
            }
            val terminal = visualState in listOf(TransferVisualState.SUCCESS, TransferVisualState.PARTIAL,
                TransferVisualState.FAILURE, TransferVisualState.CANCELED)
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 192.dp),
                color = if (terminal) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
            ) {
                Box(Modifier.padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    if (receivedFiles.isNotEmpty() &&
                        visualState in listOf(TransferVisualState.SUCCESS, TransferVisualState.PARTIAL)) {
                        ReceivedThumbnails(receivedFiles)
                    } else {
                        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                            if (visualState !in listOf(TransferVisualState.PROGRESS, TransferVisualState.CONNECTING,
                                    TransferVisualState.FINALIZING)) {
                                AttachmentVisual(attachmentKind, Modifier.size(72.dp))
                            }
                            ProgressOrOutcome(visualState, progress)
                            if (visualState == TransferVisualState.PROGRESS) {
                                Text("${transferProgressValue(progress)}%", style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.clearAndSetSemantics {})
                            }
                        }
                    }
                }
            }
        }
        EasyShareSheetActions(secondaryActionLabel, onSecondaryAction, primaryActionLabel, onPrimaryAction,
            emphasizePrimary, primaryActionEnabled, textActions = true)
    }
}

@Composable
private fun ReceivedThumbnails(files: List<ReceivedFile>) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(192.dp), contentAlignment = Alignment.Center) {
        val thumbnailSize = minOf(maxWidth * 0.52f, 180.dp)
        val displayedFiles = files.take(3)
        displayedFiles.forEachIndexed { index, file ->
            val side = displayedFiles.size > 1 && index != 1
            val displacement = thumbnailSize * when {
                displayedFiles.size == 1 -> 0f
                displayedFiles.size == 2 -> if (index == 0) -0.24f else 0.24f
                index == 0 -> -0.48f
                index == 2 -> 0.48f
                else -> 0f
            }
            FileThumbnail(file, Modifier.size(thumbnailSize).offset(x = displacement)
                .graphicsLayer { scaleX = if (side) 0.86f else 1f; scaleY = scaleX }
                .then(if (index == 1) Modifier.zIndex(1f) else Modifier))
        }
    }
}

@Composable
private fun FileThumbnail(file: ReceivedFile, modifier: Modifier) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(null, file.uri, file.mimeType) {
        value = if (file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")) {
            withContext(Dispatchers.IO) {
                try {
                    resolver.loadThumbnail(file.uri, Size(384, 384), null).asImageBitmap()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        } else null
    }
    Surface(modifier.clip(RoundedCornerShape(24.dp)), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        val image = bitmap
        if (image != null) {
            Image(image, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AttachmentVisual(attachmentKind(file.name, file.mimeType, false, 1), Modifier.size(72.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentText(title: String, metadata: String?, alignment: TextAlign) {
    var overflow by remember(title) { mutableStateOf(false) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { SelectionContainer { Text(title) } } },
        state = rememberTooltipState(isPersistent = true),
        enableUserInput = overflow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = if (alignment == TextAlign.Center) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium, textAlign = alignment,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.fillMaxWidth(),
                onTextLayout = { overflow = it.hasVisualOverflow })
            if (!metadata.isNullOrBlank()) {
                Text(metadata, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = alignment, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun AttachmentVisual(kind: AttachmentKind, modifier: Modifier) {
    val icon = when (kind) {
        AttachmentKind.IMAGE -> R.drawable.ic_attachment_image
        AttachmentKind.VIDEO -> R.drawable.ic_attachment_video
        AttachmentKind.TEXT -> R.drawable.ic_attachment_text
        AttachmentKind.MULTIPLE -> R.drawable.ic_attachment_multiple
        AttachmentKind.FILE -> R.drawable.ic_attachment_file
    }
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun BoxScope.ProgressOrOutcome(state: TransferVisualState, progress: Int?) {
    when (state) {
        TransferVisualState.PROGRESS -> MaterialWavyProgressIndicator(transferProgressValue(progress))
        TransferVisualState.CONNECTING, TransferVisualState.FINALIZING -> MaterialWavyProgressIndicator(null)
        else -> Unit
    }
    val icon = when (state) {
        TransferVisualState.SUCCESS -> R.drawable.ic_done
        TransferVisualState.PARTIAL -> R.drawable.ic_warning
        TransferVisualState.FAILURE -> R.drawable.ic_close
        TransferVisualState.CANCELED -> R.drawable.ic_close
        else -> null
    } ?: return
    val negative = state == TransferVisualState.FAILURE || state == TransferVisualState.PARTIAL
    Surface(Modifier.align(Alignment.BottomEnd).size(28.dp), shape = CircleShape,
        color = if (negative) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, modifier = Modifier.size(16.dp),
                tint = if (negative) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
fun EasyShareSheetActions(
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    emphasizePrimary: Boolean = false,
    primaryActionEnabled: Boolean = true,
    textActions: Boolean = false,
) {
    val hasSecondaryAction = secondaryActionLabel != null && onSecondaryAction != null
    if (textActions) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (hasSecondaryAction) {
                TextButton(onClick = requireNotNull(onSecondaryAction), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text(requireNotNull(secondaryActionLabel), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                }
                VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            }
            TextButton(onClick = onPrimaryAction, enabled = primaryActionEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text(primaryActionLabel, style = MaterialTheme.typography.labelLarge,
                    color = if (primaryActionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    textAlign = TextAlign.Center)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            OutlinedButton(onClick = onSecondaryAction, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(secondaryActionLabel, textAlign = TextAlign.Center)
            }
        }
        val modifier = Modifier.weight(1f, fill = hasSecondaryAction).heightIn(min = 48.dp)
        if (emphasizePrimary) {
            Button(onClick = onPrimaryAction, enabled = primaryActionEnabled, modifier = modifier) { Text(primaryActionLabel, textAlign = TextAlign.Center) }
        } else {
            OutlinedButton(onClick = onPrimaryAction, enabled = primaryActionEnabled, modifier = modifier) { Text(primaryActionLabel, textAlign = TextAlign.Center) }
        }
    }
}
