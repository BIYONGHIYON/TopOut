package com.topout.cam.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.topout.cam.R
import com.topout.cam.TopOutViewModel
import com.topout.cam.localization.AppLanguageManager
import com.topout.cam.localization.TopOutStrings
import com.topout.cam.localization.tr
import com.topout.cam.model.CameraStage
import com.topout.cam.model.HoldMask
import com.topout.cam.model.RecordingState
import com.topout.cam.model.TopOutCopy
import com.topout.cam.model.TopOutConfig
import com.topout.cam.ui.components.AspectFrame
import com.topout.cam.ui.components.CameraPreview
import com.topout.cam.ui.components.SelectionEditor
import com.topout.cam.ui.components.TopOutCapsuleButton
import com.topout.cam.ui.components.TopStatusBanner
import com.topout.cam.ui.theme.TopOutColors

@Composable
fun TopOutApp(viewModel: TopOutViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val lockCaptureOrientation = state.isBusy ||
        state.stage == CameraStage.SELECT || state.stage == CameraStage.RECORDING
    // On Android 16 large screens the system may ignore requestedOrientation. Keep the camera
    // session's capture orientation frozen independently so a window rotation never rebinds or
    // changes the recording crop while selecting/recording.
    val cameraIsLandscape = if (lockCaptureOrientation) state.isLandscape else isLandscape

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.onAppBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lockCaptureOrientation, activity) {
        activity?.requestedOrientation = if (lockCaptureOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    LaunchedEffect(activity, state.stage) {
        // Capture screens stay awake, while Review follows the user's normal auto-lock timeout.
        activity?.window?.decorView?.keepScreenOn = state.stage != CameraStage.REVIEW
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            activity?.window?.decorView?.keepScreenOn = false
        }
    }

    LaunchedEffect(isLandscape) { viewModel.updateOrientation(isLandscape) }
    DisposableEffect(previewView, lockCaptureOrientation) {
        val listener = object : OrientationEventListener(context) {
            private var lastRotation: Int? = null
            override fun onOrientationChanged(orientation: Int) {
                if (lockCaptureOrientation) return
                val rotation = previewView?.display?.rotation ?: return
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    viewModel.updateTargetRotation(rotation)
                }
            }
        }
        if (!lockCaptureOrientation && listener.canDetectOrientation()) listener.enable()
        previewView?.display?.rotation?.takeIf { !lockCaptureOrientation }?.let(viewModel::updateTargetRotation)
        onDispose { listener.disable() }
    }

    BackHandler(
        enabled = state.stage == CameraStage.SELECT || state.stage == CameraStage.RECORDING
    ) {
        when (state.stage) {
            CameraStage.SELECT -> {
                // Keep an in-flight frame capture/MobileSAM request alive. Once selection is idle,
                // Back returns to the preview just like the visible retake control.
                if (!state.isBusy) viewModel.retakeSelection()
            }
            CameraStage.RECORDING -> {
                // Never let the Activity disappear while CameraX is starting or finalizing a file.
                // A running take is finalized through the same safe stop path as the UI button.
                if (state.recordingState in setOf(RecordingState.STARTING, RecordingState.RECORDING)) {
                    viewModel.stopRecording()
                }
            }
            else -> Unit
        }
    }

    if (state.stage == CameraStage.REVIEW && state.recordedVideo != null) {
        ReviewScreen(
            video = state.recordedVideo!!,
            settings = state.settings,
            saving = state.saving,
            savingStatus = state.savingStatus,
            savingStatusSelection = state.savingStatusSelection,
            savedGalleryVideos = state.savedGalleryVideos,
            onSave = viewModel::saveSelectedClip,
            onRetake = viewModel::retakeFromReview,
            onClose = viewModel::closeReview
        )
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!state.launchVisible) {
                val activeRatio = state.settings.aspectRatio.widthOverHeight(cameraIsLandscape)
            AspectFrame(widthOverHeight = activeRatio, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    key(
                        state.settings.aspectRatio,
                        state.settings.resolution,
                        state.settings.frameRate,
                        cameraIsLandscape,
                        state.cameraTargetRotation
                    ) {
                        CameraPreview(
                            lifecycleOwner = lifecycleOwner,
                            modifier = Modifier.fillMaxSize(),
                            onPreviewReady = viewModel::bindCamera,
                            previewViewChanged = { previewView = it },
                            zoomEnabled = state.cameraReady &&
                                state.stage == CameraStage.PREVIEW &&
                                state.recordingState == RecordingState.IDLE,
                            zoomTransitionInProgress = state.lensTransition.inProgress,
                            zoomTransitionAwaitingGestureEnd =
                                state.lensTransition.awaitingGestureEnd,
                            zoomRebaseToken = state.lensTransition.rebaseToken,
                            currentZoom = state.zoomFactor,
                            minimumZoom = state.capabilities.minZoomRatio,
                            maximumZoom = state.capabilities.maxZoomRatio,
                            lensZoomRatios = state.capabilities.lensZoomRatios,
                            // Keep one TextureView-backed preview in every ratio and stage. It
                            // stays inside AspectFrame and reliably survives live-preview toggles.
                            useCompatibleMode = true,
                            onZoomChanged = viewModel::setZoom,
                            onDeferredZoomChanged = viewModel::updateDeferredLensZoom,
                            onZoomGestureEnded = viewModel::finishZoomGesture
                        )
                    }

                    LensTransitionFrameOverlay(
                        transitionId = state.lensTransition.id,
                        frame = state.lensTransition.frame,
                        scaleTarget = state.lensTransition.frameScale,
                        fading = state.lensTransition.fading,
                        onFadeFinished = viewModel::onLensTransitionFadeFinished,
                        modifier = Modifier.fillMaxSize()
                    )


                    if (state.stage == CameraStage.SELECT) {
                        SelectionEditor(
                            matLine = state.matLine,
                            holdMask = state.holdMask,
                            onTapHold = viewModel::onHoldTapped,
                            onMatPointChanged = viewModel::updateMatPoint,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (
                        state.stage == CameraStage.RECORDING &&
                        state.recordingState == RecordingState.RECORDING &&
                        state.livePreviewEnabled &&
                        state.settings.topHoldMaskingEnabled
                    ) {
                        state.holdMask?.let { HoldMaskOverlay(it, Modifier.fillMaxSize()) }
                    }

                    if (
                        state.stage == CameraStage.RECORDING &&
                        state.recordingState == RecordingState.RECORDING &&
                        state.livePreviewEnabled &&
                        state.settings.autoClipDetectionEnabled &&
                        state.matLine.size >= 2
                    ) {
                        RecordingMatLineOverlay(state.matLine, Modifier.fillMaxSize())
                    }
                }
            }

            if (state.stage == CameraStage.RECORDING && !state.livePreviewEnabled) {
                PowerSavingRecordingOverlay(
                    elapsedSeconds = state.recordingElapsedSeconds,
                    recordingStarted = state.recordingState == RecordingState.RECORDING,
                    onTogglePreview = viewModel::toggleLivePreview,
                    onStop = { viewModel.stopRecording() }
                )
            }

            // Exactly one countdown composable is shared by preview-on and preview-off modes.
            if (state.stage == CameraStage.RECORDING && state.recordingCountdown != null) {
                RecordingCountdownOverlay(
                    countdown = state.recordingCountdown,
                    isLandscape = isLandscape
                )
            }

            val showStatus = when (state.stage) {
                CameraStage.PREVIEW, CameraStage.SELECT ->
                    state.settings.helpEnabled && state.statusText.isNotBlank()
                CameraStage.RECORDING -> state.statusText.isNotBlank()
                CameraStage.REVIEW -> false
            }
            if (showStatus) {
                TopStatusBanner(
                    text = state.statusText,
                    isLandscape = isLandscape,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }

            when (state.stage) {
                CameraStage.PREVIEW -> PreviewControls(
                    zoom = state.zoomFactor,
                    showTip = state.settings.helpEnabled,
                    isLandscape = isLandscape,
                    readyTitle = TopOutCopy.readyButtonTitle(state.settings),
                    enabled = state.cameraReady && !state.isBusy &&
                        !state.lensTransition.inProgress,
                    onReady = {
                        viewModel.onReady(
                            frameProvider = { previewView?.bitmap },
                            isLandscape = cameraIsLandscape,
                            targetRotation = previewView?.display?.rotation ?: state.cameraTargetRotation
                        )
                    },
                    onSettings = viewModel::showSettings,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                CameraStage.SELECT -> SelectionControls(
                    canRecord = !state.isBusy && when {
                        state.settings.requiresTopHoldSelection -> state.holdMask != null
                        state.settings.autoClipDetectionEnabled -> state.matLine.size >= 2
                        else -> false
                    },
                    enabled = !state.isBusy,
                    showTip = state.settings.helpEnabled &&
                        TopOutCopy.selectionTip(state.settings).isNotEmpty(),
                    tipText = TopOutCopy.selectionTip(state.settings),
                    isLandscape = isLandscape,
                    onRetake = viewModel::retakeSelection,
                    onRecord = viewModel::startRecording,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                CameraStage.RECORDING -> if (
                    state.livePreviewEnabled && state.recordingState == RecordingState.RECORDING
                ) {
                    RecordingControls(
                        elapsedSeconds = state.recordingElapsedSeconds,
                        isLandscape = isLandscape,
                        onTogglePreview = viewModel::toggleLivePreview,
                        onStop = { viewModel.stopRecording() },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
                else -> Unit
            }

                if (state.isBusy && state.stage in setOf(CameraStage.PREVIEW, CameraStage.SELECT)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(46.dp),
                        color = Color.White
                    )
                }
            }

            if (state.launchVisible) LaunchOverlay()
        }
    }

    if (state.settingsVisible) {
        SettingsDialog(
            initial = state.settings,
            initialLanguage = AppLanguageManager.selectedLanguage(context),
            capabilities = state.capabilities,
            isLandscape = isLandscape,
            onDismiss = viewModel::hideSettings,
            onSave = { settings, language ->
                viewModel.saveSettings(settings)
                AppLanguageManager.select(context, language)
                viewModel.refreshLocalizedCopy()
            }
        )
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("TopOut") },
            text = { Text(message) },
            confirmButton = { Button(onClick = viewModel::clearError) { Text(tr("common.ok")) } }
        )
    }
}


@Composable
private fun LensTransitionFrameOverlay(
    transitionId: Long?,
    frame: Bitmap?,
    scaleTarget: Float,
    fading: Boolean,
    onFadeFinished: (Long, Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transitionId == null || frame == null || frame.isRecycled) return

    val image = remember(transitionId, frame) { frame.asImageBitmap() }
    val alpha = remember(transitionId, frame) { Animatable(1f) }
    val liveScale = scaleTarget.takeIf { it.isFinite() && it >= 1f } ?: 1f
    LaunchedEffect(transitionId, frame, fading) {
        if (!fading) {
            alpha.snapTo(1f)
        } else {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = LENS_TRANSITION_FADE_MILLIS)
            )
            onFadeFinished(transitionId, frame)
        }
    }

    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            // No tween here: the frozen frame follows the latest pinch ratio on every Compose
            // frame instead of perpetually chasing rapidly changing animation targets.
            scaleX = liveScale
            scaleY = liveScale
        }
    )
}


@Composable
private fun RecordingMatLineOverlay(matLine: List<Offset>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (matLine.size < 2) return@Canvas
        val path = Path()
        matLine.forEachIndexed { index, point ->
            val x = point.x.coerceIn(0f, 1f) * size.width
            val y = point.y.coerceIn(0f, 1f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Read-only guide: no endpoint handles and lower opacity so it does not distract from the climb.
        // Use dp-based widths so high-density Android displays match the visual weight of iOS.
        drawPath(
            path,
            Color.Black.copy(alpha = 0.28f),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path,
            Color(0xFF168BFF).copy(alpha = 0.48f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun HoldMaskOverlay(mask: HoldMask, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val contour = mask.normalizedContour.ifEmpty {
            listOf(
                Offset(mask.center.x - mask.radiusX, mask.center.y),
                Offset(mask.center.x, mask.center.y - mask.radiusY),
                Offset(mask.center.x + mask.radiusX, mask.center.y),
                Offset(mask.center.x, mask.center.y + mask.radiusY)
            )
        }
        if (contour.size < 2) return@Canvas

        val path = Path()
        contour.forEachIndexed { index, point ->
            val x = point.x.coerceIn(0f, 1f) * size.width
            val y = point.y.coerceIn(0f, 1f) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        // Match Mask.swift exactly: transparent interior, broad red outline, thin white outline.
        val minimumSide = min(size.width, size.height)
        val redWidth = max(TopOutConfig.MinRedLineWidth, minimumSide * TopOutConfig.RedLineRatio)
        val whiteWidth = max(TopOutConfig.MinWhiteLineWidth, minimumSide * TopOutConfig.WhiteLineRatio)
        drawPath(
            path = path,
            color = Color.Red,
            style = Stroke(width = redWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = whiteWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}


@Composable
private fun PreviewControls(
    zoom: Float,
    showTip: Boolean,
    isLandscape: Boolean,
    readyTitle: String,
    enabled: Boolean,
    onReady: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(
            horizontal = if (isLandscape) 10.dp else 18.dp,
            vertical = if (isLandscape) 6.dp else 10.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showTip && !isLandscape) {
            CameraTipBanner(tr("camera.tip_rotation"))
            Spacer(Modifier.height(6.dp))
        }

        if (isLandscape) {
            Row(
                modifier = Modifier.widthIn(max = 330.dp).height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 58.dp, height = 40.dp)
                        .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        String.format(TopOutStrings.currentLocale(), "%.1fx", zoom),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                TopOutCapsuleButton(
                    text = readyTitle,
                    onClick = onReady,
                    primary = true,
                    enabled = enabled,
                    modifier = Modifier.size(width = 164.dp, height = 46.dp)
                )
                IconButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(999.dp))
                ) {
                    Icon(Icons.Rounded.Settings, tr("common.settings"), tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().widthIn(max = 620.dp).height(56.dp)) {
                Text(
                    String.format(TopOutStrings.currentLocale(), "%.1fx", zoom),
                    modifier = Modifier.align(Alignment.CenterStart),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TopOutCapsuleButton(
                    text = readyTitle,
                    onClick = onReady,
                    primary = true,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.Center).size(width = 180.dp, height = 52.dp)
                )
                IconButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterEnd).size(42.dp)
                ) { Icon(Icons.Rounded.Settings, tr("common.settings"), tint = Color.White) }
            }
        }
    }
}

@Composable
private fun SelectionControls(
    canRecord: Boolean,
    enabled: Boolean,
    showTip: Boolean,
    tipText: String,
    isLandscape: Boolean,
    onRetake: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(
            horizontal = if (isLandscape) 14.dp else 18.dp,
            vertical = if (isLandscape) 7.dp else 10.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showTip) {
            CameraTipBanner(tipText)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth(if (isLandscape) 0.72f else 1f)
                .widthIn(max = 560.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TopOutCapsuleButton(
                tr("camera.retake"),
                onRetake,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            TopOutCapsuleButton(
                tr("camera.start_recording"),
                onRecord,
                primary = true,
                enabled = canRecord,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CameraTipBanner(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .background(TopOutColors.TipBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        color = Color.White,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 7.5.sp,
            maxFontSize = 13.sp,
            stepSize = 0.25.sp
        ),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}

@Composable
private fun RecordingControls(
    elapsedSeconds: Double,
    isLandscape: Boolean,
    onTogglePreview: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth(if (isLandscape) 0.72f else 1f)
            .widthIn(max = 560.dp)
            .navigationBarsPadding()
            .padding(horizontal = if (isLandscape) 12.dp else 20.dp, vertical = if (isLandscape) 8.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        TopOutCapsuleButton(
            tr("camera.hide_live_preview"),
            onTogglePreview,
            modifier = Modifier.weight(1f)
        )
        RecordingStopCapsuleButton(
            elapsed = formatElapsed(elapsedSeconds),
            onClick = onStop,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RecordingStopCapsuleButton(
    elapsed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TopOutColors.Red,
            contentColor = Color.White
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        IosInlineStopIcon(Modifier.size(24.dp))
        Spacer(Modifier.size(7.dp))
        Text(
            elapsed,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 14.sp,
                maxFontSize = 16.sp,
                stepSize = 0.5.sp
            ),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun IosInlineStopIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height)
        drawCircle(
            color = Color.White,
            radius = side * 0.44f,
            center = center
        )
        val square = side * 0.36f
        drawRoundRect(
            color = TopOutColors.Red,
            topLeft = Offset(center.x - square / 2f, center.y - square / 2f),
            size = Size(square, square),
            cornerRadius = CornerRadius(square * 0.1f, square * 0.1f)
        )
    }
}

@Composable
private fun RecordingCountdownOverlay(
    countdown: Int?,
    isLandscape: Boolean
) {
    countdown ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            countdown.toString(),
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = if (isLandscape) 120.sp else 150.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PowerSavingRecordingOverlay(
    elapsedSeconds: Double,
    recordingStarted: Boolean,
    onTogglePreview: () -> Unit,
    onStop: () -> Unit
) {
    // These proportions and spacings are direct counterparts of layoutPowerSavingUI(in:) in
    // HoldVC+UI.swift. Using the available bounds instead of fixed offsets keeps the grouping
    // identical on the Z Flip portrait display and after rotating to landscape.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val recordIconSide = if (landscape) {
            minOf(maxHeight * 0.44f, maxWidth * 0.22f)
        } else {
            minOf(maxWidth * 0.42f, maxHeight * 0.34f)
        }

        if (recordingStarted) {
            val toggleHeight = 42.dp
            val toggleWidth = minOf(maxWidth - 72.dp, if (landscape) 176.dp else 198.dp)
            val toggleGap = 22.dp
            val elapsedHeight = if (landscape) 64.dp else 86.dp
            val elapsedGap = if (landscape) 16.dp else 28.dp
            val toggleCenterOffset = -(recordIconSide / 2 + toggleGap + toggleHeight / 2)
            val elapsedCenterOffset = recordIconSide / 2 + elapsedGap + elapsedHeight / 2

            TopOutCapsuleButton(
                text = tr("camera.show_live_preview"),
                onClick = onTogglePreview,
                modifier = Modifier.align(Alignment.Center)
                    .offset(y = toggleCenterOffset)
                    .size(width = toggleWidth, height = toggleHeight)
            )
            IconButton(
                onClick = onStop,
                modifier = Modifier.align(Alignment.Center).size(recordIconSide)
            ) {
                IosPowerSavingStopIcon(Modifier.fillMaxSize())
            }
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .offset(y = elapsedCenterOffset)
                    .size(
                        width = minOf(maxWidth - 48.dp, if (landscape) 360.dp else maxWidth - 48.dp),
                        height = elapsedHeight
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    formatElapsed(elapsedSeconds),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 62.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun IosPowerSavingStopIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val lineWidth = max(2.5.dp.toPx(), side * 0.065f)
        val circleInset = lineWidth * 0.5f + side * 0.04f
        val radius = (side - circleInset * 2f) * 0.5f

        drawCircle(
            color = Color.White,
            radius = radius,
            center = center,
            style = Stroke(width = lineWidth)
        )

        val squareSide = side * 0.42f
        val squareTopLeft = Offset(
            center.x - squareSide * 0.5f,
            center.y - squareSide * 0.5f
        )
        drawRoundRect(
            color = TopOutColors.Red,
            topLeft = squareTopLeft,
            size = Size(squareSide, squareSide),
            cornerRadius = CornerRadius(squareSide * 0.12f, squareSide * 0.12f)
        )
    }
}

@Composable
private fun LaunchOverlay() {
    val isDark = isSystemInDarkTheme()
    val background = if (isDark) Color(0xFF1D242C) else Color(0xFFF4F6F8)
    val launchImage = if (isDark) R.drawable.launch_dark else R.drawable.launch_light

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(launchImage),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

private fun formatElapsed(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

private const val LENS_TRANSITION_FADE_MILLIS = 150

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
