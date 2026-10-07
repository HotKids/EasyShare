package me.pipi.easyshare.ui.transfer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import me.pipi.easyshare.R

// Quick Share's compact scan resource uses two linear pulses on a 224-unit, 60fps timeline.
private const val ScanLoopEndFrame = 301f

@Composable
fun NearbySearchAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Nearby device search")
    val frame = transition.animateFloat(
        initialValue = 0f,
        targetValue = ScanLoopEndFrame,
        animationSpec = infiniteRepeatable(
            animation = tween((ScanLoopEndFrame * 1000f / 60f).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Search pulse frame",
    )
    val color = MaterialTheme.colorScheme.primary

    Box(modifier.size(84.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(2) { pulse ->
                val elapsedFrames = frame.value - 84f - pulse * 38f
                drawCircle(
                    color = color,
                    radius = size.minDimension * searchPulseDiameterFraction(elapsedFrames) / 2f,
                    // Keep the pulses subordinate to Easy Share's solid foreground mark.
                    alpha = searchPulseAlpha(elapsedFrames) * 0.4f,
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.easy_share_icon_foreground),
            contentDescription = null,
            tint = color,
            // Preserve a 33dp visible mark using the original foreground's alpha bounds.
            modifier = Modifier
                .size(46.164.dp)
                .graphicsLayer { rotationZ = frame.value * 360f / ScanLoopEndFrame },
        )
    }
}

internal fun searchPulseDiameterFraction(elapsedFrames: Float): Float =
    68f * (1.3f + 2f * (elapsedFrames / 92f).coerceIn(0f, 1f)) / 224f

internal fun searchPulseAlpha(elapsedFrames: Float): Float = when {
    elapsedFrames < 0f || elapsedFrames >= 92f -> 0f
    elapsedFrames < 30f -> 0.5f * elapsedFrames / 30f
    elapsedFrames <= 46f -> 0.5f
    else -> 0.5f * (92f - elapsedFrames) / 46f
}
