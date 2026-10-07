package me.pipi.easyshare.ui.transfer

import android.view.LayoutInflater
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.material.progressindicator.CircularProgressIndicator
import me.pipi.easyshare.R

internal fun transferProgressValue(progress: Int?): Int = (progress ?: 0).coerceIn(0, 99)

@Composable
internal fun MaterialWavyProgressIndicator(progress: Int?) {
    val value = transferProgressValue(progress)
    val activeColor = MaterialTheme.colorScheme.primary.toArgb()
    val trackColor = MaterialTheme.colorScheme.secondaryContainer.toArgb()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val semantics = if (progress == null) Modifier.progressSemantics() else Modifier.progressSemantics(value / 100f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val diameter = with(LocalDensity.current) { minOf(maxWidth, maxHeight).roundToPx() }
        key(progress == null, lifecycle) {
            AndroidView(
                modifier = Modifier.fillMaxSize().then(semantics),
                factory = { context ->
                    val indicator = (LayoutInflater.from(context).inflate(R.layout.transfer_wavy_progress, null, false)
                        as CircularProgressIndicator).apply {
                        max = 100
                        isIndeterminate = progress == null
                    }
                    val observer = object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            indicator.waveSpeed = indicator.wavelengthDeterminate
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            indicator.waveSpeed = 0
                        }
                    }
                    indicator.tag = observer
                    lifecycle.addObserver(observer)
                    indicator
                },
                onRelease = { indicator ->
                    (indicator.tag as? DefaultLifecycleObserver)?.let(lifecycle::removeObserver)
                    indicator.tag = null
                    // MDC 1.14 does not stop its determinate wave animator on detach.
                    indicator.waveSpeed = 0
                },
                update = { indicator ->
                    indicator.indicatorSize = diameter
                    indicator.setIndicatorColor(activeColor)
                    indicator.trackColor = trackColor
                    if (progress != null) indicator.setProgressCompat(value, true)
                },
            )
        }
    }
}
