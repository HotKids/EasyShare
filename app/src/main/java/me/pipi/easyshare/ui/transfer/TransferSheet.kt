package me.pipi.easyshare.ui.transfer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pipi.easyshare.R

enum class TransferVisualState {
    FILE,
    PROGRESS,
    SUCCESS,
    FAILURE,
}

@Composable
fun EasyShareSheetContainer(
    title: String,
    centerTitle: Boolean = false,
    heightFraction: Float = 0.42f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 22.dp, end = 28.dp, bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
fun TransferSheet(
    title: String,
    partyText: String,
    @DrawableRes partyIconRes: Int,
    headlineText: String?,
    supportingText: String?,
    fileTypeLabel: String,
    visualState: TransferVisualState,
    progress: Int?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
) {
    EasyShareSheetContainer(
        title = title,
        centerTitle = true,
        heightFraction = 0.48f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(partyIconRes),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = partyText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!headlineText.isNullOrBlank() && headlineText != partyText) {
            Text(
                text = headlineText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 12.dp, end = 28.dp),
            )
        }

        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 6.dp, end = 28.dp),
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 18.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (visualState) {
                    TransferVisualState.FILE -> FileTypeBadge(fileTypeLabel)
                    TransferVisualState.PROGRESS -> TransferProgress(progress ?: 0)
                    TransferVisualState.SUCCESS -> TransferResult(success = true)
                    TransferVisualState.FAILURE -> TransferResult(success = false)
                }
            }
        }

        EasyShareSheetActions(
            secondaryActionLabel = secondaryActionLabel,
            onSecondaryAction = onSecondaryAction,
            primaryActionLabel = primaryActionLabel,
            onPrimaryAction = onPrimaryAction,
        )
    }
}

@Composable
private fun FileTypeBadge(fileTypeLabel: String) {
    Surface(
        modifier = Modifier.size(104.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = fileTypeLabel,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TransferProgress(progress: Int) {
    val safeProgress = progress.coerceIn(0, 100)
    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { safeProgress / 100f },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 5.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            text = "$safeProgress%",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TransferResult(success: Boolean) {
    Surface(
        modifier = Modifier.size(104.dp),
        shape = CircleShape,
        color = if (success) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(if (success) R.drawable.ic_done else R.drawable.ic_close),
                contentDescription = null,
                tint = if (success) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.size(58.dp),
            )
        }
    }
}

@Composable
fun EasyShareSheetActions(
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            SheetAction(
                label = secondaryActionLabel,
                onClick = onSecondaryAction,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier.height(30.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        SheetAction(
            label = primaryActionLabel,
            onClick = onPrimaryAction,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SheetAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(0.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

fun fileTypeLabel(
    fileName: String,
    isText: Boolean,
    textLabel: String,
    fallbackLabel: String,
): String {
    if (isText) return textLabel
    val extension = fileName.substringAfterLast('.', "").uppercase()
    return extension.takeIf { it.length in 1..5 } ?: fallbackLabel
}
