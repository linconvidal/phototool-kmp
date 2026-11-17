package br.com.lincon.phototool.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import br.com.lincon.phototool.domain.Photo
import br.com.lincon.phototool.state.RemoteBrowseState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// At most one presented bitmap and one decode in flight. Neighbors use the host's bounded cache.
internal const val REMOTE_DETAIL_DIMENSION = 1600
// Full-size decodes stall the UI thread on Wasm. Hosts prefetch a display-sized tier instead;
// the byte-identical original is only decoded when the viewer reaches 1:1.
internal data class PresentedPreview(val photo: Photo, val bitmap: ImageBitmap, val original: OriginalImage? = null)

@Composable
internal fun remoteDetailPreview(remote: RemoteBrowseState?, callbacks: AppCallbacks): PresentedPreview? {
    var presented by remember { mutableStateOf<PresentedPreview?>(null) }
    val currentCallbacks by rememberUpdatedState(callbacks)
    val pending = remote?.pendingDetail
    LaunchedEffect(pending, callbacks.imageLoader) {
        if (pending == null) return@LaunchedEffect
        var bitmap = currentCallbacks.imageLoader.load(pending.photo, REMOTE_DETAIL_DIMENSION)
        currentCoroutineContext().ensureActive()
        // Rapid navigation can lose a preview to cancellation or a transient server slot.
        for (backoff in longArrayOf(160, 400, 800)) {
            if (bitmap != null) break
            kotlinx.coroutines.delay(backoff)
            bitmap = currentCallbacks.imageLoader.load(pending.photo, REMOTE_DETAIL_DIMENSION)
            currentCoroutineContext().ensureActive()
        }
        if (currentCallbacks.presentDetail(pending.ticket, bitmap != null) && bitmap != null) {
            // No suspension between acknowledgement and image publication: one Compose frame.
            presented = PresentedPreview(pending.photo, bitmap)
            // Display-sized upgrade survives the ticket's state update; the full decode waits for 1:1.
            val display = withContext(NonCancellable) {
                try {
                    currentCallbacks.imageLoader.prefetchDisplay(pending.photo)
                } catch (_: Throwable) {
                    null
                }
            }
            val latest = presented
            if (display != null && latest?.photo?.id == pending.photo.id &&
                latest.photo.presentation?.previewUrl == pending.photo.presentation?.previewUrl
            ) {
                presented = latest.copy(bitmap = display)
            }
        }
    }
    remote?.preparePhotos?.take(2)?.forEach { photo ->
        // The second detail arriving must not cancel/restart the first neighbor's decode.
        key(photo.id, photo.presentation?.previewUrl) {
            LaunchedEffect(callbacks.imageLoader) {
                currentCallbacks.imageLoader.load(photo, REMOTE_DETAIL_DIMENSION)
            }
        }
    }
    DisposableEffect(presented?.original) {
        val original = presented?.original
        onDispose { original?.close() }
    }
    // StateFlow collection and this local state need not reach composition together.
    // Always render the pair, never select a bitmap using independently delivered metadata.
    return presented?.let { pair ->
        // Editorial readback does not change media. Keep the bitmap and update only its own photo.
        val latest = remote?.detail?.takeIf {
            it.id == pair.photo.id && it.presentation?.previewUrl == pair.photo.presentation?.previewUrl
        }
        if (latest == null) pair else pair.copy(photo = latest)
    }
}
