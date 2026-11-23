package com.streamingcommunity.downloader.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.GestureDetector
import android.view.MotionEvent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    onSaveProgress: (mediaKey: String, positionMs: Long, durationMs: Long) -> Unit,
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
                        mediaKey = state.mediaKey,
                        initialPositionMs = state.initialPositionMs,
                        preferredAudioLang = state.preferredAudioLang,
                        preferredSubtitleLang = state.preferredSubtitleLang,
                        pauseRequestCount = pauseRequestCount,
                        onControlsVisibilityChanged = { visible -> controlsVisible = visible },
                        onSaveProgress = onSaveProgress,
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

@SuppressLint("ClickableViewAccessibility")
@OptIn(UnstableApi::class)
@Composable
private fun ExoPlayerContainer(
    context: Context,
    streamSource: StreamSource,
    mediaKey: String,
    initialPositionMs: Long,
    preferredAudioLang: String?,
    preferredSubtitleLang: String?,
    pauseRequestCount: Int,
    onControlsVisibilityChanged: (Boolean) -> Unit,
    onSaveProgress: (mediaKey: String, positionMs: Long, durationMs: Long) -> Unit,
    onPlayerError: (String) -> Unit
) {
    val ua = remember { HttpClientProvider.randomUserAgent() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentMediaKey by rememberUpdatedState(mediaKey)
    val currentOnSaveProgress by rememberUpdatedState(onSaveProgress)

    var seekFeedbackDirection by remember { mutableIntStateOf(0) } // -1 rewind, 1 forward, 0 hidden
    var seekFeedbackSeconds by remember { mutableIntStateOf(0) }
    var seekFeedbackTrigger by remember { mutableIntStateOf(0) }
    var showResumedBadge by remember(streamSource.m3u8Url, initialPositionMs) {
        mutableStateOf(initialPositionMs > 0L)
    }

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

        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .apply {
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
                if (initialPositionMs > 0L) {
                    seekTo(initialPositionMs)
                }
                prepare()
                playWhenReady = true
            }
    }

    fun persistCurrentPosition(forceEnd: Boolean = false) {
        val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        if (forceEnd) {
            if (dur > 0L) {
                currentOnSaveProgress(currentMediaKey, dur, dur)
            }
            return
        }
        val state = exoPlayer.playbackState
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
            val pos = exoPlayer.currentPosition
            if (pos >= 5_000L) {
                currentOnSaveProgress(currentMediaKey, pos, dur)
            }
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                persistCurrentPosition()
                onPlayerError(error.localizedMessage ?: "Errore ExoPlayer (${error.errorCodeName})")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    persistCurrentPosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    persistCurrentPosition(forceEnd = true)
                }
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                persistCurrentPosition()
                exoPlayer.pause()
            }
        }
        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            persistCurrentPosition()
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodically save streaming progress every 5 seconds during playback
    LaunchedEffect(exoPlayer, mediaKey) {
        while (true) {
            delay(5_000L)
            if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.isPlaying) {
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                currentOnSaveProgress(currentMediaKey, pos, dur)
            }
        }
    }

    // Auto-hide the seek feedback overlay after 800ms
    LaunchedEffect(seekFeedbackTrigger) {
        if (seekFeedbackTrigger > 0) {
            delay(800L)
            seekFeedbackDirection = 0
            seekFeedbackSeconds = 0
        }
    }

    // Auto-hide the "Ripreso da ..." badge after 3 seconds
    LaunchedEffect(showResumedBadge) {
        if (showResumedBadge) {
            delay(3_000L)
            showResumedBadge = false
        }
    }

    LaunchedEffect(pauseRequestCount) {
        if (pauseRequestCount > 0) {
            persistCurrentPosition()
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

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    controllerShowTimeoutMs = 5000
                    controllerHideOnTouch = true
                    controllerAutoShow = true
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

                    var lastDoubleTapSeekMs = 0L
                    var lastDoubleTapSide = 0

                    fun performSeekByTap(direction: Int) {
                        val activePlayer = this@apply.player ?: return
                        val currentPos = activePlayer.currentPosition
                        val dur = activePlayer.duration
                        val targetPos = if (direction < 0) {
                            (currentPos - 10_000L).coerceAtLeast(0L)
                        } else {
                            val rawTarget = currentPos + 10_000L
                            if (dur != C.TIME_UNSET && dur > 0L) rawTarget.coerceAtMost(dur) else rawTarget
                        }
                        activePlayer.seekTo(targetPos)
                        if (seekFeedbackDirection == direction) {
                            seekFeedbackSeconds += 10
                        } else {
                            seekFeedbackDirection = direction
                            seekFeedbackSeconds = 10
                        }
                        seekFeedbackTrigger++
                        lastDoubleTapSeekMs = System.currentTimeMillis()
                        lastDoubleTapSide = direction
                    }

                    val gestureDetector = GestureDetector(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent): Boolean = true

                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                if (System.currentTimeMillis() - lastDoubleTapSeekMs < 700L) {
                                    return true
                                }
                                if (isControllerFullyVisible) {
                                    hideController()
                                } else {
                                    showController()
                                }
                                return true
                            }

                            override fun onSingleTapUp(e: MotionEvent): Boolean {
                                val now = System.currentTimeMillis()
                                val side = if (e.x < width / 2f) -1 else 1
                                if (now - lastDoubleTapSeekMs < 700L && side == lastDoubleTapSide) {
                                    performSeekByTap(side)
                                    return true
                                }
                                return super.onSingleTapUp(e)
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                val side = if (e.x < width / 2f) -1 else 1
                                performSeekByTap(side)
                                return true
                            }
                        }
                    )

                    setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)
                        true
                    }
                }
            },
            update = { playerView ->
                if (playerView.player !== exoPlayer) {
                    playerView.player = exoPlayer
                }
            }
        )

        // YouTube-style double-tap seek feedback indicator (left/right)
        AnimatedVisibility(
            visible = seekFeedbackDirection != 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (seekFeedbackDirection < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 48.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = CircleShape
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (seekFeedbackDirection < 0) Icons.Default.FastRewind else Icons.Default.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (seekFeedbackDirection < 0) "-${seekFeedbackSeconds}s" else "+${seekFeedbackSeconds}s",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Subtle "Ripreso da MM:SS" indicator when resuming from saved position
        AnimatedVisibility(
            visible = showResumedBadge && initialPositionMs > 0L,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Ripreso da ${formatDurationMs(initialPositionMs)}",
                    color = AccentCyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
