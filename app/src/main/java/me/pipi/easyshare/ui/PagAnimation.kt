package me.pipi.easyshare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.libpag.PAGFile
import org.libpag.PAGImageView

@Composable
fun PagAnimation(
    lightAsset: String,
    darkAsset: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val asset = if (isSystemInDarkTheme()) darkAsset else lightAsset

    key(asset) {
        AndroidView(
            modifier = modifier,
            factory = {
                PAGImageView(context).apply {
                    setComposition(PAGFile.Load(context.assets, asset))
                    setRepeatCount(-1)
                    play()
                }
            },
        )
    }
}
