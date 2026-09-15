package com.topout.cam

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
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
import com.topout.cam.model.HoldMask
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
import com.topout.cam.util.createHoldOverlayBitmap
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private var matBoundaryTimeoutJob: Job? = null
    private var topHoldAnalysisTimeoutJob: Job? = null
    private var selectionAnalysisJob: Job? = null
    private var automaticRecordingStartJob: Job? = null
    // 화면 재생성 뒤 이전 UI가 남지 않도록 생명주기 객체는 약한 참조로 보관한다.
    private var latestLifecycleOwner = WeakReference<LifecycleOwner>(null)
    private var latestPreviewView = WeakReference<PreviewView>(null)
    private var pendingRetakeRecording = false
    private var activeDetectedStart: Double? = null
    private val completedRanges = DetectedClipRangeStore()
    private var activeRecordingSettings: AppSettings? = null
    private var activeRecordingLandscape = false
    private var activeRecordingTargetRotation = 0
    private var recordingAnalysisAvailable = true
    private var selectionRequestId = 0L
    private var ownedRecordingFile: File? = null
    private var appInForeground = false
    private var automaticRecordingWarningVisible = false

    init {
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
                    val status = if (state.stage == CameraStage.PREVIEW) previewGuidance(normalized) else state.statusText
                    state.copy(settings = normalized, statusText = status)
                }
            }
        }

        // 앱 진입을 막지 않고 카메라 준비 뒤 MobileSAM을 예열한다.
        viewModelScope.launch {
            delay(IN_APP_LAUNCH_DELAY_MILLIS)
            _uiState.update { it.copy(launchVisible = false) }
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
                val captureLocked = state.isBusy ||
                    state.stage == CameraStage.SELECT || state.stage == CameraStage.RECORDING
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
                        errorMessage = null
                    )
                }
                if (normalized != previous) settingsRepository.save(normalized)
                cameraEngine.setZoom(zoom)
                preloadMobileSamAfterCameraStarts()
                val currentState = _uiState.value
                cameraEngine.setPreviewRenderingEnabled(
                    currentState.stage != CameraStage.RECORDING || currentState.livePreviewEnabled
                )

                if (pendingRetakeRecording) {
                    pendingRetakeRecording = false
                    startRecording()
                } else {
                    scheduleAutomaticRecordingStartIfEligible()
                }
            }.onFailure { error ->
                // 재연결에 따른 이전 작업 취소는 오류로 표시하지 않는다.
                if (error is CancellationException) return@onFailure
                val wasPendingRetake = pendingRetakeRecording
                pendingRetakeRecording = false
                _uiState.update { state ->
                    val recoveryStage = if (wasPendingRetake) {
                        if (state.settings.requiresTopHoldSelection && state.holdMask != null) {
                            CameraStage.SELECT
                        } else {
                            CameraStage.PREVIEW
                        }
                    } else {
                        state.stage
                    }
                    state.copy(
                        stage = recoveryStage,
                        cameraReady = false,
                        errorMessage = tr("error.camera_start")
                    )
                }
            }
        }
    }

    fun updateOrientation(isLandscape: Boolean) {
        _uiState.update { state ->
            // 준비 완료 후 선택과 녹화가 끝날 때까지 촬영 방향을 유지한다.
            if (state.isBusy || state.stage in setOf(CameraStage.SELECT, CameraStage.RECORDING)) {
                state
            } else {
                state.copy(
                    isLandscape = isLandscape,
                    statusText = if (state.stage == CameraStage.PREVIEW) previewGuidance(state.settings) else state.statusText
                )
            }
        }
    }

    fun updateTargetRotation(rotation: Int) {
        val state = _uiState.value
        if (state.isBusy || state.stage in setOf(CameraStage.SELECT, CameraStage.RECORDING)) return
        _uiState.update { it.copy(cameraTargetRotation = rotation) }
        cameraEngine.updateTargetRotation(rotation)
    }

    /**
     * Ready uses one current camera frame only to establish the automatic mat boundary.
     * The captured bitmap is never displayed; selection continues over the live PreviewView.
     */
    fun onReady(frameProvider: () -> Bitmap?, isLandscape: Boolean, targetRotation: Int) {
        val current = _uiState.value
        if (current.stage != CameraStage.PREVIEW ||
            current.recordingState != RecordingState.IDLE ||
            current.isBusy ||
            !current.cameraReady ||
            current.lensTransition.inProgress
        ) return

        updateOrientation(isLandscape)
        // 준비 완료 시점의 미리보기 회전값을 촬영 종료까지 유지한다.
        _uiState.update { it.copy(cameraTargetRotation = targetRotation) }
        cameraEngine.updateTargetRotation(targetRotation)
        val settings = _uiState.value.settings
        if (!settings.requiresTopHoldSelection && !settings.autoClipDetectionEnabled) {
            startRecordingWithoutSelection()
            return
        }

        // 탑홀드만 선택할 때는 바로 편집 화면으로 이동하고 탭마다 새 프레임을 사용한다.
        if (!settings.autoClipDetectionEnabled) {
            invalidateSelectionRequest()
            mobileSam.releaseCachedEmbedding()
            _uiState.update { state ->
                state.copy(
                    stage = CameraStage.SELECT,
                    matLine = emptyList(),
                    holdMask = null,
                    isBusy = false,
                    statusText = selectionGuidance(state.settings)
                )
            }
            return
        }

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
        cancelAutomaticRecordingStart()
        invalidateSelectionRequest()
        return selectionRequestId
    }

    private fun invalidateSelectionRequest() {
        matBoundaryTimeoutJob?.cancel()
        matBoundaryTimeoutJob = null
        topHoldAnalysisTimeoutJob?.cancel()
        topHoldAnalysisTimeoutJob = null
        selectionAnalysisJob?.cancel()
        selectionAnalysisJob = null
        selectionRequestId += 1
    }

    private fun finishSelectionRequest(requestId: Long): Boolean {
        if (requestId != selectionRequestId) return false
        matBoundaryTimeoutJob?.cancel()
        matBoundaryTimeoutJob = null
        topHoldAnalysisTimeoutJob?.cancel()
        topHoldAnalysisTimeoutJob = null
        selectionAnalysisJob = null
        selectionRequestId += 1
        return true
    }

    private fun cancelSelectionAnalysis(requestId: Long) {
        if (requestId != selectionRequestId) return
        selectionAnalysisJob?.cancel()
        selectionAnalysisJob = null
    }

    private fun defaultMatLine(): List<Offset> = listOf(
        Offset(0.06f, TopOutConfig.MatDefaultY),
        Offset(0.94f, TopOutConfig.MatDefaultY)
    )

    private fun completeMatBoundaryAnalysis(requestId: Long, matLine: List<Offset>) {
        if (!finishSelectionRequest(requestId)) return
        _uiState.update { state ->
            state.copy(
                stage = CameraStage.SELECT,
                matLine = matLine,
                holdMask = null,
                isBusy = false,
                statusText = selectionGuidance(state.settings)
            )
        }
        scheduleAutomaticRecordingStartIfEligible()
    }

    fun onHoldTapped(normalizedPoint: Offset) {
        val state = _uiState.value
        if (state.stage != CameraStage.SELECT ||
            state.isBusy ||
            state.lensTransition.inProgress ||
            !state.settings.requiresTopHoldSelection
        ) return

        val requestId = beginSelectionRequest()
        _uiState.update {
            it.copy(
                holdMask = null,
                isBusy = true,
                statusText = tr("camera.analyzing_hold")
            )
        }

        selectionAnalysisJob = viewModelScope.launch {
            // 실제 표시 화면을 우선 사용하고 캡처 실패 시 분석 프레임을 사용한다.
            val previewSnapshot = runCatching {
                latestPreviewView.get()?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            val frame = previewSnapshot
                ?: poseAnalyzer.captureNextFrame(TopOutConfig.CaptureMaxLongSide)

            if (frame == null || frame.width <= 0 || frame.height <= 0) {
                frame?.recycle()
                if (finishSelectionRequest(requestId)) {
                    _uiState.update {
                        it.copy(isBusy = false, statusText = tr("camera.capture_failed_retry"))
                    }
                }
                return@launch
            }

            val fallbackMask = mobileSam.fallbackHoldMask(frame, normalizedPoint)
            topHoldAnalysisTimeoutJob = viewModelScope.launch {
                delay(TopOutConfig.TopHoldAnalysisTimeoutMillis)
                cancelSelectionAnalysis(requestId)
                completeTopHoldAnalysis(requestId, fallbackMask)
            }

            try {
                holdSegmenter.segment(frame, normalizedPoint, 1f)
                    .onSuccess { mask ->
                        completeTopHoldAnalysis(requestId, mask)
                    }
                    .onFailure {
                        completeTopHoldAnalysis(requestId, fallbackMask)
                    }
            } finally {
                mobileSam.releaseCachedEmbedding()
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    private fun completeTopHoldAnalysis(requestId: Long, mask: HoldMask) {
        if (!finishSelectionRequest(requestId)) return
        _uiState.update { currentState ->
            if (currentState.stage != CameraStage.SELECT) currentState
            else currentState.copy(
                holdMask = mask,
                isBusy = false,
                statusText = tr("camera.selection_complete"),
                errorMessage = null
            )
        }
        scheduleAutomaticRecordingStartIfEligible()
    }

    private fun isAutomaticRecordingStartEligible(state: TopOutUiState): Boolean {
        if (state.stage != CameraStage.SELECT ||
            state.recordingState != RecordingState.IDLE ||
            state.isBusy ||
            !state.cameraReady ||
            !appInForeground
        ) return false

        val settings = state.settings
        val hasRequiredBoundary = !settings.autoClipDetectionEnabled || state.matLine.size >= 2
        val hasRequiredTopHold = !settings.requiresTopHoldSelection || state.holdMask != null
        return (settings.autoClipDetectionEnabled || settings.requiresTopHoldSelection) &&
            hasRequiredBoundary && hasRequiredTopHold
    }

    private fun scheduleAutomaticRecordingStartIfEligible() {
        if (!isAutomaticRecordingStartEligible(_uiState.value)) return
        if (automaticRecordingStartJob?.isActive == true) return

        automaticRecordingWarningVisible = false
        _uiState.update { state ->
            if (isAutomaticRecordingStartEligible(state)) {
                state.copy(statusText = completedSelectionGuidance(state))
            } else state
        }
        automaticRecordingStartJob = viewModelScope.launch {
            delay(
                TopOutConfig.AutomaticRecordingStartDelayMillis -
                    TopOutConfig.AutomaticRecordingWarningLeadMillis
            )
            if (!isAutomaticRecordingStartEligible(_uiState.value)) {
                automaticRecordingStartJob = null
                return@launch
            }
            automaticRecordingWarningVisible = true
            _uiState.update { state ->
                if (isAutomaticRecordingStartEligible(state)) {
                    state.copy(statusText = tr("camera.auto_recording_soon"))
                } else state
            }
            delay(TopOutConfig.AutomaticRecordingWarningLeadMillis)
            automaticRecordingWarningVisible = false
            automaticRecordingStartJob = null
            if (isAutomaticRecordingStartEligible(_uiState.value)) startRecording()
        }
    }

    private fun cancelAutomaticRecordingStart() {
        automaticRecordingStartJob?.cancel()
        automaticRecordingStartJob = null
        automaticRecordingWarningVisible = false
    }

    private fun completedSelectionGuidance(state: TopOutUiState): String =
        if (state.settings.requiresTopHoldSelection && state.holdMask != null) {
            tr("camera.selection_complete")
        } else {
            selectionGuidance(state.settings)
        }

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

    fun setZoom(zoom: Float) {
        val currentState = _uiState.value
        // 선택과 녹화 중에는 경계와 마스크를 만든 카메라 화각을 유지한다.
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

    fun retakeSelection() {
        cancelAutomaticRecordingStart()
        invalidateSelectionRequest()
        cameraEngine.setPreviewRenderingEnabled(true)
        _uiState.update { state ->
            state.copy(
                stage = CameraStage.PREVIEW,
                holdMask = null,
                matLine = emptyList(),
                isBusy = false,
                statusText = previewGuidance(state.settings)
            )
        }
    }

    fun startRecording() {
        val state = _uiState.value
        val settings = state.settings
        if (state.recordingState != RecordingState.IDLE || state.isBusy) return
        cancelAutomaticRecordingStart()
        invalidateSelectionRequest()
        if (settings.requiresTopHoldSelection && state.holdMask == null) {
            setError(tr("error.select_hold_first"))
            return
        }
        if (settings.autoClipDetectionEnabled && state.matLine.isEmpty()) {
            setError(tr("error.check_mat_boundary"))
            return
        }
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
            val hold = state.holdMask
            poseAnalyzer.configure(
                matLine = state.matLine,
                topBox = hold?.normalizedBounds,
                finishReference = if (settings.topHoldMaskingEnabled) {
                    MotionFinishReference.CLOSEST_TOP_HOLD
                } else {
                    MotionFinishReference.HIGHEST_POSE_POINT
                }
            )
            poseAnalyzer.resetForRecording()
        } else {
            poseAnalyzer.stop()
        }

        // 녹화 전 임베딩만 해제하고 재촬영을 위해 모델 세션은 유지한다.
        mobileSam.releaseCachedEmbedding()
        _uiState.update {
            it.copy(
                stage = CameraStage.RECORDING,
                recordingState = RecordingState.STARTING,
                recordingElapsedSeconds = 0.0,
                recordingCountdown = TopOutConfig.RecordingCountdownSeconds.toInt(),
                detectedRanges = emptyList(),
                isBusy = false,
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

    private fun startRecordingWithoutSelection() {
        _uiState.update {
            it.copy(holdMask = null, matLine = emptyList())
        }
        startRecording()
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
                val returnStage = if (state.settings.requiresTopHoldSelection && state.holdMask != null) {
                    CameraStage.SELECT
                } else CameraStage.PREVIEW
                state.copy(
                    stage = returnStage,
                    recordingState = RecordingState.IDLE,
                    recordingCountdown = null,
                    livePreviewEnabled = true,
                    statusText = if (returnStage == CameraStage.SELECT) {
                        tr("camera.selection_complete")
                    } else previewGuidance(state.settings)
                )
            }
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
        cancelAutomaticRecordingStart()
        if (_uiState.value.recordingState in setOf(RecordingState.STARTING, RecordingState.RECORDING)) {
            stopRecording(RecordingStopNotice.Background)
        }
    }

    fun onAppForegrounded() {
        appInForeground = true
        scheduleAutomaticRecordingStartIfEligible()
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
                CameraStage.PREVIEW -> previewGuidance(state.settings)
                CameraStage.SELECT -> if (automaticRecordingWarningVisible) {
                    tr("camera.auto_recording_soon")
                } else {
                    completedSelectionGuidance(state)
                }
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
                    settingsVisible = false,
                    statusText = if (state.stage == CameraStage.PREVIEW) previewGuidance(normalized) else state.statusText
                )
            }
            val updated = _uiState.value

            val cameraSessionChanged = before.aspectRatio != normalized.aspectRatio ||
                before.resolution != normalized.resolution ||
                before.frameRate != normalized.frameRate
            if (!cameraSessionChanged &&
                updated.recordingState == RecordingState.IDLE &&
                updated.stage in setOf(CameraStage.PREVIEW, CameraStage.SELECT)
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

    /** Close discards both the review file and selection, matching ReviewVC's close action. */
    fun closeReview() {
        invalidateSelectionRequest()
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

    /** Retake preserves the selected top hold/mat and starts the countdown again automatically. */
    fun retakeFromReview() {
        invalidateSelectionRequest()
        cameraEngine.setPreviewRenderingEnabled(true)
        deleteCurrentRecording()
        completedRanges.clear()
        activeDetectedStart = null
        pendingRetakeRecording = true
        _uiState.update { state ->
            state.copy(
                // 재촬영은 이전 촬영 방향과 정규화 좌표를 유지한 채 카메라를 다시 연결한다.
                stage = CameraStage.RECORDING,
                recordingState = RecordingState.IDLE,
                recordingCountdown = null,
                recordedVideo = null,
                detectedRanges = emptyList(),
                savingStatus = null,
                savingStatusSelection = null,
                savedGalleryVideos = emptyList(),
                // 결과 화면에서 회전했어도 재촬영 시 기존 촬영 방향을 복구한다.
                isLandscape = activeRecordingLandscape,
                cameraTargetRotation = activeRecordingTargetRotation,
                statusText = tr("camera.preparing")
            )
        }
        // 결과 화면을 닫은 뒤 새 카메라 화면의 연결 콜백이 재촬영을 시작한다.
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
            val overlay = if (settings.topHoldMaskingEnabled) {
                createHoldOverlayBitmap(overlayWidth, overlayHeight, _uiState.value.holdMask)
            } else null

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
                        overlayBitmap = overlay,
                        aspectRatio = settings.aspectRatio,
                        automaticStopNotice = finalized.notice
                    ),
                    statusText = if (ranges.isEmpty()) {
                        tr("review.manual_prompt")
                    } else {
                        tr("review.auto_prompt")
                    }
                )
            }

        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            recoverAfterRecordingFinalizationFailure()
        }
    }

    private fun recoverAfterRecordingFinalizationFailure() {
        _uiState.update { state ->
            state.copy(
                stage = if (state.settings.requiresTopHoldSelection && state.holdMask != null) {
                    CameraStage.SELECT
                } else {
                    CameraStage.PREVIEW
                },
                recordingState = RecordingState.IDLE,
                errorMessage = tr("error.recording_finish"),
                statusText = if (state.holdMask != null) {
                    tr("camera.selection_complete")
                } else {
                    previewGuidance(state.settings)
                }
            )
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
                    val settings = activeRecordingSettings ?: _uiState.value.settings
                    _uiState.update {
                        it.copy(statusText = TopOutCopy.climbingStatus(settings))
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

    private fun previewGuidance(settings: AppSettings): String =
        TopOutCopy.previewGuidance(settings)

    private fun selectionGuidance(settings: AppSettings): String =
        TopOutCopy.selectionGuidance(settings)

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
        matBoundaryTimeoutJob?.cancel()
        topHoldAnalysisTimeoutJob?.cancel()
        selectionAnalysisJob?.cancel()
        automaticRecordingStartJob?.cancel()
        deleteCurrentRecording()
        poseAnalyzer.stop()
        cameraEngine.release(onAnalysisDrained = poseAnalyzer::close)
        mobileSam.close()
        latestLifecycleOwner.clear()
        latestPreviewView.clear()
        super.onCleared()
    }
}

private fun Float.validFrameScale(): Float =
    takeIf { it.isFinite() && it >= 1f } ?: 1f

private const val IN_APP_LAUNCH_DELAY_MILLIS = 550L
