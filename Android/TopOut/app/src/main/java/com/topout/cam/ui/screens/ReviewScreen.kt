package com.topout.cam.ui.screens

import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.topout.cam.R
import com.topout.cam.model.AppSettings
import com.topout.cam.model.GalleryClipSelection
import com.topout.cam.model.RecordedVideo
import com.topout.cam.model.SavedGalleryVideo
import com.topout.cam.model.TopOutConfig
import com.topout.cam.localization.tr
import com.topout.cam.ui.components.PrecisionTrimSlider
import com.topout.cam.ui.components.TopStatusBanner
import com.topout.cam.ui.theme.TopOutColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class TrimEditEdge { START, END }
private val closeWarningMessage: String get() = tr("review.close_warning")

private data class DiscardConfirmation(
    val message: String,
    val confirmedAction: () -> Unit
)

/** 결과 화면의 5초 전/후 이동 간격. 선택 구간 안에서만 움직인다. */
private const val REVIEW_SEEK_STEP_SECONDS = 5.0

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ReviewScreen(
    video: RecordedVideo,
    settings: AppSettings,
    saving: Boolean,
    savingStatus: String?,
    savingStatusSelection: GalleryClipSelection?,
    savedGalleryVideos: List<SavedGalleryVideo>,
    topHoldAnalyzing: Boolean,
    topHoldStatus: String?,
    onSave: (Double, Double, Boolean) -> Unit,
    onSelectTopHold: (positionMs: Long, tap: Offset, containerSize: Size) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var pendingTrimEditEdge by remember(video.uri) { mutableStateOf<TrimEditEdge?>(null) }
    var pendingSeekPositionMs by remember(video.uri) {
        mutableLongStateOf((video.defaultStartSeconds * 1000.0).roundToLong())
    }
    // 탑홀드 마스크 저장은 기본적으로 끈다.
    var includeOverlay by remember(video.uri) { mutableStateOf(false) }
    var topHoldTipShown by remember(video.uri) { mutableStateOf(false) }
    var topHoldTipVisible by remember(video.uri) { mutableStateOf(false) }
    // 탑홀드 마스크가 체크된 상태면 두 손가락으로 확대한 뒤 다시 탭할 수 있다고 알려준다.
    var topHoldZoomTipVisible by remember(video.uri) { mutableStateOf(false) }
    // 결과 화면에서 탑홀드를 고르면 새 마스크를 바로 켠다.
    LaunchedEffect(video.overlayBitmap) {
        if (video.overlayBitmap != null) {
            includeOverlay = true
            topHoldZoomTipVisible = settings.helpEnabled
        }
    }
    var selectedRangeIndex by remember(video.uri) {
        mutableIntStateOf(video.detectedRanges.lastIndex.coerceAtLeast(0))
    }
    var stopNotice by remember(video.uri) { mutableStateOf(video.automaticStopNotice) }
    var discardConfirmation by remember { mutableStateOf<DiscardConfirmation?>(null) }
    var playbackErrorMessage by remember(video.uri) { mutableStateOf<String?>(null) }
    var showingSavedGalleryVideo by remember(video.uri) { mutableStateOf(false) }

    fun requestDiscardConfirmation(
        message: String,
        confirmedAction: () -> Unit
    ) {
        if (settings.helpEnabled) {
            discardConfirmation = DiscardConfirmation(message, confirmedAction)
        } else {
            confirmedAction()
        }
    }

    BackHandler {
        // 저장 중에는 뒤로가기를 막아 불완전한 결과 파일을 방지한다.
        if (!saving) {
            requestDiscardConfirmation(
                closeWarningMessage,
                confirmedAction = onClose
            )
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                player.pause()
                isPlaying = false
                if (settings.helpEnabled) {
                    playbackErrorMessage = tr("error.play_video")
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

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, selectedStart, selectedEnd, isPlaying, isTrimTracking) {
        if (!isPlaying || isTrimTracking) return@LaunchedEffect

        while (isActive) {
            val position = player.currentPosition / 1000.0
            if (position >= selectedEnd - 0.02) {
                playhead = selectedEnd
                player.pause()
                isPlaying = false
                player.seekTo((selectedEnd * 1000).toLong())
                break
            } else {
                playhead = position.coerceIn(selectedStart, selectedEnd)
            }
            delay(50.milliseconds)
        }
    }

    LaunchedEffect(player, isTrimTracking) {
        if (!isTrimTracking) return@LaunchedEffect

        // 화면 주기마다 한 번만 이동해 빠른 드래그에서 탐색 요청이 쌓이지 않게 한다.
        var lastIssuedPositionMs = Long.MIN_VALUE
        while (isActive) {
            val requestedPositionMs = pendingSeekPositionMs
            if (requestedPositionMs != lastIssuedPositionMs) {
                player.seekTo(requestedPositionMs)
                lastIssuedPositionMs = requestedPositionMs
            }
            delay(16.milliseconds)
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

    val currentSelection = GalleryClipSelection(selectedStart, selectedEnd, includeOverlay)
    val currentSavedGalleryVideo = savedGalleryVideos.lastOrNull {
        it.selection.matches(currentSelection)
    }
    val savedMatchesCurrentSelection = currentSavedGalleryVideo != null

    // 탑홀드 마스킹을 켠 녹화는 두 손가락으로 영상을 확대·이동한 뒤 탑홀드를 탭할 수 있다. iOS ReviewVC+VideoZoom과 같다.
    var videoZoomScale by remember(video.uri) { mutableFloatStateOf(1f) }
    var videoZoomOffset by remember(video.uri) { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        // 영상과 탑홀드 마스크를 같은 층에 두어 함께 확대한다.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = videoZoomScale
                    scaleY = videoZoomScale
                    translationX = videoZoomOffset.x
                    translationY = videoZoomOffset.y
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    val view = if (video.topHoldSelectionEnabled) {
                        // 확대가 화면에 반영되도록 TextureView를 쓰는 PlayerView를 쓴다.
                        LayoutInflater.from(viewContext)
                            .inflate(R.layout.review_zoomable_player_view, null, false) as PlayerView
                    } else {
                        PlayerView(viewContext).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    }
                    view.apply { this.player = player }
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
        }

        if (video.topHoldSelectionEnabled) {
            // 영상을 멈추고 현재 프레임에서 탭한 탑홀드로 마스크를 만든다. 조작 버튼은 이 위에 그려진다.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(video.uri) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val oldScale = videoZoomScale
                            val newScale = (oldScale * zoom).coerceIn(1f, ReviewMaxVideoZoomScale)
                            // 손가락 아래의 영상 지점이 그대로 머물도록 이동량을 맞춘다.
                            val relative = centroid - center
                            val anchored = relative - (relative - videoZoomOffset) * (newScale / oldScale)
                            videoZoomScale = newScale
                            videoZoomOffset = clampVideoZoomOffset(
                                anchored + pan, newScale, size.width, size.height, video
                            )
                        }
                    }
                    .pointerInput(video.uri, saving, topHoldAnalyzing) {
                        detectTapGestures { offset ->
                            if (saving || topHoldAnalyzing) return@detectTapGestures
                            // 확대를 되돌린 원래 화면 좌표로 바꿔 영상 위치를 구한다.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val contentPoint = center + (offset - center - videoZoomOffset) / videoZoomScale
                            // 버튼 사이 빈 곳처럼 영상 밖을 누른 경우는 무시한다.
                            if (!isInsideFittedVideo(contentPoint, size.width, size.height, video)) return@detectTapGestures
                            player.pause()
                            isPlaying = false
                            topHoldTipVisible = false
                            onSelectTopHold(
                                player.currentPosition,
                                contentPoint,
                                Size(size.width.toFloat(), size.height.toFloat())
                            )
                        }
                    }
            )
        }

        val currentSavingStatus = savingStatus?.takeIf {
            savingStatusSelection?.matches(currentSelection) == true
        }
        val topHoldTip = when {
            topHoldZoomTipVisible && includeOverlay && video.overlayBitmap != null -> tr("review.top_hold_zoom_tip")
            topHoldTipVisible -> tr("review.top_hold_tap_tip")
            else -> null
        }
        val reviewStatusText = playbackErrorMessage ?: currentSavingStatus ?: topHoldStatus ?: topHoldTip
            ?: if (video.detectedRanges.isEmpty()) {
                tr("review.manual_prompt")
            } else {
                tr("review.auto_prompt")
            }
        if (settings.helpEnabled || currentSavingStatus != null || topHoldStatus != null) {
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
                .navigationBarsPadding()
                .padding(
                    start = if (isLandscape) 10.dp else 18.dp,
                    top = if (isLandscape) 8.dp else 14.dp,
                    end = if (isLandscape) 10.dp else 18.dp,
                    bottom = if (isLandscape) 11.dp else 17.dp
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
                        text = tr("review.detected_range", selectedRangeIndex + 1, video.detectedRanges.size),
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
                    pendingTrimEditEdge = if (lowerChanged) TrimEditEdge.START else TrimEditEdge.END
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    }
                    seek(if (lowerChanged) lower else upper)
                },
                onPlayheadChanged = { value ->
                    pendingTrimEditEdge = null
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    }
                    seek(value)
                },
                onTrackingChanged = { tracking ->
                    if (tracking) {
                        pendingTrimEditEdge = null
                        if (isPlaying) {
                            player.pause()
                            isPlaying = false
                        }
                        pendingSeekPositionMs = (playhead * 1000.0).roundToLong()
                        player.isScrubbingModeEnabled = true
                        isTrimTracking = true
                    } else {
                        isTrimTracking = false
                        player.isScrubbingModeEnabled = false
                        player.setSeekParameters(SeekParameters.EXACT)
                        val editedEdge = pendingTrimEditEdge
                        pendingTrimEditEdge = null
                        if (editedEdge == null) {
                            // 요청한 프레임에서 재생 위치와 확대 조작을 끝낸다.
                            player.seekTo(pendingSeekPositionMs)
                        } else {
                            val resumeFrom = when (editedEdge) {
                                TrimEditEdge.START -> selectedStart
                                TrimEditEdge.END -> (
                                    selectedEnd - TopOutConfig.ReviewEndPreviewLeadSeconds
                                ).coerceAtLeast(selectedStart)
                            }
                            seek(resumeFrom)
                            player.play()
                            isPlaying = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(if (isLandscape) 104.dp else 116.dp)
            )

            val togglePlayback = {
                isPlaying = !isPlaying
                if (isPlaying) {
                    topHoldTipVisible = false
                    if (playhead >= selectedEnd - 0.05) seek(selectedStart)
                    player.play()
                } else {
                    player.pause()
                    // 처음 멈췄을 때 탑홀드를 탭해 마스크를 지정한다고 한 번 알려준다.
                    if (video.topHoldSelectionEnabled && settings.helpEnabled && !topHoldTipShown) {
                        topHoldTipShown = true
                        topHoldTipVisible = true
                    }
                }
            }
            val requestClose = {
                requestDiscardConfirmation(
                    closeWarningMessage,
                    confirmedAction = onClose
                )
            }

            ReviewActionControls(
                isLandscape = isLandscape,
                isPlaying = isPlaying,
                saving = saving,
                topHoldAnalyzing = topHoldAnalyzing,
                includeOverlay = includeOverlay,
                overlayAvailable = video.topHoldSelectionEnabled,
                overlaySelected = video.overlayBitmap != null,
                savedMatchesCurrentSelection = savedMatchesCurrentSelection,
                onTogglePlayback = togglePlayback,
                onToggleOverlay = {
                    if (video.overlayBitmap == null) {
                        // 아직 마스크가 없으면 영상을 멈추고 탑홀드를 탭하라고 안내한다.
                        player.pause()
                        isPlaying = false
                        topHoldTipShown = true
                        topHoldTipVisible = true
                    } else {
                        includeOverlay = !includeOverlay
                        // 체크하면 확대 안내를 보여주고, 끄면 기본 안내로 돌아간다.
                        topHoldZoomTipVisible = includeOverlay && settings.helpEnabled
                    }
                },
                onSaveOrView = {
                    player.pause()
                    isPlaying = false
                    if (savedMatchesCurrentSelection) {
                        showingSavedGalleryVideo = true
                    } else {
                        onSave(selectedStart, selectedEnd, includeOverlay)
                    }
                },
                // 재생 중이면 이동한 위치부터 계속 재생하고, 구간 끝에서는 기존 루프가 멈춘다.
                onSeekBackward = { seek(playhead - REVIEW_SEEK_STEP_SECONDS) },
                onSeekForward = { seek(playhead + REVIEW_SEEK_STEP_SECONDS) },
                onClose = requestClose
            )
        }
    }

    if (showingSavedGalleryVideo && currentSavedGalleryVideo != null) {
        SavedGalleryVideoDialog(
            uri = currentSavedGalleryVideo.uri,
            onDismiss = { showingSavedGalleryVideo = false }
        )
    }

    stopNotice?.let { notice ->
        PersistentNoticeDialog(
            title = notice.title,
            message = notice.message,
            onConfirm = { stopNotice = null }
        )
    }

    discardConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { discardConfirmation = null },
            title = null,
            text = {
                Text(
                    text = confirmation.message,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            dismissButton = {
                Button(
                    onClick = { discardConfirmation = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TopOutColors.GrayButton,
                        contentColor = Color.White
                    )
                ) { Text(tr("common.cancel")) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        discardConfirmation = null
                        confirmation.confirmedAction()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TopOutColors.Red)
                ) { Text(tr("common.ok")) }
            }
        )
    }
}

private const val ReviewMaxVideoZoomScale = 5f

/** 확대된 영상이 원래 영상 영역을 항상 덮도록 이동 범위를 제한한다. 영상은 화면 가운데에 맞춰 그려진다. */
private fun clampVideoZoomOffset(
    offset: Offset,
    scale: Float,
    containerWidth: Int,
    containerHeight: Int,
    video: RecordedVideo
): Offset {
    if (scale <= 1.001f || containerWidth <= 0 || containerHeight <= 0) return Offset.Zero
    val (videoWidth, videoHeight) = if (video.overlayWidth > 0 && video.overlayHeight > 0) {
        val fit = minOf(containerWidth.toFloat() / video.overlayWidth, containerHeight.toFloat() / video.overlayHeight)
        video.overlayWidth * fit to video.overlayHeight * fit
    } else {
        containerWidth.toFloat() to containerHeight.toFloat()
    }
    val slackX = (scale - 1f) * videoWidth / 2f
    val slackY = (scale - 1f) * videoHeight / 2f
    return Offset(offset.x.coerceIn(-slackX, slackX), offset.y.coerceIn(-slackY, slackY))
}

/** 화면에 비율을 유지해 맞춘 영상 영역 안의 탭인지. 표시 크기를 모르면 화면 전체를 영상으로 본다. */
private fun isInsideFittedVideo(tap: Offset, containerWidth: Int, containerHeight: Int, video: RecordedVideo): Boolean {
    if (video.overlayWidth <= 0 || video.overlayHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) return true
    val scale = minOf(containerWidth.toFloat() / video.overlayWidth, containerHeight.toFloat() / video.overlayHeight)
    val width = video.overlayWidth * scale
    val height = video.overlayHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return tap.x in left..(left + width) && tap.y in top..(top + height)
}
