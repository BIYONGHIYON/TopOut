package com.topout.cam.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.topout.cam.TopOutViewModel
import com.topout.cam.localization.AppLanguageManager
import com.topout.cam.localization.tr
import com.topout.cam.model.CameraStage
import com.topout.cam.model.RecordingState
import com.topout.cam.model.TopOutCopy
import com.topout.cam.ui.components.AspectFrame
import com.topout.cam.ui.components.CameraPreview
import com.topout.cam.ui.components.SelectionEditor
import com.topout.cam.ui.components.TopStatusBanner

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
    // 큰 화면에서도 선택과 녹화 중에는 촬영 방향을 고정한다.
    val cameraIsLandscape = if (lockCaptureOrientation) state.isLandscape else isLandscape

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
            }
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
        // 촬영 화면만 자동 잠금을 막고 결과 화면은 시스템 설정을 따른다.
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
                // 분석 중에는 요청을 유지하고 완료 후에만 미리보기로 돌아간다.
                if (!state.isBusy) viewModel.retakeSelection()
            }
            CameraStage.RECORDING -> {
                // 촬영 시작과 파일 마무리 중에도 같은 안전 종료 경로를 사용한다.
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
                                // 모든 화면비와 단계에서 같은 미리보기 뷰를 유지한다.
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

                // 미리보기 표시 여부와 관계없이 같은 카운트다운을 사용한다.
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
