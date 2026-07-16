package com.topout.cam.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToLong
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.topout.cam.model.AppSettings
import com.topout.cam.model.RecordedVideo
import com.topout.cam.ui.components.PrecisionTrimSlider
import com.topout.cam.ui.components.TopOutCapsuleButton
import com.topout.cam.ui.components.TopStatusBanner
import com.topout.cam.ui.theme.TopOutColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ReviewScreen(
    video: RecordedVideo,
    settings: AppSettings,
    saving: Boolean,
    savingStatus: String?,
    onSave: (Double, Double, Boolean) -> Unit,
    onRetake: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val player = remember(video.uri) {
        ExoPlayer.Builder(context)
            .setSeekParameters(SeekParameters.EXACT)
            .build().apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    var selectedStart by remember(video.uri) { mutableDoubleStateOf(video.defaultStartSeconds) }
    var selectedEnd by remember(video.uri) { mutableDoubleStateOf(video.defaultEndSeconds) }
    var playhead by remember(video.uri) { mutableDoubleStateOf(video.defaultStartSeconds) }
    var isPlaying by remember { mutableStateOf(true) }
    var isTrimTracking by remember(video.uri) { mutableStateOf(false) }
    var pendingSeekPositionMs by remember(video.uri) {
        mutableLongStateOf((video.defaultStartSeconds * 1000.0).roundToLong())
    }
    // ReviewVC is created with includeTopHoldOverlayByDefault = false.
    var includeOverlay by remember(video.uri) { mutableStateOf(false) }
    var selectedRangeIndex by remember(video.uri) {
        mutableIntStateOf(video.detectedRanges.lastIndex.coerceAtLeast(0))
    }
    var stopNotice by remember(video.uri) { mutableStateOf(video.automaticStopNotice) }
    var pendingDiscardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var playbackErrorMessage by remember(video.uri) { mutableStateOf<String?>(null) }

    BackHandler(enabled = !saving) {
        if (settings.helpEnabled) pendingDiscardAction = onClose else onClose()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                player.pause()
                isPlaying = false
                if (settings.helpEnabled) {
                    playbackErrorMessage = "영상을 재생할 수 없습니다. 다시 촬영해주세요"
                }
            }
        }
        player.addListener(listener)
        player.seekTo((selectedStart * 1000).toLong())
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, selectedStart, selectedEnd, isPlaying, isTrimTracking) {
        while (true) {
            val position = player.currentPosition / 1000.0
            if (isPlaying && !isTrimTracking) {
                if (position >= selectedEnd - 0.02) {
                    playhead = selectedEnd
                    player.pause()
                    isPlaying = false
                    player.seekTo((selectedEnd * 1000).toLong())
                } else {
                    playhead = position.coerceIn(selectedStart, selectedEnd)
                }
            }
            delay(50)
        }
    }

    LaunchedEffect(player, isTrimTracking) {
        if (!isTrimTracking) return@LaunchedEffect

        // Coalesce pointer events to at most one seek per display frame. This prevents
        // stale seek requests from building up behind the user's finger. Media3's
        // scrubbing mode keeps the decoder warm for these frequent paused seeks.
        var lastIssuedPositionMs = Long.MIN_VALUE
        while (isActive) {
            val requestedPositionMs = pendingSeekPositionMs
            if (requestedPositionMs != lastIssuedPositionMs) {
                player.seekTo(requestedPositionMs)
                lastIssuedPositionMs = requestedPositionMs
            }
            delay(16)
        }
    }

    fun seek(seconds: Double) {
        val safe = seconds.coerceIn(selectedStart, selectedEnd)
        val positionMs = (safe * 1000.0).roundToLong()
        playhead = safe
        pendingSeekPositionMs = positionMs
        if (!isTrimTracking) {
            player.seekTo(positionMs)
        }
    }

    fun applyDetectedRange(index: Int) {
        val range = video.detectedRanges.getOrNull(index) ?: return
        player.pause()
        isPlaying = false
        selectedRangeIndex = index
        selectedStart = range.startSeconds
        selectedEnd = range.endSeconds
        seek(selectedStart)
        player.play()
        isPlaying = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player }
        )

        if (includeOverlay) {
            video.overlayBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        val reviewStatusText = playbackErrorMessage ?: savingStatus ?: if (video.detectedRanges.isEmpty()) {
            "저장할 구간을 직접 선택해주세요"
        } else {
            "자동 감지된 시작~완등 구간을 확인해주세요"
        }
        if (settings.helpEnabled || savingStatus != null) {
            TopStatusBanner(
                text = reviewStatusText,
                isLandscape = isLandscape,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(if (isLandscape) 0.94f else 1f)
                .widthIn(max = if (isLandscape) 900.dp else 680.dp)
                .padding(
                    horizontal = if (isLandscape) 10.dp else 18.dp,
                    vertical = if (isLandscape) 8.dp else 14.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 5.dp else 8.dp)
        ) {
            if (video.detectedRanges.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (video.detectedRanges.size > 1) {
                        RangeArrowButton(
                            icon = Icons.Rounded.ChevronLeft,
                            onClick = { applyDetectedRange((selectedRangeIndex - 1).coerceAtLeast(0)) },
                            enabled = !saving && selectedRangeIndex > 0
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        text = "감지 구간 ${selectedRangeIndex + 1}/${video.detectedRanges.size}",
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(13.dp)).padding(horizontal = 14.dp, vertical = 5.dp),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (video.detectedRanges.size > 1) {
                        Spacer(Modifier.size(6.dp))
                        RangeArrowButton(
                            icon = Icons.Rounded.ChevronRight,
                            onClick = { applyDetectedRange((selectedRangeIndex + 1).coerceAtMost(video.detectedRanges.lastIndex)) },
                            enabled = !saving && selectedRangeIndex < video.detectedRanges.lastIndex
                        )
                    }
                }
            }

            PrecisionTrimSlider(
                durationSeconds = video.durationSeconds,
                lowerValue = selectedStart,
                upperValue = selectedEnd,
                playheadValue = playhead,
                enabled = !saving,
                onRangeChanged = { lower, upper, lowerChanged ->
                    selectedStart = lower
                    selectedEnd = upper
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    }
                    seek(if (lowerChanged) lower else upper)
                },
                onPlayheadChanged = { value ->
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    }
                    seek(value)
                },
                onTrackingChanged = { tracking ->
                    if (tracking) {
                        if (isPlaying) {
                            player.pause()
                            isPlaying = false
                        }
                        pendingSeekPositionMs = (playhead * 1000.0).roundToLong()
                        player.setScrubbingModeEnabled(true)
                        isTrimTracking = true
                    } else {
                        isTrimTracking = false
                        player.setScrubbingModeEnabled(false)
                        player.setSeekParameters(SeekParameters.EXACT)
                        // Finish on the exact requested frame after the fast scrub preview.
                        player.seekTo(pendingSeekPositionMs)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(if (isLandscape) 104.dp else 116.dp)
            )

            val togglePlayback = {
                isPlaying = !isPlaying
                if (isPlaying) {
                    if (playhead >= selectedEnd - 0.05) seek(selectedStart)
                    player.play()
                } else player.pause()
            }
            val requestRetake = {
                if (settings.helpEnabled) pendingDiscardAction = onRetake else onRetake()
            }
            val requestClose = {
                if (settings.helpEnabled) pendingDiscardAction = onClose else onClose()
            }

            if (isLandscape) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaybackButton(isPlaying, !saving, togglePlayback, Modifier.size(width = 48.dp, height = 40.dp))
                    TopOutCapsuleButton(
                        text = "탑홀드 마스크",
                        onClick = { includeOverlay = !includeOverlay },
                        icon = if (includeOverlay) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        enabled = !saving && video.overlayBitmap != null,
                        primary = includeOverlay,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                    TopOutCapsuleButton(
                        text = if (saving) "저장 중..." else "선택 구간 저장",
                        onClick = { onSave(selectedStart, selectedEnd, includeOverlay) },
                        enabled = !saving,
                        primary = true,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                    TopOutCapsuleButton("다시 촬영", requestRetake, enabled = !saving, compact = true, modifier = Modifier.weight(0.78f))
                    TopOutCapsuleButton("닫기", requestClose, enabled = !saving, compact = true, modifier = Modifier.weight(0.62f))
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaybackButton(isPlaying, !saving, togglePlayback, Modifier.size(width = 58.dp, height = 42.dp))
                    TopOutCapsuleButton(
                        text = "탑홀드 마스크",
                        onClick = { includeOverlay = !includeOverlay },
                        icon = if (includeOverlay) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        enabled = !saving && video.overlayBitmap != null,
                        primary = includeOverlay,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                    TopOutCapsuleButton(
                        text = if (saving) "저장 중..." else "선택 구간 저장",
                        onClick = { onSave(selectedStart, selectedEnd, includeOverlay) },
                        enabled = !saving,
                        primary = true,
                        compact = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopOutCapsuleButton("다시 촬영", requestRetake, enabled = !saving, compact = true, modifier = Modifier.weight(1f))
                    TopOutCapsuleButton("닫기", requestClose, enabled = !saving, compact = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    stopNotice?.let { notice ->
        PersistentNoticeDialog(
            title = notice.title,
            message = notice.message,
            onConfirm = { stopNotice = null }
        )
    }

    pendingDiscardAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDiscardAction = null },
            title = null,
            text = { Text("저장하지 않은 영상이 삭제됩니다") },
            dismissButton = {
                Button(
                    onClick = { pendingDiscardAction = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TopOutColors.GrayButton,
                        contentColor = Color.White
                    )
                ) { Text("취소") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDiscardAction = null
                        action()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TopOutColors.Red)
                ) { Text("확인") }
            }
        )
    }
}

@Composable
private fun PlaybackButton(
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
            "재생 또는 일시정지",
            tint = Color.White
        )
    }
}

@Composable
private fun RangeArrowButton(
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
private fun PersistentNoticeDialog(title: String, message: String, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) { Text("확인") }
        }
    )
}
