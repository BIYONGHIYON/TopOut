package com.topout.cam.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.topout.cam.localization.tr
import com.topout.cam.ui.components.TopOutCapsuleButton
import com.topout.cam.ui.components.TopStatusBanner
import com.topout.cam.ui.theme.TopOutColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds

/**
 * 1행: 5초 전 · 재생/정지 · 5초 후 · 선택 구간 저장
 * 2행: 탑홀드 마스크 · 닫기 (탑홀드 마스킹을 끈 녹화는 닫기가 전체 폭)
 * 가로 화면은 같은 순서를 한 줄에 둔다.
 */
@Composable
internal fun ReviewActionControls(
    isLandscape: Boolean,
    isPlaying: Boolean,
    saving: Boolean,
    /** 결과 화면 탑홀드 보정 중에는 마스크와 저장만 잠근다. */
    topHoldAnalyzing: Boolean,
    includeOverlay: Boolean,
    /** 탑홀드 마스킹을 켠 녹화면 마스크 버튼을 보인다. */
    overlayAvailable: Boolean,
    /** 결과 화면에서 탑홀드를 이미 골랐는지. 고르기 전에는 누르면 탭 안내를 보여준다. */
    overlaySelected: Boolean,
    savedMatchesCurrentSelection: Boolean,
    onTogglePlayback: () -> Unit,
    onToggleOverlay: () -> Unit,
    onSaveOrView: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onClose: () -> Unit
) {
    val spacing = if (isLandscape) 6.dp else 8.dp
    val iconWidth = if (isLandscape) 44.dp else 50.dp
    val playWidth = if (isLandscape) 48.dp else 58.dp
    val buttonHeight = if (isLandscape) 40.dp else 42.dp

    @Composable
    fun OverlayButton(modifier: Modifier) = TopOutCapsuleButton(
        text = tr("review.top_hold_mask"),
        onClick = onToggleOverlay,
        icon = when {
            !overlaySelected -> Icons.Rounded.TouchApp
            includeOverlay -> Icons.Rounded.CheckBox
            else -> Icons.Rounded.CheckBoxOutlineBlank
        },
        enabled = !saving && !topHoldAnalyzing,
        primary = includeOverlay,
        compact = true,
        modifier = modifier
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReviewIconButton(
            icon = Icons.Rounded.Replay5,
            contentDescription = tr("review.seek_backward_5s"),
            enabled = !saving,
            onClick = onSeekBackward,
            modifier = Modifier.size(width = iconWidth, height = buttonHeight)
        )
        PlaybackButton(
            isPlaying = isPlaying,
            enabled = !saving,
            onClick = onTogglePlayback,
            modifier = Modifier.size(width = playWidth, height = buttonHeight)
        )
        ReviewIconButton(
            icon = Icons.Rounded.Forward5,
            contentDescription = tr("review.seek_forward_5s"),
            enabled = !saving,
            onClick = onSeekForward,
            modifier = Modifier.size(width = iconWidth, height = buttonHeight)
        )
        if (isLandscape && overlayAvailable) OverlayButton(Modifier.weight(1f))
        TopOutCapsuleButton(
            text = when {
                saving -> tr("review.saving")
                savedMatchesCurrentSelection -> tr("review.view_saved")
                else -> tr("review.save_clip")
            },
            onClick = onSaveOrView,
            enabled = !saving && !topHoldAnalyzing,
            primary = true,
            compact = true,
            modifier = Modifier.weight(1f)
        )
        if (isLandscape) {
            TopOutCapsuleButton(tr("common.close"), onClose, enabled = !saving, compact = true, modifier = Modifier.weight(0.62f))
        }
    }
    if (!isLandscape) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (overlayAvailable) OverlayButton(Modifier.weight(1f))
            TopOutCapsuleButton(tr("common.close"), onClose, enabled = !saving, compact = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ReviewIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TopOutColors.GrayButton),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Icon(icon, contentDescription, tint = Color.White)
    }
}

@Composable
internal fun PlaybackButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TopOutColors.GrayButton),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            tr("review.play_pause"),
            tint = Color.White
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SavedGalleryVideoDialog(
    uri: android.net.Uri,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(uri) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(5_000L)
            .setSeekForwardIncrementMs(5_000L)
            .build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    var playbackError by remember(uri) { mutableStateOf<String?>(null) }
    var isGalleryScrubbing by remember(uri) { mutableStateOf(false) }
    var pendingGallerySeekPositionMs by remember(uri) { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = tr("error.gallery_play")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, isGalleryScrubbing) {
        if (!isGalleryScrubbing) return@LaunchedEffect

        // 빠르게 탐색해도 프레임 요청이 쌓이지 않도록 화면 주기마다 한 번만 이동한다.
        var lastIssuedPositionMs = Long.MIN_VALUE
        while (isActive) {
            val requestedPositionMs = pendingGallerySeekPositionMs
            if (requestedPositionMs != lastIssuedPositionMs) {
                player.seekTo(requestedPositionMs)
                lastIssuedPositionMs = requestedPositionMs
            }
            delay(16.milliseconds)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val windowInfo = LocalWindowInfo.current
        val density = LocalDensity.current
        val shortViewport = with(density) {
            windowInfo.containerSize.height.toDp() < 520.dp
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 짧고 넓은 화면에서도 탐색 막대가 가려지지 않도록 버튼을 영상 밖에 둔다.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                            setShutterBackgroundColor(android.graphics.Color.BLACK)

                            // 영상이 가려지지 않는 범위에서 기본 컨트롤 배경을 낮춘다.
                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controls_background)
                                ?.setBackgroundColor(android.graphics.Color.argb(46, 0, 0, 0))
                            findViewById<android.view.View>(androidx.media3.ui.R.id.exo_bottom_bar)
                                ?.setBackgroundColor(android.graphics.Color.argb(82, 0, 0, 0))

                            (findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? TimeBar)
                                ?.addListener(object : TimeBar.OnScrubListener {
                                    override fun onScrubStart(timeBar: TimeBar, position: Long) {
                                        player.pause()
                                        player.isScrubbingModeEnabled = true
                                        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                        pendingGallerySeekPositionMs = position
                                        isGalleryScrubbing = true
                                    }

                                    override fun onScrubMove(timeBar: TimeBar, position: Long) {
                                        pendingGallerySeekPositionMs = position
                                    }

                                    override fun onScrubStop(
                                        timeBar: TimeBar,
                                        position: Long,
                                        canceled: Boolean
                                    ) {
                                        isGalleryScrubbing = false
                                        player.isScrubbingModeEnabled = false
                                        player.setSeekParameters(SeekParameters.EXACT)
                                        if (!canceled) player.seekTo(position)
                                    }
                                })
                        }
                    },
                    update = { it.player = player }
                )

                TopStatusBanner(
                    text = playbackError ?: tr("review.saved_gallery_video"),
                    isLandscape = isLandscape,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .navigationBarsPadding()
                    .padding(
                        horizontal = if (isLandscape) 16.dp else 24.dp,
                        vertical = if (shortViewport) 6.dp else 10.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                TopOutCapsuleButton(
                    text = tr("review.back_to_editor"),
                    onClick = onDismiss,
                    primary = true,
                    compact = true,
                    modifier = Modifier
                        .widthIn(max = if (isLandscape) 360.dp else 420.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun RangeArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    TopOutColors.BubbleBackground.copy(alpha = if (enabled) 1f else 0.38f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
internal fun PersistentNoticeDialog(title: String, message: String, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(tr("common.ok")) }
        }
    )
}
