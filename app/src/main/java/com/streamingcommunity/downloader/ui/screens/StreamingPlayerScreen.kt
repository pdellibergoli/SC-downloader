package com.streamingcommunity.downloader.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.streamingcommunity.downloader.core.hls.AesSegmentDecryptor
import com.streamingcommunity.downloader.core.model.StreamSource
import com.streamingcommunity.downloader.core.network.HttpClientProvider
import com.streamingcommunity.downloader.ui.StreamingPlayerUiState
import com.streamingcommunity.downloader.ui.theme.AccentCyan
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun StreamingPlayerScreen(
    state: StreamingPlayerUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onPlayPreviousEpisode: (() -> Unit)?,
    onPlayNextEpisode: (() -> Unit)?,
    onOpenExternalPlayer: (StreamSource) -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    var isLandscapeLocked by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var playerError by remember(state.streamSource) { mutableStateOf<String?>(null) }
    var pauseRequestCount by remember { mutableIntStateOf(0) }

    // Manage orientation, screen-on flag, and fullscreen system bars
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else null

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = originalOrientation
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val streamSource = state.streamSource

            when {
                state.isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = AccentCyan)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Risoluzione stream in corso...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!state.subtitleText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.subtitleText,
                                color = AccentCyan,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                !state.errorMessage.isNullOrBlank() || playerError != null -> {
                    val errText = state.errorMessage ?: playerError ?: "Errore riproduzione stream"
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Impossibile riprodurre lo stream",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errText,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                playerError = null
                                onRetry()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Riprova")
                            }
                            if (streamSource != null) {
                                OutlinedButton(onClick = {
                                    pauseRequestCount++
                                    onOpenExternalPlayer(streamSource)
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Player Esterno")
                                }
                            }
                        }
                    }
                }

                streamSource != null -> {
                    ExoPlayerContainer(
                        context = context,
                        streamSource = streamSource,
                        preferredAudioLang = state.preferredAudioLang,
                        preferredSubtitleLang = state.preferredSubtitleLang,
                        pauseRequestCount = pauseRequestCount,
                        onControlsVisibilityChanged = { visible -> controlsVisible = visible },
                        onPlayerError = { msg -> playerError = msg }
                    )
                }
            }

            // Top overlay header (visible while loading/error or when PlayerView controls are visible)
            AnimatedVisibility(
                visible = controlsVisible || state.isLoading || !state.errorMessage.isNullOrBlank() || playerError != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.82f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Chiudi player",
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = state.displayTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!state.subtitleText.isNullOrBlank()) {
                            Text(
                                text = state.subtitleText,
                                color = AccentCyan,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (onPlayPreviousEpisode != null) {
                        IconButton(onClick = onPlayPreviousEpisode) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Episodio precedente",
                                tint = Color.White
                            )
                        }
                    }

                    if (onPlayNextEpisode != null) {
                        FilledTonalButton(
                            onClick = onPlayNextEpisode,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Successivo", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (streamSource != null) {
                        IconButton(
                            onClick = {
                                pauseRequestCount++
                                onOpenExternalPlayer(streamSource)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Apri in player esterno",
                                tint = Color.White
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val activity = context.findActivity()
                            isLandscapeLocked = !isLandscapeLocked
                            activity?.requestedOrientation = if (isLandscapeLocked) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Ruota schermo",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerContainer(
    context: Context,
    streamSource: StreamSource,
    preferredAudioLang: String?,
    preferredSubtitleLang: String?,
    pauseRequestCount: Int,
    onControlsVisibilityChanged: (Boolean) -> Unit,
    onPlayerError: (String) -> Unit
) {
    val ua = remember { HttpClientProvider.randomUserAgent() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(streamSource.m3u8Url, streamSource.referer, streamSource.keyHex) {
        val preloadedKeyBytes = streamSource.keyHex
            ?.takeIf { it.length == 32 }
            ?.let { runCatching { AesSegmentDecryptor.hexToBytes(it) }.getOrNull() }

        val hlsHttpClient = HttpClientProvider.sharedClient.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val path = original.url.encodedPath

                // If Vixcloud HLS playlist requests /storage/enc.key and we already fetched keyHex,
                // serve the 16 raw key bytes directly so AES-128 decryption never fails.
                if (preloadedKeyBytes != null && path.endsWith("enc.key", ignoreCase = true)) {
                    return@Interceptor Response.Builder()
                        .request(original)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(preloadedKeyBytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }

                val reqWithHeaders = original.newBuilder()
                    .header("Referer", streamSource.referer)
                    .header("User-Agent", ua)
                    .build()
                val resp = chain.proceed(reqWithHeaders)
                if (resp.code == 403 && "b=1" !in original.url.query.orEmpty() && "playlist" in path) {
                    resp.close()
                    val b1Url = original.url.newBuilder().addQueryParameter("b", "1").build()
                    val b1Req = reqWithHeaders.newBuilder().url(b1Url).build()
                    return@Interceptor chain.proceed(b1Req)
                }
                resp
            })
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(hlsHttpClient)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to streamSource.referer,
                    "User-Agent" to ua
                )
            )

        val hlsMediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(streamSource.m3u8Url))

        ExoPlayer.Builder(context).build().apply {
            val paramsBuilder = trackSelectionParameters.buildUpon()
            if (!preferredAudioLang.isNullOrBlank()) {
                paramsBuilder.setPreferredAudioLanguage(preferredAudioLang)
            }
            if (!preferredSubtitleLang.isNullOrBlank()) {
                paramsBuilder.setPreferredTextLanguage(preferredSubtitleLang)
                paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            trackSelectionParameters = paramsBuilder.build()

            setMediaSource(hlsMediaSource)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlayerError(error.localizedMessage ?: "Errore ExoPlayer (${error.errorCodeName})")
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(pauseRequestCount) {
        if (pauseRequestCount > 0) {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(preferredAudioLang, preferredSubtitleLang) {
        val paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon()
        if (!preferredAudioLang.isNullOrBlank()) {
            paramsBuilder.setPreferredAudioLanguage(preferredAudioLang)
        }
        if (!preferredSubtitleLang.isNullOrBlank()) {
            paramsBuilder.setPreferredTextLanguage(preferredSubtitleLang)
            paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        exoPlayer.trackSelectionParameters = paramsBuilder.build()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowSubtitleButton(true)
                setShowFastForwardButton(true)
                setShowRewindButton(true)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        onControlsVisibilityChanged(visibility == View.VISIBLE)
                    }
                )
            }
        },
        update = { playerView ->
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }
        }
    )
}
