package dev.octaneui.app.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The user's own GIF, bundled as an asset. It is never downloaded and never redrawn. */
const val GUEST_PREPARING_GIF = "boot/guest_preparing.gif"

/**
 * Shows the bundled GIF while the guest is being prepared. Decoding happens off the main thread; the platform
 * AnimatedImageDrawable keeps the original frames and timings and decodes lazily (low memory use).
 * If the asset is missing, cannot be decoded, or the device is older than API 28, the standard progress indicator is
 * shown instead. The animation must only be composed while preparation is really running.
 */
@Composable
fun GuestPreparingAnimation(modifier: Modifier = Modifier.size(112.dp)) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        CircularProgressIndicator(modifier)
        return
    }
    val ctx = LocalContext.current
    val drawable by produceState<AnimatedImageDrawable?>(initialValue = null, ctx) {
        value = withContext(Dispatchers.IO) { loadGif(ctx) }
    }
    val d = drawable
    if (d == null) {
        CircularProgressIndicator(modifier)
        return
    }
    DisposableEffect(d) {
        d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        d.start()
        onDispose { d.stop() }
    }
    AndroidView(
        factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(d) } },
        modifier = modifier,
    )
}

private fun loadGif(ctx: android.content.Context): AnimatedImageDrawable? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    return runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.assets, GUEST_PREPARING_GIF)) as? AnimatedImageDrawable
    }.getOrNull()
}
