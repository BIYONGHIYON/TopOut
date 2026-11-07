package com.topout.cam

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.topout.cam.camera.CameraEngine
import com.topout.cam.camera.LensTransitionEvent
import com.topout.cam.data.SettingsRepository
import com.topout.cam.detection.MotionEvent
import com.topout.cam.detection.MotionFinishReference
import com.topout.cam.detection.PoseClimbAnalyzer
import com.topout.cam.media.VideoExporter
import com.topout.cam.localization.tr
import com.topout.cam.model.AppSettings
import com.topout.cam.model.CameraStage
import com.topout.cam.model.ClipRange
import com.topout.cam.model.ClipRangeNormalizer
import com.topout.cam.model.DetectedClipRangeStore
import com.topout.cam.model.GalleryClipSelection
import com.topout.cam.model.LensTransitionPhase
import com.topout.cam.model.LensTransitionUiState
import com.topout.cam.model.RecordedVideo
import com.topout.cam.model.SavedGalleryVideo
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution
import com.topout.cam.model.RecordingState
import com.topout.cam.model.RecordingStopNotice
import com.topout.cam.model.TopOutConfig
import com.topout.cam.model.TopOutCopy
import com.topout.cam.model.TopOutUiState
import com.topout.cam.sam.HoldSegmenter
import com.topout.cam.sam.OnnxMobileSamSegmenter
import com.topout.cam.util.StorageMonitor
import com.topout.cam.util.TemporaryVideoCleaner
import com.topout.cam.util.createHoldOverlayBitmap
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TopOutViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val cameraEngine = CameraEngine(application)
    private val videoExporter = VideoExporter(application)
    private val mobileSam = OnnxMobileSamSegmenter(application)
    private val holdSegmenter: HoldSegmenter = mobileSam
    private val poseAnalyzer = PoseClimbAnalyzer(application, onEvent = ::handleMotionEvent)

    private val _uiState = MutableStateFlow(TopOutUiState())
    val uiState: StateFlow<TopOutUiState> = _uiState.asStateFlow()

    private var storageJob: Job? = null
    private var countdownJob: Job? = null
    private var cameraBindingJob: Job? = null
    private var mobileSamPreloadJob: Job? = null
    private var selectionAnalysisJob: Job? = null
    private var matBoundaryTimeoutJob: Job? = null
    private var selectionRequestId = 0L
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = handleMemoryPressure(level)
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = handleMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
    private var reviewTopHoldJob: Job? = null
    // 화면 재생성 뒤 이전 UI가 남지 않도록 생명주기 객체는 약한 참조로 보관한다.
    private var latestLifecycleOwner = WeakReference<LifecycleOwner>(null)
    private var latestPreviewView = WeakReference<PreviewView>(null)
    private var activeDetectedStart: Double? = null
    private val completedRanges = DetectedClipRangeStore()
    private var activeRecordingSettings: AppSettings? = null
    private var activeRecordingLandscape = false
    private var activeRecordingTargetRotation = 0
    private var recordingAnalysisAvailable = true
    private var ownedRecordingFile: File? = null
    private var appInForeground = false

    init {
        val createdAtMillis = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            TemporaryVideoCleaner.purgeStale(application, createdAtMillis)
        }

        application.registerComponentCallbacks(memoryCallbacks)

        cameraEngine.onRecordingStarted = {
            val settings = activeRecordingSettings ?: _uiState.value.settings
            val canAnalyze = settings.autoClipDetectionEnabled && recordingAnalysisAvailable
            if (canAnalyze) poseAnalyzer.markRecordingStarted() else poseAnalyzer.stop()
            _uiState.update { state ->
                state.copy(
                    recordingState = RecordingState.RECORDING,
                    recordingCountdown = null,
                    statusText = activeRecordingStatusText(settings)
                )
            }
            startStorageMonitoring()
        }
        cameraEngine.onRecordingStartCancelled = {
            // 카메라 화면 재생성으로 시작 조건이 무효화되면 같은 시작 취소 경로를 사용한다.
            if (_uiState.value.recordingState == RecordingState.STARTING) stopRecording()
        }
        cameraEngine.onRecordingProgress = { seconds ->
            _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
        }
        cameraEngine.onRecordingFinalized = { result ->
            result.getOrNull()?.file?.let(::adoptRecordingFile)
            viewModelScope.launch { finishRecording(result) }
        }
        cameraEngine.onZoomCorrected = { correctedZoom ->
            _uiState.update { it.copy(zoomFactor = correctedZoom) }
        }
        cameraEngine.onLensTransition = ::handleLensTransition
        cameraEngine.onRecordingAnalysisUnavailable = {
            recordingAnalysisAvailable = false
            if (_uiState.value.recordingState in setOf(RecordingState.STARTING, RecordingState.RECORDING)) {
                poseAnalyzer.stop()
            }
        }


        viewModelScope.launch {
            settingsRepository.settings.collectLatest { persisted ->
                val normalized = normalizeSettings(persisted, _uiState.value)
                _uiState.update { state ->
                    val updated = state.copy(settings = normalized)
                    val status = if (state.stage == CameraStage.PREVIEW && state.recordingState == RecordingState.IDLE) {
                        previewGuidance(updated.settings)
                    } else state.statusText
                    updated.copy(statusText = status)
                }
            }
        }
    }

    private fun handleLensTransition(event: LensTransitionEvent) {
        when (event) {
            is LensTransitionEvent.Started -> {
                // 렌즈 전환마다 표시 중인 프레임을 한 번만 캡처한다.
                val frame = runCatching { latestPreviewView.get()?.bitmap }
                    .getOrNull()
                    ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                _uiState.update { state ->
                    state.copy(
                        zoomFactor = event.displayZoom,
                        lensTransition = state.lensTransition.copy(
                            id = event.id,
                            phase = if (event.awaitingGestureEnd) {
                                LensTransitionPhase.AWAITING_GESTURE_END
                            } else {
                                LensTransitionPhase.REBINDING
                            },
                            frame = frame,
                            frameScale = event.frameScale.validFrameScale()
                        )
                    )
                }
            }

            is LensTransitionEvent.Updated -> _uiState.update { state ->
                val transition = state.lensTransition
                val validPhase = if (event.awaitingGestureEnd) {
                    transition.phase == LensTransitionPhase.AWAITING_GESTURE_END
                } else {
                    transition.inProgress
                }
                if (transition.id != event.id || !validPhase) {
                    state
                } else {
                    state.copy(
                        zoomFactor = event.displayZoom,
                        lensTransition = transition.copy(
                            phase = if (event.awaitingGestureEnd) {
                                LensTransitionPhase.AWAITING_GESTURE_END
                            } else {
                                LensTransitionPhase.REBINDING
                            },
                            frameScale = event.frameScale.validFrameScale()
                        )
                    )
                }
            }

            is LensTransitionEvent.Completed -> _uiState.update { state ->
                val transition = state.lensTransition
                if (transition.id != event.id || !transition.inProgress) {
                    state
                } else {
                    val finishedTransition = if (transition.frame == null) {
                        LensTransitionUiState(rebaseToken = transition.rebaseToken + 1)
                    } else {
                        transition.copy(
                            phase = LensTransitionPhase.FADING,
                            rebaseToken = transition.rebaseToken + 1
                        )
                    }
                    state.copy(
                        zoomFactor = event.displayZoom,
                        lensTransition = finishedTransition
                    )
                }
            }

            is LensTransitionEvent.Aborted -> _uiState.update { state ->
                val transition = state.lensTransition
                if (transition.id != event.id || !transition.inProgress) {
                    state
                } else {
                    state.copy(
                        zoomFactor = event.displayZoom,
                        lensTransition = LensTransitionUiState(
                            rebaseToken = transition.rebaseToken + 1
                        )
                    )
                }
            }
        }
    }

    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView) {
        latestLifecycleOwner = WeakReference(owner)
        latestPreviewView = WeakReference(previewView)
        cameraBindingJob?.cancel()
        _uiState.update {
            val transition = it.lensTransition
            it.copy(
                cameraReady = false,
                lensTransition = LensTransitionUiState(
                    rebaseToken = transition.rebaseToken +
                        if (transition.inProgress) 1 else 0
                )
            )
        }
        cameraBindingJob = viewModelScope.launch {
            runCatching {
                var state = _uiState.value
                val captureLocked = state.stage == CameraStage.RECORDING
                val targetRotation = if (captureLocked) {
                    state.cameraTargetRotation
                } else {
                    previewView.display?.rotation ?: state.cameraTargetRotation
                }
                if (!captureLocked && targetRotation != state.cameraTargetRotation) {
                    _uiState.update { it.copy(cameraTargetRotation = targetRotation) }
                    state = _uiState.value
                }
                cameraEngine.bind(
                    lifecycleOwner = owner,
                    previewView = previewView,
                    resolution = state.settings.resolution,
                    frameRate = state.settings.frameRate,
                    aspectRatio = state.settings.aspectRatio,
                    isLandscape = state.isLandscape,
                    targetRotation = targetRotation,
                    analyzer = poseAnalyzer,
                    requiresAnalysis = state.settings.autoClipDetectionEnabled
                )
            }.onSuccess { capabilities ->
                recordingAnalysisAvailable = cameraEngine.isAnalysisAvailable()
                val previous = _uiState.value.settings
                val normalized = normalizeSettings(previous, _uiState.value.copy(capabilities = capabilities))
                val zoom = _uiState.value.zoomFactor.coerceIn(
                    capabilities.minZoomRatio,
                    capabilities.maxZoomRatio
                )
                _uiState.update {
                    it.copy(
                        capabilities = capabilities,
                        settings = normalized,
                        zoomFactor = zoom,
                        cameraReady = true,
                        // iOS와 같이 카메라 세션 구성이 끝난 뒤 시작 화면을 닫는다.
                        launchVisible = false,
                        errorMessage = null
                    )
                }
                if (normalized != previous) settingsRepository.save(normalized)
                cameraEngine.setZoom(zoom)
                val currentState = _uiState.value
                // 준비 완료 분석이 5초 제한 안에 끝나도록 자동 구간 탐지를 쓸 때만 녹화 전에 미리 불러온다.
                if (currentState.settings.autoClipDetectionEnabled &&
                    currentState.stage in setOf(CameraStage.PREVIEW, CameraStage.SELECT)
                ) {
                    preloadMobileSamAfterCameraStarts()
                }
                cameraEngine.setPreviewRenderingEnabled(
                    currentState.stage != CameraStage.RECORDING || currentState.livePreviewVisible
                )
            }.onFailure { error ->
                // 재연결에 따른 이전 작업 취소는 오류로 표시하지 않는다.
                if (error is CancellationException) return@onFailure
                _uiState.update { state ->
                    state.copy(
                        cameraReady = false,
                        launchVisible = false,
                        errorMessage = tr("error.camera_start")
                    )
                }
            }
        }
    }

    fun updateOrientation(isLandscape: Boolean) {
        _uiState.update { state ->
            // 경계선 측정·보정과 녹화가 끝날 때까지 촬영 방향을 유지한다.
            if (state.isBusy || state.stage in setOf(CameraStage.SELECT, CameraStage.RECORDING)) {
                state
            } else {
                state.copy(isLandscape = isLandscape)
            }
        }
    }

    fun updateTargetRotation(rotation: Int) {
        val state = _uiState.value
        if (state.isBusy || state.stage in setOf(CameraStage.SELECT, CameraStage.RECORDING)) return
        _uiState.update { it.copy(cameraTargetRotation = rotation) }
        cameraEngine.updateTargetRotation(rotation)
    }

    private fun defaultMatLine(): List<Offset> = listOf(
        Offset(0.06f, TopOutConfig.MatDefaultY),
        Offset(0.94f, TopOutConfig.MatDefaultY)
    )

    private fun previewGuidance(settings: AppSettings): String =
        TopOutCopy.previewGuidance(settings)

    fun updateMatPoint(index: Int, point: Offset) {
        _uiState.update { state ->
            if (index !in state.matLine.indices) state
            else state.copy(
                matLine = state.matLine.toMutableList().also {
                    // 자동 인식한 가로 위치는 유지하고 세로 위치만 보정한다.
                    it[index] = Offset(it[index].x, point.y.coerceIn(0f, 1f))
                }
            )
        }
    }

    /** 녹화 중 손으로 고친 경계선은 손을 뗄 때 이어지는 등반 분석에 바로 쓴다. */
    fun onMatLineDragChanged(dragging: Boolean) {
        if (dragging) return
        val state = _uiState.value
        if (state.recordingState in RecordingStates.Active && state.matLine.size >= 2) {
            poseAnalyzer.updateMatLine(state.matLine)
        }
    }

    // MARK: 준비 완료와 경계선 보정

    /**
     * 준비 완료는 현재 카메라 프레임 한 장으로 매트 경계를 측정한다.
     * 캡처한 비트맵은 보여주지 않고, 보정은 실시간 미리보기 위에서 한다. HoldVC.onReady와 같다.
     */
    fun onReady(frameProvider: () -> Bitmap?, isLandscape: Boolean, targetRotation: Int) {
        val current = _uiState.value
        if (current.stage != CameraStage.PREVIEW ||
            current.recordingState != RecordingState.IDLE ||
            current.isBusy ||
            !current.cameraReady ||
            current.lensTransition.inProgress ||
            !current.settings.autoClipDetectionEnabled
        ) return

        updateOrientation(isLandscape)
        // 준비 완료 시점의 미리보기 회전값을 촬영 종료까지 유지한다.
        _uiState.update { it.copy(cameraTargetRotation = targetRotation) }
        cameraEngine.updateTargetRotation(targetRotation)

        val requestId = beginSelectionRequest()
        _uiState.update { it.copy(isBusy = true, statusText = tr("camera.analyzing_mat")) }

        matBoundaryTimeoutJob = viewModelScope.launch {
            delay(TopOutConfig.MatBoundaryAnalysisTimeoutMillis)
            cancelSelectionAnalysis(requestId)
            completeMatBoundaryAnalysis(requestId, defaultMatLine())
        }

        selectionAnalysisJob = viewModelScope.launch {
            // 준비 완료 뒤 안정된 분석 프레임을 사용하고 분석 미지원 시 미리보기 캡처로 대체한다.
            val frame = if (recordingAnalysisAvailable) {
                poseAnalyzer.captureNextFrame(
                    maxLongSide = TopOutConfig.CaptureMaxLongSide,
                    minimumFramesAfterRequest = 6
                ) ?: runCatching {
                    frameProvider()?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
            } else {
                runCatching {
                    frameProvider()?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
            }

            if (frame == null || frame.width <= 0 || frame.height <= 0) {
                frame?.recycle()
                if (finishSelectionRequest(requestId)) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusText = tr("camera.capture_failed")
                        )
                    }
                }
                return@launch
            }

            if (requestId != selectionRequestId) {
                frame.recycle()
                return@launch
            }

            val mat = try {
                mobileSam.detectMatBoundary(frame).getOrElse {
                    defaultMatLine()
                }
            } finally {
                // 경계 보정 중 재사용하지 않는 임베딩은 즉시 해제한다.
                mobileSam.releaseCachedEmbedding()
                if (!frame.isRecycled) frame.recycle()
            }

            completeMatBoundaryAnalysis(requestId, mat)
        }
    }

    private fun beginSelectionRequest(): Long {
        invalidateSelectionRequest()
        return selectionRequestId
    }

    private fun invalidateSelectionRequest() {
        matBoundaryTimeoutJob?.cancel()
        matBoundaryTimeoutJob = null
        selectionAnalysisJob?.cancel()
        selectionAnalysisJob = null
        selectionRequestId += 1
    }

    private fun finishSelectionRequest(requestId: Long): Boolean {
        if (requestId != selectionRequestId) return false
        matBoundaryTimeoutJob?.cancel()
        matBoundaryTimeoutJob = null
        selectionAnalysisJob = null
        selectionRequestId += 1
        return true
    }

    private fun cancelSelectionAnalysis(requestId: Long) {
        if (requestId != selectionRequestId) return
        selectionAnalysisJob?.cancel()
        selectionAnalysisJob = null
    }

    /** 경계를 찾지 못하면 MobileSAM이 75% 기본선을 돌려주고, 사용자가 보정한 뒤 녹화한다. */
    private fun completeMatBoundaryAnalysis(requestId: Long, matLine: List<Offset>) {
        if (!finishSelectionRequest(requestId)) return
        _uiState.update { state ->
            state.copy(
                stage = CameraStage.SELECT,
                matLine = matLine.takeIf { it.size >= 2 } ?: defaultMatLine(),
                isBusy = false,
                statusText = tr("camera.selection_mat")
            )
        }
    }

    fun retakeSelection() {
        invalidateSelectionRequest()
        cameraEngine.setPreviewRenderingEnabled(true)
        _uiState.update { state ->
            state.copy(
                stage = CameraStage.PREVIEW,
                matLine = emptyList(),
                isBusy = false,
                statusText = previewGuidance(state.settings)
            )
        }
    }

    /** 메모리 부족 알림을 받으면 분석 중이 아닐 때 모델 세션을 닫고 다음 분석에서 다시 연다. */
    private fun handleMemoryPressure(level: Int) {
        @Suppress("DEPRECATION")
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val state = _uiState.value
        if (state.isBusy || state.reviewTopHoldAnalyzing) return
        releaseMobileSamSessions()
    }

    /** 세션을 닫고 다음 분석 또는 예열 때 다시 연다. */
    private fun releaseMobileSamSessions() {
        mobileSamPreloadJob?.cancel()
        mobileSamPreloadJob = null
        mobileSam.releaseSessions()
    }

    fun setZoom(zoom: Float) {
        val currentState = _uiState.value
        // 보정과 녹화 중에는 경계선을 만든 카메라 화각을 유지한다.
        if (currentState.stage != CameraStage.PREVIEW ||
            currentState.recordingState != RecordingState.IDLE ||
            currentState.isBusy ||
            !currentState.cameraReady ||
            currentState.lensTransition.inProgress
        ) return
        val capabilities = currentState.capabilities
        val value = zoom.coerceIn(capabilities.minZoomRatio, capabilities.maxZoomRatio)
        _uiState.update { state -> state.copy(zoomFactor = value) }
        cameraEngine.setZoom(value)
    }

    fun updateDeferredLensZoom(zoom: Float) {
        val state = _uiState.value
        if (state.stage != CameraStage.PREVIEW ||
            state.recordingState != RecordingState.IDLE ||
            !state.lensTransition.awaitingGestureEnd
        ) return
        cameraEngine.updateDeferredLensSwitchZoom(zoom)
    }

    fun finishZoomGesture() {
        cameraEngine.finishDeferredLensSwitchGesture()
    }

    /**
     * 녹화 시작 버튼. 자동 구간 탐지는 준비 완료로 측정·보정한 경계선이 있는 보정 화면에서,
     * 자동 구간 탐지를 끄면 카메라 화면에서 바로 시작한다.
     */
    fun startRecording() {
        val current = _uiState.value
        val validStage = if (current.settings.autoClipDetectionEnabled) {
            current.stage == CameraStage.SELECT && current.matLine.size >= 2
        } else {
            current.stage == CameraStage.PREVIEW
        }
        if (!validStage ||
            current.recordingState != RecordingState.IDLE ||
            current.isBusy ||
            !current.cameraReady ||
            current.lensTransition.inProgress
        ) return
        invalidateSelectionRequest()
        val state = _uiState.value
        val settings = state.settings
        deleteCurrentRecording()

        countdownJob?.cancel()
        completedRanges.clear()
        activeDetectedStart = null
        activeRecordingSettings = settings
        activeRecordingLandscape = state.isLandscape
        activeRecordingTargetRotation = state.cameraTargetRotation
        recordingAnalysisAvailable = cameraEngine.isAnalysisAvailable()
        if (settings.autoClipDetectionEnabled && !recordingAnalysisAvailable) {
            setError(tr("error.analysis_unavailable"))
            return
        }

        if (settings.autoClipDetectionEnabled) {
            // 탑홀드는 결과 화면에서만 고르므로 녹화 중에는 가장 높이 올라간 순간을 완등으로 본다.
            poseAnalyzer.configure(
                matLine = state.matLine,
                topBox = null,
                finishReference = MotionFinishReference.HIGHEST_POSE_POINT
            )
            poseAnalyzer.resetForRecording()
        } else {
            poseAnalyzer.stop()
        }

        // 녹화 중에는 경계선을 자동으로 다시 재지 않으므로 녹화 메모리를 위해 모델 세션을 닫는다.
        mobileSam.releaseCachedEmbedding()
        releaseMobileSamSessions()
        _uiState.update {
            it.copy(
                stage = CameraStage.RECORDING,
                recordingState = RecordingState.STARTING,
                recordingElapsedSeconds = 0.0,
                recordingCountdown = TopOutConfig.RecordingCountdownSeconds.toInt(),
                detectedRanges = emptyList(),
                livePreviewEnabled = settings.livePreviewDuringRecordingEnabled,
                statusText = recordingCountdownStatusText(settings)
            )
        }
        cameraEngine.setPreviewRenderingEnabled(settings.livePreviewDuringRecordingEnabled)

        countdownJob = viewModelScope.launch {
            for (remaining in TopOutConfig.RecordingCountdownSeconds.toInt() downTo 1) {
                _uiState.update {
                    it.copy(
                        recordingCountdown = remaining,
                        statusText = recordingCountdownStatusText(settings, remaining)
                    )
                }
                delay(1_000)
            }
            // 취소된 카운트다운이 녹화를 다시 시작하지 못하도록 상태를 확인한다.
            if (_uiState.value.recordingState == RecordingState.STARTING) {
                cameraEngine.startRecording(includeAudio = true)
            }
        }
    }

    fun stopRecording(notice: RecordingStopNotice? = null) {
        val recordingState = _uiState.value.recordingState
        if (recordingState !in setOf(RecordingState.STARTING, RecordingState.RECORDING)) return
        countdownJob?.cancel()
        storageJob?.cancel()

        if (recordingState == RecordingState.STARTING) {
            cameraEngine.cancelRecordingStart()
            poseAnalyzer.stop()
            cameraEngine.setPreviewRenderingEnabled(true)
            _uiState.update { state ->
                state.copy(
                    stage = CameraStage.PREVIEW,
                    recordingState = RecordingState.IDLE,
                    recordingCountdown = null,
                    livePreviewEnabled = true,
                    matLine = emptyList(),
                    statusText = previewGuidance(state.settings)
                )
            }
            // 녹화 시작 때 닫은 모델은 다음 준비 완료를 위해 다시 불러 둔다.
            if (_uiState.value.settings.autoClipDetectionEnabled) preloadMobileSamAfterCameraStarts()
            return
        }

        poseAnalyzer.stop()?.let(::appendDetectedRange)
        _uiState.update {
            it.copy(
                recordingState = RecordingState.FINALIZING,
                recordingCountdown = null,
                statusText = if (notice == null) {
                    tr("camera.processing_video")
                } else {
                    tr("camera.finishing")
                }
            )
        }
        cameraEngine.stopRecording(notice)
    }

    fun onAppBackgrounded() {
        appInForeground = false
        if (_uiState.value.recordingState in RecordingStates.Active) {
            stopRecording(RecordingStopNotice.Background)
        }
    }

    fun onAppForegrounded() {
        appInForeground = true
    }

    /** Stops or resumes CameraX's preview stream while recording and analysis continue. */
    fun toggleLivePreview() {
        if (_uiState.value.recordingState != RecordingState.RECORDING) return
        val enabled = !_uiState.value.livePreviewEnabled
        if (!cameraEngine.setPreviewRenderingEnabled(enabled)) return

        val updatedSettings = _uiState.value.settings
            .copy(livePreviewDuringRecordingEnabled = enabled)
        _uiState.update {
            it.copy(
                livePreviewEnabled = enabled,
                settings = updatedSettings
            )
        }
        viewModelScope.launch { settingsRepository.save(updatedSettings) }
    }

    fun showSettings() = _uiState.update { it.copy(settingsVisible = true) }
    fun hideSettings() = _uiState.update { it.copy(settingsVisible = false) }

    /** Settings can change the process locale while this ViewModel survives Activity recreation. */
    fun refreshLocalizedCopy() {
        _uiState.update { state ->
            val localizedStatus = when (state.stage) {
                CameraStage.PREVIEW -> if (state.isBusy) tr("camera.analyzing_mat") else previewGuidance(state.settings)
                CameraStage.SELECT -> tr("camera.selection_mat")
                CameraStage.RECORDING -> activeRecordingStatusText(state.settings)
                CameraStage.REVIEW -> if (state.detectedRanges.isEmpty()) {
                    tr("review.manual_prompt")
                } else {
                    tr("review.auto_prompt")
                }
            }
            state.copy(
                statusText = localizedStatus,
                errorMessage = null,
                savingStatus = null,
                savingStatusSelection = null
            )
        }
    }

    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            val before = _uiState.value.settings
            val normalized = normalizeSettings(settings, _uiState.value)
            settingsRepository.save(normalized)
            _uiState.update { state ->
                state.copy(
                    settings = normalized,
                    settingsVisible = false
                )
            }
            _uiState.update { state ->
                if (state.stage == CameraStage.PREVIEW && state.recordingState == RecordingState.IDLE && !state.isBusy) {
                    state.copy(statusText = previewGuidance(state.settings))
                } else state
            }
            val updated = _uiState.value
            // 자동 구간 탐지를 켰다면 준비 완료 전에 모델을 미리 불러 둔다.
            if (normalized.autoClipDetectionEnabled && updated.cameraReady &&
                updated.stage == CameraStage.PREVIEW
            ) {
                preloadMobileSamAfterCameraStarts()
            }

            val cameraSessionChanged = before.aspectRatio != normalized.aspectRatio ||
                before.resolution != normalized.resolution ||
                before.frameRate != normalized.frameRate
            if (!cameraSessionChanged &&
                updated.recordingState == RecordingState.IDLE &&
                updated.stage == CameraStage.PREVIEW
            ) {
                rebindLatestCamera()
            }
            // 새 화면 크기가 확정된 뒤 미리보기 콜백에서 카메라를 다시 연결한다.
        }
    }

    fun saveSelectedClip(startSeconds: Double, endSeconds: Double, includeOverlay: Boolean) {
        val video = _uiState.value.recordedVideo ?: return
        if (_uiState.value.saving) return
        val selection = GalleryClipSelection(startSeconds, endSeconds, includeOverlay)
        _uiState.update {
            it.copy(
                saving = true,
                savingStatus = if (includeOverlay) tr("review.processing_mask") else tr("review.trimming"),
                savingStatusSelection = selection
            )
        }
        viewModelScope.launch {
            var savingToGallery = false
            try {
                val savedUri = videoExporter.exportToGallery(
                    inputUri = video.uri,
                    startSeconds = startSeconds,
                    endSeconds = endSeconds,
                    overlayBitmap = video.overlayBitmap,
                    includeOverlay = includeOverlay,
                    onSavingToGallery = {
                        savingToGallery = true
                        _uiState.update {
                            it.copy(
                                savingStatus = tr("review.saving_verifying"),
                                savingStatusSelection = selection
                            )
                        }
                    }
                )
                _uiState.update { state ->
                    val saved = SavedGalleryVideo(
                        uri = savedUri,
                        startSeconds = startSeconds,
                        endSeconds = endSeconds,
                        includeOverlay = includeOverlay
                    )
                    state.copy(
                        saving = false,
                        savingStatus = tr("review.gallery_verified"),
                        savingStatusSelection = selection,
                        savedGalleryVideos = state.savedGalleryVideos
                            .filterNot { it.selection.matches(selection) } + saved
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val prefix = if (savingToGallery) tr("error.save_verify") else tr("error.save_clip")
                _uiState.update {
                    it.copy(
                        saving = false,
                        savingStatus = prefix,
                        savingStatusSelection = selection
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun onLensTransitionFadeFinished(transitionId: Long, frame: Bitmap) {
        _uiState.update { state ->
            val transition = state.lensTransition
            if (transition.id == transitionId &&
                transition.phase == LensTransitionPhase.FADING &&
                transition.frame === frame
            ) {
                state.copy(
                    lensTransition = LensTransitionUiState(
                        rebaseToken = transition.rebaseToken
                    )
                )
            } else {
                state
            }
        }
    }

    /**
     * 결과 화면에서 멈춘 프레임의 탑홀드를 탭하면 그 장면 기준으로 마스크를 다시 만든다.
     * [tap]은 [containerSize] 안의 좌표이고, 영상은 화면 안에 비율을 유지해 맞춰 그려진다.
     */
    fun selectTopHoldInReview(positionMs: Long, tap: Offset, containerSize: Size) {
        val state = _uiState.value
        val video = state.recordedVideo ?: return
        if (state.stage != CameraStage.REVIEW ||
            !video.topHoldSelectionEnabled ||
            state.saving ||
            state.reviewTopHoldAnalyzing ||
            containerSize.width <= 0f ||
            containerSize.height <= 0f
        ) return

        reviewTopHoldJob?.cancel()
        _uiState.update {
            it.copy(reviewTopHoldAnalyzing = true, reviewTopHoldStatus = tr("camera.analyzing_hold"))
        }
        reviewTopHoldJob = viewModelScope.launch {
            val frame = videoExporter.extractFrame(video.uri, positionMs, TopOutConfig.CaptureMaxLongSide)
            val normalizedTap = frame?.let { fittedNormalizedPoint(tap, containerSize, it.width, it.height) }
            val overlay = if (frame != null && normalizedTap != null) {
                try {
                    val mask = withTimeoutOrNull(TopOutConfig.TopHoldAnalysisTimeoutMillis) {
                        holdSegmenter.segment(frame, normalizedTap).getOrNull()
                    } ?: mobileSam.fallbackHoldMask(frame, normalizedTap)
                    val width = video.overlayWidth.takeIf { it > 0 } ?: frame.width
                    val height = video.overlayHeight.takeIf { it > 0 } ?: frame.height
                    createHoldOverlayBitmap(width, height, mask)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // 분석이 예외로 끝나도 마스크·저장 버튼이 잠긴 채 남지 않게 실패로 처리한다.
                    null
                } finally {
                    mobileSam.releaseCachedEmbedding()
                    frame.recycle()
                }
            } else {
                frame?.recycle()
                null
            }
            reviewTopHoldJob = null

            _uiState.update { current ->
                val currentVideo = current.recordedVideo
                if (current.stage != CameraStage.REVIEW || currentVideo?.uri != video.uri) {
                    overlay?.recycle()
                    return@update current
                }
                if (overlay == null) {
                    current.copy(
                        reviewTopHoldAnalyzing = false,
                        reviewTopHoldStatus = if (normalizedTap == null && frame != null) null
                        else tr("camera.selection_failed")
                    )
                } else {
                    // 마스크가 바뀌었으므로 마스크를 넣어 저장한 이전 결과는 지금 선택과 다르다.
                    current.copy(
                        recordedVideo = currentVideo.copy(overlayBitmap = overlay),
                        savedGalleryVideos = current.savedGalleryVideos.filterNot { it.includeOverlay },
                        reviewTopHoldAnalyzing = false,
                        // 도움말을 켜면 결과 화면이 확대 안내를 보여주고, 끄면 지정 결과만 알린다.
                        reviewTopHoldStatus = if (current.settings.helpEnabled) null
                        else tr("review.top_hold_updated")
                    )
                }
            }
        }
    }

    /** 화면에 비율을 유지해 맞춘 영상 영역 안의 탭이면 영상 기준 정규 좌표를, 바깥이면 null을 돌려준다. */
    private fun fittedNormalizedPoint(tap: Offset, container: Size, frameWidth: Int, frameHeight: Int): Offset? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val scale = min(container.width / frameWidth, container.height / frameHeight)
        val width = frameWidth * scale
        val height = frameHeight * scale
        val left = (container.width - width) / 2f
        val top = (container.height - height) / 2f
        val x = (tap.x - left) / width
        val y = (tap.y - top) / height
        return if (x in 0f..1f && y in 0f..1f) Offset(x, y) else null
    }

    /** Close discards both the review file and selection, matching ReviewVC's close action. */
    fun closeReview() {
        reviewTopHoldJob?.cancel()
        reviewTopHoldJob = null
        cameraEngine.setPreviewRenderingEnabled(true)
        deleteCurrentRecording()
        completedRanges.clear()
        activeDetectedStart = null
        activeRecordingSettings = null
        _uiState.update { state ->
            TopOutUiState(
                settings = state.settings,
                capabilities = state.capabilities,
                launchVisible = false,
                zoomFactor = state.zoomFactor,
                isLandscape = state.isLandscape,
                statusText = previewGuidance(state.settings)
            )
        }
    }

    private suspend fun finishRecording(result: Result<CameraEngine.FinalizedRecording>) {
        storageJob?.cancel()
        // 녹화 중지는 인코더 종료 전에 열린 탐지 구간을 닫는다.
        poseAnalyzer.stop()
        cameraEngine.setPreviewRenderingEnabled(true)
        cameraEngine.unbind()

        val finalized = result.getOrElse {
            recoverAfterRecordingFinalizationFailure()
            return
        }

        try {
            val settings = activeRecordingSettings ?: _uiState.value.settings
            val readyFile = finalized.file
            val metadata = videoExporter.inspectVideo(readyFile)
            val duration = metadata?.durationSeconds
                ?: finalized.durationSeconds.takeIf { it.isFinite() && it > 0.0 }
                ?: throw IllegalStateException(tr("error.saved_video_metadata"))
            val ranges = normalizeDetectedClipRanges(
                input = completedRanges.snapshot().mapNotNull { range ->
                    val startSeconds = range.startSeconds.coerceIn(0.0, duration)
                    val endSeconds = range.endSeconds.coerceIn(0.0, duration)
                    if (endSeconds > startSeconds) {
                        // 완료 구간에는 여백을 넣기 전 원본 탐지 시각을 저장한다.
                        ClipRange(startSeconds, endSeconds)
                    } else null
                },
                recordingDurationSeconds = duration
            )
            val defaultRange = ranges.lastOrNull() ?: ClipRange(0.0, duration)
            val fallbackSize = settings.aspectRatio.outputSize(
                settings.resolution,
                activeRecordingLandscape
            )
            val overlayWidth = metadata?.displayedWidth ?: fallbackSize.width
            val overlayHeight = metadata?.displayedHeight ?: fallbackSize.height

            _uiState.update { state ->
                state.copy(
                    stage = CameraStage.REVIEW,
                    recordingState = RecordingState.IDLE,
                    recordingCountdown = null,
                    livePreviewEnabled = true,
                    detectedRanges = ranges,
                    savedGalleryVideos = emptyList(),
                    savingStatus = null,
                    savingStatusSelection = null,
                    recordedVideo = RecordedVideo(
                        uri = Uri.fromFile(readyFile),
                        durationSeconds = duration,
                        detectedRanges = ranges,
                        defaultStartSeconds = defaultRange.startSeconds,
                        defaultEndSeconds = defaultRange.endSeconds,
                        // 탑홀드 마스크는 결과 화면에서 고른다.
                        overlayBitmap = null,
                        aspectRatio = settings.aspectRatio,
                        automaticStopNotice = finalized.notice,
                        topHoldSelectionEnabled = settings.topHoldMaskingEnabled,
                        overlayWidth = overlayWidth,
                        overlayHeight = overlayHeight
                    ),
                    reviewTopHoldAnalyzing = false,
                    reviewTopHoldStatus = null,
                    statusText = if (ranges.isEmpty()) {
                        tr("review.manual_prompt")
                    } else {
                        tr("review.auto_prompt")
                    }
                )
            }
            // 녹화 시작 때 닫은 모델은 결과 화면에서 탑홀드를 고를 때만 다시 불러온다.
            if (settings.topHoldMaskingEnabled) preloadMobileSamAfterCameraStarts()

        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            recoverAfterRecordingFinalizationFailure()
        }
    }

    private fun recoverAfterRecordingFinalizationFailure() {
        _uiState.update { state ->
            val recovered = state.copy(
                stage = CameraStage.PREVIEW,
                recordingState = RecordingState.IDLE,
                livePreviewEnabled = true,
                matLine = emptyList(),
                errorMessage = tr("error.recording_finish")
            )
            recovered.copy(statusText = previewGuidance(recovered.settings))
        }
        rebindLatestCamera()
    }

    private fun handleMotionEvent(event: MotionEvent) {
        viewModelScope.launch {
            if (_uiState.value.recordingState !in setOf(RecordingState.RECORDING, RecordingState.FINALIZING)) return@launch
            when (event) {
                is MotionEvent.Start -> {
                    // 시작 시각은 원본 값으로 저장하고 최종 정리 뒤 여백을 넣는다.
                    activeDetectedStart = event.seconds
                    _uiState.update {
                        it.copy(statusText = TopOutCopy.climbingStatus())
                    }
                }

                is MotionEvent.Finish -> {
                    val start = activeDetectedStart
                    if (start != null && event.seconds > start) {
                        appendDetectedRange(ClipRange(start, event.seconds))
                    }
                    activeDetectedStart = null
                    _uiState.update {
                        it.copy(
                            detectedRanges = completedRanges.snapshot(),
                            statusText = tr("camera.clip_saved")
                        )
                    }
                }

                MotionEvent.Retry -> {
                    activeDetectedStart = null
                    val settings = activeRecordingSettings ?: _uiState.value.settings
                    _uiState.update { it.copy(statusText = activeRecordingStatusText(settings)) }
                }
            }
        }
    }

    private fun appendDetectedRange(range: ClipRange) {
        // 모든 탐지 구간을 모은 뒤 마무리 단계에서 짧은 오탐만 제거한다.
        completedRanges.append(range)
    }

    private fun normalizeDetectedClipRanges(
        input: List<ClipRange>,
        recordingDurationSeconds: Double
    ): List<ClipRange> = ClipRangeNormalizer.normalize(
        input = input,
        recordingDurationSeconds = recordingDurationSeconds
    )

    private fun startStorageMonitoring() {
        storageJob?.cancel()
        storageJob = viewModelScope.launch {
            checkStorageAndStopIfNeeded()
            while (true) {
                delay(TopOutConfig.StorageCheckIntervalMillis)
                checkStorageAndStopIfNeeded()
            }
        }
    }

    private fun checkStorageAndStopIfNeeded() {
        val state = _uiState.value
        if (state.recordingState != RecordingState.RECORDING) return
        val available = StorageMonitor.availableBytes(getApplication())
        val required = StorageMonitor.requiredSafetyBytes(state.settings)
        if (available != null && available <= required) stopRecording(RecordingStopNotice.lowStorage(available))
    }

    private fun normalizeSettings(settings: AppSettings, state: TopOutUiState): AppSettings {
        val forced = settings
        val supported = state.capabilities.supportedFrameRates.filterValues { it.isNotEmpty() }
        if (supported.isEmpty()) return forced
        if (state.capabilities.supports(forced.resolution, forced.frameRate)) return forced

        val resolution = when {
            !supported[forced.resolution].isNullOrEmpty() -> forced.resolution
            supported.containsKey(RecordingResolution.FHD) -> RecordingResolution.FHD
            else -> supported.keys.first()
        }
        val rates = supported.getValue(resolution)
        val frameRate = when {
            forced.frameRate in rates -> forced.frameRate
            RecordingFrameRate.FPS30 in rates -> RecordingFrameRate.FPS30
            RecordingFrameRate.FPS60 in rates -> RecordingFrameRate.FPS60
            else -> rates.first()
        }
        return forced.copy(resolution = resolution, frameRate = frameRate)
    }

    private fun recordingCountdownStatusText(settings: AppSettings, remaining: Int? = null): String =
        TopOutCopy.countdownStatus(settings, remaining)

    private fun activeRecordingStatusText(settings: AppSettings): String =
        TopOutCopy.activeRecordingStatus(settings)

    private fun rebindLatestCamera() {
        val owner = latestLifecycleOwner.get() ?: return
        val preview = latestPreviewView.get() ?: return
        if (_uiState.value.stage == CameraStage.REVIEW) return
        bindCamera(owner, preview)
    }

    private fun preloadMobileSamAfterCameraStarts() {
        if (mobileSamPreloadJob != null) return
        mobileSamPreloadJob = viewModelScope.launch {
            // 예열 실패는 실제 분석에서 다시 시도하고 동시 요청은 같은 잠금으로 직렬화한다.
            mobileSam.prepare()
        }
    }

    private fun deleteCurrentRecording() {
        val files = buildSet {
            ownedRecordingFile?.let(::add)
            _uiState.value.recordedVideo?.uri?.path?.let { add(File(it)) }
        }
        ownedRecordingFile = null
        files.forEach { it.delete() }
    }

    private fun adoptRecordingFile(file: File) {
        ownedRecordingFile?.takeIf { it != file }?.delete()
        ownedRecordingFile = file
    }

    private fun setError(message: String) = _uiState.update { it.copy(errorMessage = message) }

    override fun onCleared() {
        storageJob?.cancel()
        countdownJob?.cancel()
        cameraBindingJob?.cancel()
        mobileSamPreloadJob?.cancel()
        selectionAnalysisJob?.cancel()
        matBoundaryTimeoutJob?.cancel()
        reviewTopHoldJob?.cancel()
        getApplication<Application>().unregisterComponentCallbacks(memoryCallbacks)
        deleteCurrentRecording()
        poseAnalyzer.stop()
        cameraEngine.release(onAnalysisDrained = poseAnalyzer::close)
        mobileSam.close()
        latestLifecycleOwner.clear()
        latestPreviewView.clear()
        super.onCleared()
    }
}

private object RecordingStates {
    val Active = setOf(RecordingState.STARTING, RecordingState.RECORDING)
}

private fun Float.validFrameScale(): Float =
    takeIf { it.isFinite() && it >= 1f } ?: 1f

