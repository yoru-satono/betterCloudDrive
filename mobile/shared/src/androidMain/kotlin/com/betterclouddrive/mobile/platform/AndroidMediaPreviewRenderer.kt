package com.betterclouddrive.mobile.platform

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.betterclouddrive.mobile.core.localization.MobileString
import com.betterclouddrive.mobile.core.localization.UiMessage
import com.betterclouddrive.mobile.core.model.PreviewKind
import com.betterclouddrive.mobile.core.platform.MediaPreviewRenderer
import com.betterclouddrive.mobile.core.platform.MediaPreviewRequest

internal class AndroidMediaPreviewRenderer : MediaPreviewRenderer {
    @Composable
    override fun Render(
        request: MediaPreviewRequest,
        modifier: Modifier,
        onPositionChanged: (Long) -> Unit,
        onError: (UiMessage) -> Unit,
    ) {
        when (request.kind) {
            PreviewKind.IMAGE -> ImagePreview(request, modifier, onError)
            PreviewKind.VIDEO -> VideoPreview(request, modifier, onPositionChanged, onError)
            else -> Unit
        }
    }
}

@Composable
private fun ImagePreview(
    request: MediaPreviewRequest,
    modifier: Modifier,
    onError: (UiMessage) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember(request.fileKey) { mutableStateOf(true) }
    var scale by remember(request.fileKey) { mutableFloatStateOf(1f) }
    var offsetX by remember(request.fileKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(request.fileKey) { mutableFloatStateOf(0f) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(androidReachableUrl(request.url))
                .memoryCacheKey(request.fileKey)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onLoading = { loading = true },
            onSuccess = { loading = false },
            onError = {
                loading = false
                onError(UiMessage(MobileString.ErrorPreviewFailed))
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .pointerInput(request.fileKey) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                }
                .pointerInput(request.fileKey) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    })
                },
        )
        if (loading) CircularProgressIndicator()
    }
}

@Composable
private fun VideoPreview(
    request: MediaPreviewRequest,
    modifier: Modifier,
    onPositionChanged: (Long) -> Unit,
    onError: (UiMessage) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPositionCallback by rememberUpdatedState(onPositionChanged)
    val latestErrorCallback by rememberUpdatedState(onError)
    val player = remember(request.fileKey, request.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(androidReachableUrl(request.url))))
            prepare()
            if (request.initialPositionMillis > 0) seekTo(request.initialPositionMillis)
            playWhenReady = false
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                latestErrorCallback(UiMessage(MobileString.ErrorPreviewFailed))
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                latestPositionCallback(player.currentPosition.coerceAtLeast(0))
                player.pause()
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            latestPositionCallback(player.currentPosition.coerceAtLeast(0))
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}
