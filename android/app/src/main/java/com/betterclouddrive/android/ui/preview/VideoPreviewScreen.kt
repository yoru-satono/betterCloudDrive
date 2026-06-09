package com.betterclouddrive.android.ui.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPreviewScreen(
    fileId: Long,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri("http://10.0.2.2:8080/api/v1/preview/$fileId")
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频播放") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply { this.player = player }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
