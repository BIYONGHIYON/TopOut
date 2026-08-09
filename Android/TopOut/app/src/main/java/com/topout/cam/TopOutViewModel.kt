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
import com.topout.cam.data.SettingsRepository
import com.topout.cam.detection.MotionEvent
import com.topout.cam.detection.MotionFinishReference
import com.topout.cam.detection.PoseClimbAnalyzer
import com.topout.cam.media.VideoExporter
import com.topout.cam.model.AppSettings
import com.topout.cam.model.CameraStage
import com.topout.cam.model.ClipRange
import com.topout.cam.model.ClipRangeNormalizer
import com.topout.cam.model.DetectedClipRangeStore
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
import kotlin.math.max
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
    private var latestLifecycleOwner: LifecycleOwner? = null
    private var latestPreviewView: PreviewView? = null
    private var pendingRetakeRecording = false
    private var activeDetectedStart: Double? = null
    private val completedRanges = DetectedClipRangeStore()
    private var activeRecordingSettings: AppSettings? = null
    private var activeRecordingLandscape = false
    private var activeRecordingTargetRotation = 0
    private var recordingAnalysisAvailable = true
    private var selectionRequestId = 0L

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
        cameraEngine.onRecordingProgress = { seconds ->
            _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
        }
        cameraEngine.onRecordingFinalized = { result ->
            viewModelScope.launch { finishRecording(result) }
        }
        cameraEngine.onZoomCorrected = { correctedZoom ->
            _uiState.update { it.copy(zoomFactor = correctedZoom) }
        }
        cameraEngine.onLensSwitchingChanged = { switching ->
            _uiState.update { state ->
                if (switching) {
                    state.copy(lensSwitchInProgress = true)
                } else {
                    state.copy(
                        lensSwitchInProgress = false,
                        lensSwitchSequence = state.lensSwitchSequence + 1
                    )
                }
            }
        }
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

        // Load MobileSAM while the brand/thumbnail screen is visible. TopOutApp does not create or
        // bind CameraX until launchVisible becomes false, so model loading and camera startup never
        // compete for CPU, memory bandwidth, or thermal headroom.
        viewModelScope.launch {
            val startedAt = System.nanoTime()
            mobileSam.prepare()
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
            val minimumLaunchMillis = 550L
            if (elapsedMillis < minimumLaunchMillis) delay(minimumLaunchMillis - elapsedMillis)
            _uiState.update { it.copy(launchVisible = false) }
        }
    }

    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView) {
        latestLifecycleOwner = owner
        latestPreviewView = previewView
        cameraBindingJob?.cancel()
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
                        errorMessage = null
                    )
                }
                if (normalized != previous) settingsRepository.save(normalized)
                cameraEngine.setZoom(zoom)
                val currentState = _uiState.value
                cameraEngine.setPreviewRenderingEnabled(
                    currentState.stage != CameraStage.RECORDING || currentState.livePreviewEnabled
                )

                if (pendingRetakeRecording) {
                    pendingRetakeRecording = false
                    startRecording()
                }
            }.onFailure { error ->
                // Rebinding intentionally cancels the previous camera job. Cancellation is a
                // normal control-flow event and must never be surfaced as an app error dialog.
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
                        errorMessage = error.localizedMessage ?: "카메라를 시작하지 못했습니다."
                    )
                }
            }
        }
    }

    fun updateOrientation(isLandscape: Boolean) {
        _uiState.update { state ->
            // Android 16 can ignore requestedOrientation on large screens. Once Ready is pressed,
            // keep the capture orientation immutable until selection/recording ends so Compose
            // window changes cannot recreate CameraPreview with a different ViewPort.
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
            current.lensSwitchInProgress
        ) return

        updateOrientation(isLandscape)
        // Freeze the exact display rotation used by the ready-time Preview/ViewPort. On Android 16
        // large displays the window may rotate even while requestedOrientation is ignored.
        _uiState.update { it.copy(cameraTargetRotation = targetRotation) }
        cameraEngine.updateTargetRotation(targetRotation)
        val settings = _uiState.value.settings
        if (!settings.requiresTopHoldSelection && !settings.autoClipDetectionEnabled) {
            startRecordingWithoutSelection()
            return
        }

        // When only top-hold selection is enabled there is nothing to freeze at Ready. Enter the
        // transparent live editor immediately; each hold tap captures its own fresh frame.
        if (!settings.autoClipDetectionEnabled) {
            selectionRequestId += 1
            mobileSam.releaseCachedEmbedding()
            _uiState.update { state ->
                state.copy(
                    stage = CameraStage.SELECT,
                    frozenFrame = null,
                    matLine = emptyList(),
                    holdMask = null,
                    isBusy = false,
                    statusText = selectionGuidance(state.settings)
                )
            }
            return
        }

        // Snapshot the displayed crop immediately at the Ready action. MobileSAM runs later, but
        // its mat-boundary input represents the composition the user actually confirmed.
        val readySnapshot = runCatching {
            frameProvider()?.copy(Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
        _uiState.update { it.copy(isBusy = true, statusText = "AI가 매트 경계를 분석하고 있어요") }

        viewModelScope.launch {
            val frame = readySnapshot
                ?: poseAnalyzer.captureNextFrame(TopOutConfig.CaptureMaxLongSide)

            if (frame == null || frame.width <= 0 || frame.height <= 0) {
                frame?.recycle()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusText = "현재 카메라 프레임을 캡처하지 못했습니다."
                    )
                }
                return@launch
            }

            val mat = try {
                mobileSam.detectMatBoundary(frame).getOrElse {
                    listOf(
                        Offset(0.06f, TopOutConfig.MatDefaultY),
                        Offset(0.94f, TopOutConfig.MatDefaultY)
                    )
                }
            } finally {
                // The Ready image must not leak into later hold taps. A new embedding is generated
                // from the frame captured at each tap instead.
                mobileSam.releaseCachedEmbedding()
                if (!frame.isRecycled) frame.recycle()
            }

            selectionRequestId += 1
            _uiState.update { state ->
                state.copy(
                    stage = CameraStage.SELECT,
                    frozenFrame = null,
                    matLine = mat,
                    holdMask = null,
                    isBusy = false,
                    statusText = selectionGuidance(state.settings)
                )
            }
        }
    }

    fun onHoldTapped(normalizedPoint: Offset) {
        val state = _uiState.value
        if (state.stage != CameraStage.SELECT ||
            state.isBusy ||
            state.lensSwitchInProgress ||
            !state.settings.requiresTopHoldSelection
        ) return

        val requestId = ++selectionRequestId
        _uiState.update {
            it.copy(
                holdMask = null,
                isBusy = true,
                statusText = "현재 프레임에서 탑홀드 분석 중..."
            )
        }

        viewModelScope.launch {
            // PreviewView.bitmap is the exact displayed crop including the current physical lens and
            // camera zoom. ImageAnalysis is retained only as a fallback for rare TextureView misses.
            val previewSnapshot = runCatching {
                latestPreviewView?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            val frame = previewSnapshot
                ?: poseAnalyzer.captureNextFrame(TopOutConfig.CaptureMaxLongSide)

            if (frame == null || frame.width <= 0 || frame.height <= 0) {
                frame?.recycle()
                if (requestId == selectionRequestId) {
                    _uiState.update {
                        it.copy(isBusy = false, statusText = "현재 카메라 프레임을 캡처하지 못했습니다. 다시 탭해주세요")
                    }
                }
                return@launch
            }

            try {
                holdSegmenter.segment(frame, normalizedPoint, 1f)
                    .onSuccess { mask ->
                        if (requestId != selectionRequestId) return@onSuccess
                        _uiState.update { currentState ->
                            if (currentState.stage != CameraStage.SELECT) currentState
                            else currentState.copy(
                                holdMask = mask,
                                isBusy = false,
                                statusText = "선택 완료 · 다시 탭해서 수정하거나 녹화 시작을 눌러주세요"
                            )
                        }
                    }
                    .onFailure { error ->
                        if (requestId != selectionRequestId) return@onFailure
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                statusText = "선택 실패 · 다시 탭해보세요",
                                errorMessage = error.localizedMessage
                            )
                        }
                    }
            } finally {
                mobileSam.releaseCachedEmbedding()
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    fun updateMatPoint(index: Int, point: Offset) {
        _uiState.update { state ->
            if (index !in state.matLine.indices) state
            else state.copy(
                matLine = state.matLine.toMutableList().also {
                    // iOS MatLineView only permits vertical correction; x stays detected.
                    it[index] = Offset(it[index].x, point.y.coerceIn(0f, 1f))
                }
            )
        }
    }

    fun setZoom(zoom: Float) {
        val currentState = _uiState.value
        // Camera composition is editable only before Ready. Selection and recording must keep the
        // exact lens/zoom FOV used to establish the mat boundary and subsequent hold masks.
        if (currentState.stage != CameraStage.PREVIEW ||
            currentState.recordingState != RecordingState.IDLE ||
            currentState.isBusy ||
            currentState.lensSwitchInProgress
        ) return
        val capabilities = currentState.capabilities
        val value = zoom.coerceIn(capabilities.minZoomRatio, capabilities.maxZoomRatio)
        _uiState.update { state -> state.copy(zoomFactor = value) }
        cameraEngine.setZoom(value)
    }

    fun retakeSelection() {
        selectionRequestId += 1
        cameraEngine.setPreviewRenderingEnabled(true)
        _uiState.update { state ->
            state.copy(
                stage = CameraStage.PREVIEW,
                frozenFrame = null,
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
        selectionRequestId += 1
        if (settings.requiresTopHoldSelection && state.holdMask == null) {
            setError("먼저 탑홀드를 선택해주세요.")
            return
        }
        if (settings.autoClipDetectionEnabled && state.matLine.isEmpty()) {
            setError("매트 경계선을 확인해주세요.")
            return
        }

        countdownJob?.cancel()
        completedRanges.clear()
        activeDetectedStart = null
        activeRecordingSettings = settings
        activeRecordingLandscape = state.isLandscape
        activeRecordingTargetRotation = state.cameraTargetRotation
        recordingAnalysisAvailable = cameraEngine.isAnalysisAvailable()
        if (settings.autoClipDetectionEnabled && !recordingAnalysisAvailable) {
            setError("이 기기에서는 현재 촬영 설정으로 자동 구간 탐지를 사용할 수 없습니다. 해상도 또는 프레임을 낮춘 뒤 다시 시도해주세요.")
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

        // The mat line and optional hold mask are self-contained. Drop any transient MobileSAM
        // embedding before the long recording begins to reduce steady-state RAM.
        mobileSam.releaseCachedEmbedding()
        _uiState.update {
            it.copy(
                stage = CameraStage.RECORDING,
                recordingState = RecordingState.STARTING,
                frozenFrame = null,
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
            cameraEngine.startRecording(includeAudio = true)
        }
    }

    private fun startRecordingWithoutSelection() {
        _uiState.update {
            it.copy(frozenFrame = null, holdMask = null, matLine = emptyList())
        }
        startRecording()
    }

    fun stopRecording(notice: RecordingStopNotice? = null) {
        val recordingState = _uiState.value.recordingState
        if (recordingState !in setOf(RecordingState.STARTING, RecordingState.RECORDING)) return
        countdownJob?.cancel()
        storageJob?.cancel()

        if (recordingState == RecordingState.STARTING) {
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
                        "선택 완료 · 다시 탭해서 수정하거나 녹화 시작을 눌러주세요"
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
                statusText = "영상 정리 중..."
            )
        }
        cameraEngine.stopRecording(notice)
    }

    fun onAppBackgrounded() {
        if (_uiState.value.recordingState in setOf(RecordingState.STARTING, RecordingState.RECORDING)) {
            stopRecording(RecordingStopNotice.Background)
        }
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
            // Camera-session changes recreate PreviewView from Compose. Its onPreviewReady callback
            // performs the rebind against the correctly sized surface instead of briefly binding
            // the old 9:16 surface and leaving an OEM SurfaceView artifact.
        }
    }

    fun saveSelectedClip(startSeconds: Double, endSeconds: Double, includeOverlay: Boolean) {
        val video = _uiState.value.recordedVideo ?: return
        if (_uiState.value.saving) return
        _uiState.update {
            it.copy(
                saving = true,
                savingStatus = if (includeOverlay) "탑홀드 마스크를 입히는 중..." else "선택 구간 자르는 중..."
            )
        }
        viewModelScope.launch {
            var savingToGallery = false
            runCatching {
                videoExporter.exportToGallery(
                    inputUri = video.uri,
                    startSeconds = startSeconds,
                    endSeconds = endSeconds,
                    overlayBitmap = video.overlayBitmap,
                    includeOverlay = includeOverlay,
                    onSavingToGallery = {
                        savingToGallery = true
                        _uiState.update { it.copy(savingStatus = "사진 앱에 저장하고 확인하는 중...") }
                    }
                )
            }.onSuccess { savedUri ->
                _uiState.update {
                    it.copy(
                        saving = false,
                        savingStatus = "✓ 갤러리에 저장된 영상을 확인했습니다",
                        savedGalleryVideo = SavedGalleryVideo(
                            uri = savedUri,
                            startSeconds = startSeconds,
                            endSeconds = endSeconds,
                            includeOverlay = includeOverlay
                        )
                    )
                }
            }.onFailure { error ->
                val prefix = if (savingToGallery) "사진 앱 저장/확인 실패" else "구간 저장 실패"
                _uiState.update {
                    it.copy(
                        saving = false,
                        savingStatus = "$prefix: ${error.localizedMessage ?: "알 수 없는 오류"}"
                    )
                }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    /** Close discards both the review file and selection, matching ReviewVC's close action. */
    fun closeReview() {
        selectionRequestId += 1
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
        selectionRequestId += 1
        cameraEngine.setPreviewRenderingEnabled(true)
        deleteCurrentRecording()
        completedRanges.clear()
        activeDetectedStart = null
        pendingRetakeRecording = true
        _uiState.update { state ->
            state.copy(
                // Leave Review directly into a capture-locked stage. Android 16 may ignore
                // requestedOrientation on large displays; using RECORDING here keeps AspectFrame,
                // CameraPreview and the preserved normalized mat/hold coordinates on the exact
                // orientation/FOV of the previous take while the camera session is rebound.
                stage = CameraStage.RECORDING,
                recordingState = RecordingState.IDLE,
                recordingCountdown = null,
                recordedVideo = null,
                detectedRanges = emptyList(),
                savingStatus = null,
                savedGalleryVideo = null,
                // Review may rotate independently. Retake must restore the exact orientation used
                // for the saved mat/hold normalized coordinates, otherwise the preserved boundary
                // would be drawn over a different camera crop.
                isLandscape = activeRecordingLandscape,
                cameraTargetRotation = activeRecordingTargetRotation,
                statusText = "기존 매트 경계를 유지하여 녹화를 다시 준비합니다"
            )
        }
        // The camera composable is recreated on leaving review; bindCamera starts the retake.
    }

    private suspend fun finishRecording(result: Result<CameraEngine.FinalizedRecording>) {
        storageJob?.cancel()
        // stopRecording() already closes the open detected range before encoder finalization.
        poseAnalyzer.stop()
        cameraEngine.setPreviewRenderingEnabled(true)
        cameraEngine.unbind()

        result.onFailure { error ->
            _uiState.update { state ->
                state.copy(
                    stage = if (state.settings.requiresTopHoldSelection && state.holdMask != null) CameraStage.SELECT else CameraStage.PREVIEW,
                    recordingState = RecordingState.IDLE,
                    errorMessage = error.localizedMessage ?: "녹화를 완료하지 못했습니다.",
                    statusText = if (state.holdMask != null) {
                        "선택 완료 · 다시 탭해서 수정하거나 녹화 시작을 눌러주세요"
                    } else previewGuidance(state.settings)
                )
            }
            rebindLatestCamera()
        }.onSuccess { finalized ->
            val settings = activeRecordingSettings ?: _uiState.value.settings
            val readyFile = finalized.file
            val duration = max(0.1, videoExporter.durationSeconds(readyFile))
            val ranges = normalizeDetectedClipRanges(
                input = completedRanges.snapshot().mapNotNull { range ->
                    val startSeconds = range.startSeconds.coerceIn(0.0, duration)
                    val endSeconds = range.endSeconds.coerceIn(0.0, duration)
                    if (endSeconds > startSeconds) {
                        // completedRanges contains raw, unpadded detection boundaries.
                        ClipRange(startSeconds, endSeconds)
                    } else null
                },
                recordingDurationSeconds = duration
            )
            val defaultRange = ranges.lastOrNull() ?: ClipRange(0.0, duration)
            val encodedSize = videoExporter.videoDimensions(readyFile)
            val fallbackSize = settings.aspectRatio.outputSize(
                settings.resolution,
                activeRecordingLandscape
            )
            val overlayWidth = encodedSize?.first ?: fallbackSize.width
            val overlayHeight = encodedSize?.second ?: fallbackSize.height
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
                    savedGalleryVideo = null,
                    savingStatus = null,
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
                        "저장할 구간을 직접 선택해주세요"
                    } else {
                        "자동 감지된 시작~완등 구간을 확인해주세요"
                    }
                )
            }

        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        viewModelScope.launch {
            if (_uiState.value.recordingState !in setOf(RecordingState.RECORDING, RecordingState.FINALIZING)) return@launch
            when (event) {
                is MotionEvent.Start -> {
                    // Store only the raw pose-detected start. Padding is added after final cleanup.
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
                            statusText = "구간 저장됨 · 다음 등반 대기"
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
        // Keep every detected range. Finalization removes isolated short false positives, but valid climbs are never discarded
        // merely because the recording contains many sections.
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
        val owner = latestLifecycleOwner ?: return
        val preview = latestPreviewView ?: return
        if (_uiState.value.stage == CameraStage.REVIEW) return
        bindCamera(owner, preview)
    }

    private fun deleteCurrentRecording() {
        _uiState.value.recordedVideo?.uri?.path?.let { File(it).delete() }
    }

    private fun setError(message: String) = _uiState.update { it.copy(errorMessage = message) }

    override fun onCleared() {
        storageJob?.cancel()
        countdownJob?.cancel()
        cameraBindingJob?.cancel()
        poseAnalyzer.stop()
        poseAnalyzer.close()
        cameraEngine.release()
        mobileSam.close()
        super.onCleared()
    }
}
