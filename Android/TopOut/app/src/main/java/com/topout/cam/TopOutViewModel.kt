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
                val state = _uiState.value
                cameraEngine.bind(
                    lifecycleOwner = owner,
                    previewView = previewView,
                    resolution = state.settings.resolution,
                    frameRate = state.settings.frameRate,
                    aspectRatio = state.settings.aspectRatio,
                    isLandscape = state.isLandscape,
                    analyzer = poseAnalyzer
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
                pendingRetakeRecording = false
                _uiState.update {
                    it.copy(errorMessage = error.localizedMessage ?: "카메라를 시작하지 못했습니다.")
                }
            }
        }
    }

    fun updateOrientation(isLandscape: Boolean) {
        _uiState.update { state ->
            state.copy(
                isLandscape = isLandscape,
                statusText = if (state.stage == CameraStage.PREVIEW) previewGuidance(state.settings) else state.statusText
            )
        }
        // Orientation changes recreate the keyed PreviewView in TopOutApp. Binding against the
        // new surface avoids one frame of the previous orientation/aspect leaking through.
    }

    fun updateTargetRotation(rotation: Int) = cameraEngine.updateTargetRotation(rotation)

    /**
     * Freezes exactly the camera composition currently visible in PreviewView.
     *
     * PreviewView already contains the selected aspect ratio, optical/digital zoom and CameraX
     * transform. Using a separately converted ImageAnalysis frame here can apply a second crop on
     * some devices and make the selection image look more zoomed than the live preview.
     */
    fun onReady(frameProvider: () -> Bitmap?, isLandscape: Boolean) {
        val current = _uiState.value
        if (current.stage != CameraStage.PREVIEW ||
            current.recordingState != RecordingState.IDLE ||
            current.isBusy
        ) return

        updateOrientation(isLandscape)
        val settings = _uiState.value.settings
        if (!settings.requiresTopHoldSelection && !settings.autoClipDetectionEnabled) {
            startRecordingWithoutSelection()
            return
        }

        // iOS leaves the existing camera guidance in place while the current frame is captured.
        // Only the loading indicator changes during this short transition.
        _uiState.update { it.copy(isBusy = true, statusText = previewGuidance(settings)) }

        viewModelScope.launch {
            // Let the locked-orientation frame finish rendering, then snapshot PreviewView itself.
            // This is the only source that is guaranteed to match what the user saw at the instant
            // Ready was pressed. Keep ImageAnalysis only as a rare fallback when the TextureView
            // has not produced a bitmap yet.
            delay(120L)
            // Use the TextureView-backed PreviewView bitmap as the single source for both the
            // selection UI and MobileSAM. MobileSAM resizes its input to 1024px internally, so a
            // separate low-resolution ImageAnalysis snapshot does not reduce inference cost enough
            // to justify displaying a softer YUV-converted frame or risking a frame-time mismatch.
            //
            // ImageAnalysis remains a fallback only for rare devices where PreviewView.bitmap is
            // temporarily unavailable.
            val previewSnapshot = runCatching {
                frameProvider()?.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull()
            val frame = previewSnapshot
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

            val mat = if (settings.autoClipDetectionEnabled) {
                val detected = mobileSam.detectMatBoundary(frame).getOrElse {
                    listOf(Offset(0.06f, TopOutConfig.MatDefaultY), Offset(0.94f, TopOutConfig.MatDefaultY))
                }
                val downwardOffset = TopOutConfig.MatDetectedLineDownwardOffsetPixels / frame.height.toFloat()
                detected.map { point ->
                    Offset(point.x, (point.y + downwardOffset).coerceIn(0f, 1f))
                }
            } else {
                emptyList()
            }

            selectionRequestId += 1
            _uiState.update { state ->
                state.copy(
                    stage = CameraStage.SELECT,
                    frozenFrame = frame,
                    matLine = mat,
                    holdMask = null,
                    isBusy = false,
                    statusText = selectionGuidance(state.settings)
                )
            }
            // detectMatBoundary already cached the embedding for this exact displayed bitmap. When
            // automatic mat detection is off, warm it once only if top-hold selection needs SAM.
            if (settings.requiresTopHoldSelection && !settings.autoClipDetectionEnabled) {
                viewModelScope.launch { holdSegmenter.warmUp(frame) }
            }
        }
    }

    fun onHoldTapped(normalizedPoint: Offset, selectionZoom: Float) {
        val state = _uiState.value
        if (state.stage != CameraStage.SELECT || state.isBusy || !state.settings.requiresTopHoldSelection) return
        val frame = state.frozenFrame ?: return
        val requestId = ++selectionRequestId
        _uiState.update { it.copy(isBusy = true, statusText = "탑홀드 분석 중...") }
        viewModelScope.launch {
            holdSegmenter.segment(frame, normalizedPoint, selectionZoom)
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
                .onFailure {
                    if (requestId != selectionRequestId) return@onFailure
                    _uiState.update { currentState ->
                        if (currentState.stage != CameraStage.SELECT) currentState.copy(isBusy = false)
                        else currentState.copy(
                            isBusy = false,
                            statusText = if (currentState.holdMask == null) {
                                "선택 실패 · 다시 탭해보세요"
                            } else {
                                "선택 실패 · 기존 선택을 유지합니다"
                            }
                        )
                    }
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
        // CameraEngine freezes the zoom at the optical boundary while another physical lens is
        // loading. Ignore UI updates too, so the displayed ratio cannot run ahead of the camera.
        if (currentState.lensSwitchInProgress) return
        val capabilities = currentState.capabilities
        val value = zoom.coerceIn(capabilities.minZoomRatio, capabilities.maxZoomRatio)
        _uiState.update { it.copy(zoomFactor = value) }
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

        // The mat line and optional hold mask are now self-contained. Drop the frozen RGB frame
        // and its MobileSAM embedding before the long recording begins to reduce steady-state RAM.
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
                        _uiState.update { it.copy(savingStatus = "사진 앱에 저장하는 중...") }
                    }
                )
            }.onSuccess {
                _uiState.update { it.copy(saving = false, savingStatus = "구간 선택된 동영상을 저장했습니다") }
            }.onFailure { error ->
                val prefix = if (savingToGallery) "사진 앱 저장 실패" else "구간 저장 실패"
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
                stage = if (state.settings.requiresTopHoldSelection) CameraStage.SELECT else CameraStage.PREVIEW,
                recordingState = RecordingState.IDLE,
                recordingCountdown = null,
                recordedVideo = null,
                detectedRanges = emptyList(),
                savingStatus = null,
                statusText = if (state.settings.requiresTopHoldSelection) {
                    "선택 완료 · 녹화를 다시 준비합니다"
                } else previewGuidance(state.settings)
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
