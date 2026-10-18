package me.pipi.easyshare.ui.transfer

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import me.pipi.easyshare.R

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
    heightFraction: Float? = null,
    animateSize: Boolean = false,
    providePaneSemantics: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
        val availableHeight = (heightFraction?.let {
            sheetHeightDp(maxHeight.value, topInset.value, it, LocalDensity.current.fontScale)
        } ?: availableSheetHeightDp(maxHeight.value, topInset.value)).dp
        Surface(
            modifier = Modifier.widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxWidth()
                .then(if (animateSize) Modifier.animateContentSize(alignment = Alignment.BottomCenter) else Modifier)
                .heightIn(max = availableHeight)
                .then(if (providePaneSemantics) Modifier.semantics { paneTitle = title } else Modifier)
                .then(if (onDismiss != null) Modifier.semantics {
                    dismiss { onDismiss(); true }
                } else Modifier),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 24.dp, end = 24.dp)
                        .semantics { heading() },
                )
                content()
            }
        }
    }
}

internal fun availableSheetHeightDp(windowHeightDp: Float, topInsetDp: Float): Float =
    (windowHeightDp - topInsetDp).coerceAtLeast(0f)

internal fun sheetHeightDp(windowHeightDp: Float, topInsetDp: Float, requestedFraction: Float, fontScale: Float = 1f): Float {
    val fraction = if (windowHeightDp < 480f) 1f else (requestedFraction * fontScale.coerceAtLeast(1f)).coerceAtMost(1f)
    return (windowHeightDp * fraction).coerceAtMost(availableSheetHeightDp(windowHeightDp, topInsetDp))
}

@Composable
fun ColumnScope.TransferSheetBody(
    partyText: String,
    @DrawableRes partyIconRes: Int? = null,
    fileSize: String? = null,
    message: String? = null,
    visualState: TransferVisualState = TransferVisualState.FILE,
    visualContent: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.padding(top = 24.dp).heightIn(min = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val style = MaterialTheme.typography.titleSmall
            val resolvedTypeface by LocalFontFamilyResolver.current.resolve(
                style.fontFamily, style.fontWeight ?: FontWeight.Normal,
                style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All,
            )
            val fontSizePx = with(LocalDensity.current) { style.fontSize.toPx() }
            val textPaint = remember(resolvedTypeface, fontSizePx) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = resolvedTypeface as Typeface
                    textSize = fontSizePx
                }
            }
            var iconOffsetPx by remember(partyText, partyIconRes, resolvedTypeface, fontSizePx) { mutableIntStateOf(0) }
            val status = buildAnnotatedString {
                if (partyIconRes != null) {
                    appendInlineContent("partyIcon")
                    append('\u2060')
                }
                append(partyText)
            }
            val inlineContent = if (partyIconRes != null) {
                val width = with(LocalDensity.current) { (style.fontSize.toDp() + 4.dp).toSp() }
                mapOf("partyIcon" to InlineTextContent(
                    Placeholder(width, style.fontSize, PlaceholderVerticalAlign.TextCenter),
                ) {
                    Image(painterResource(partyIconRes), null,
                        Modifier.offset { IntOffset(0, iconOffsetPx) }.fillMaxSize().padding(end = 4.dp))
                })
            } else emptyMap()
            Text(status, inlineContent = inlineContent, style = style,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                onTextLayout = { layout ->
                    layout.placeholderRects.firstOrNull()?.let { icon ->
                        // Align to the visible first-line glyphs, not the font's asymmetric line box.
                        val textStart = status.length - partyText.length
                        val firstLine = partyText.take((layout.getLineEnd(0, visibleEnd = true) - textStart).coerceAtLeast(0))
                        val bounds = Rect()
                        textPaint.getTextBounds(firstLine, 0, firstLine.length, bounds)
                        iconOffsetPx = if (bounds.isEmpty) 0 else
                            (layout.firstBaseline + bounds.exactCenterY() - icon.center.y).roundToInt()
                    }
                },
                modifier = Modifier.clearAndSetSemantics {
                    text = AnnotatedString(partyText)
                    liveRegion = LiveRegionMode.Polite
                })
        }
        transferSheetExplanation(partyText, message)?.let { explanation ->
            Text(explanation, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = if (visualState in listOf(TransferVisualState.FAILURE, TransferVisualState.PARTIAL))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
        // Short windows scroll the measured media content above the persistent action row.
        Box(Modifier.fillMaxWidth().heightIn(min = 252.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.padding(top = 57.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center, content = visualContent)
                if (!fileSize.isNullOrBlank()) {
                    Text(fileSize, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

internal fun transferSheetExplanation(status: String, message: String?): String? =
    message?.takeIf { it.isNotBlank() && it != status }

@Composable
fun ColumnScope.TransferSheetContent(
    partyText: String,
    @DrawableRes partyIconRes: Int?,
    attachmentKind: AttachmentKind,
    visualState: TransferVisualState,
    progress: Int?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    message: String? = null,
    emphasizePrimary: Boolean = true,
    primaryActionEnabled: Boolean = true,
    fileSize: String? = null,
) {
    TransferSheetBody(partyText, partyIconRes, fileSize, message, visualState) {
        if (visualState !in listOf(TransferVisualState.PROGRESS, TransferVisualState.CONNECTING,
                TransferVisualState.FINALIZING)) {
            AttachmentVisual(attachmentKind)
        }
        ProgressOrOutcome(visualState, progress)
        if (visualState == TransferVisualState.PROGRESS) {
            Text("${transferProgressValue(progress)}%", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clearAndSetSemantics {})
        }
    }
    EasyShareSheetActions(secondaryActionLabel, onSecondaryAction, primaryActionLabel, onPrimaryAction,
        emphasizePrimary, primaryActionEnabled)
}

@Composable
private fun AttachmentVisual(kind: AttachmentKind) {
    val icon = when (kind) {
        AttachmentKind.IMAGE -> R.drawable.ic_attachment_image
        AttachmentKind.VIDEO -> R.drawable.ic_attachment_video
        AttachmentKind.TEXT -> R.drawable.ic_attachment_text
        AttachmentKind.MULTIPLE -> R.drawable.ic_attachment_multiple
        AttachmentKind.FILE -> R.drawable.ic_attachment_file
    }
    Surface(Modifier.size(80.dp), shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp))
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
    Surface(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp).size(28.dp), shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Surface(Modifier.padding(2.dp), shape = CircleShape,
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, modifier = Modifier.size(16.dp),
                    tint = if (negative) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)
            }
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
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            OutlinedButton(onClick = onSecondaryAction, modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
                Text(secondaryActionLabel, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
        val modifier = Modifier.defaultMinSize(minHeight = 40.dp)
        val padding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
        if (emphasizePrimary) {
            Button(onClick = onPrimaryAction, enabled = primaryActionEnabled, modifier = modifier, contentPadding = padding) {
                Text(primaryActionLabel, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        } else {
            OutlinedButton(onClick = onPrimaryAction, enabled = primaryActionEnabled, modifier = modifier, contentPadding = padding) {
                Text(primaryActionLabel, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}
