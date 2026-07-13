package com.topout.cam

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topout.cam.camera.MockVideoEngine
import com.topout.cam.data.SettingsRepository
import com.topout.cam.detection.MatBoundaryDetector
import com.topout.cam.detection.MotionEvent
import com.topout.cam.detection.MotionFinishReference
import com.topout.cam.detection.PersonClimbAnalyzer
import com.topout.cam.media.VideoExporter
import com.topout.cam.model.AppSettings
import com.topout.cam.model.CameraStage
import com.topout.cam.model.ClipRange
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
import androidx.media3.ui.PlayerView
import com.topout.cam.util.createHoldOverlayBitmap
import java.io.File
import kotlin.math.abs
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
    private val videoExporter = VideoExporter(application)
    private val holdSegmenter: HoldSegmenter = OnnxMobileSamSegmenter(application)
    private val personAnalyzer = PersonClimbAnalyzer(application, onEvent = ::handleMotionEvent)
    private val mockVideoEngine = MockVideoEngine(application, personAnalyzer)

    private val _uiState = MutableStateFlow(TopOutUiState())
    val uiState: StateFlow<TopOutUiState> = _uiState.asStateFlow()

    private var storageJob: Job? = null
    private var countdownJob: Job? = null
    private var cameraBindingJob: Job? = null
    private var latestPreviewView: PlayerView? = null
    private var pendingRetakeRecording = false
    private var activeDetectedStart: Double? = null
    private val completedRanges = mutableListOf<ClipRange>()
    private var activeRecordingSettings: AppSettings? = null
    private var activeRecordingLandscape = false
    private var recordingAnalysisAvailable = true
    private var selectionRequestId = 0L

    init {
        mockVideoEngine.onRecordingStarted = {
            val settings = activeRecordingSettings ?: _uiState.value.settings
            val canAnalyze = settings.autoClipDetectionEnabled && recordingAnalysisAvailable
            if (canAnalyze) personAnalyzer.markRecordingStarted() else personAnalyzer.stop()
            _uiState.update { state ->
                state.copy(
                    recordingState = RecordingState.RECORDING,
                    recordingCountdown = null,
                    statusText = activeRecordingStatusText(settings)
                )
            }
            startStorageMonitoring()
        }
        mockVideoEngine.onRecordingProgress = { seconds ->
            _uiState.update { it.copy(recordingElapsedSeconds = seconds) }
        }
        mockVideoEngine.onRecordingFinalized = { result ->
            viewModelScope.launch { finishRecording(result) }
        }
        viewModelScope.launch {
            runCatching { mockVideoEngine.loadBundledSourceIfPresent() }
                .onSuccess { info ->
                    if (info != null) applyMockSourceInfo(info)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.localizedMessage ?: "번들 test.MOV를 열지 못했습니다.")
                    }
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

        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            holdSegmenter.prepare()
                .onSuccess {
                    val remaining = 550L - (SystemClock.elapsedRealtime() - startedAt)
                    if (remaining > 0L) delay(remaining)
                    _uiState.update { it.copy(modelReady = true, launchVisible = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            modelReady = false,
                            launchVisible = false,
                            errorMessage = error.localizedMessage ?: "MobileSAM 모델을 준비하지 못했습니다."
                        )
                    }
                }
        }
    }

    fun bindMockVideo(previewView: PlayerView) {
        latestPreviewView = previewView
        cameraBindingJob?.cancel()
        cameraBindingJob = viewModelScope.launch {
            runCatching {
                mockVideoEngine.bind(
                    previewView,
                    _uiState.value.settings.aspectRatio.widthOverHeight(_uiState.value.isLandscape)
                )
            }
                .onSuccess { capabilities ->
                    recordingAnalysisAvailable = mockVideoEngine.isAnalysisAvailable()
                    val previous = _uiState.value.settings
                    val normalized = normalizeSettings(
                        previous,
                        _uiState.value.copy(capabilities = capabilities)
                    )
                    _uiState.update {
                        it.copy(
                            capabilities = capabilities,
                            settings = normalized,
                            zoomFactor = 1f,
                            errorMessage = null
                        )
                    }
                    if (normalized != previous) settingsRepository.save(normalized)
                    val currentState = _uiState.value
                    mockVideoEngine.setPreviewRenderingEnabled(
                        currentState.stage != CameraStage.RECORDING || currentState.livePreviewEnabled
                    )

                    if (pendingRetakeRecording) {
                        pendingRetakeRecording = false
                        startRecording()
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    pendingRetakeRecording = false
                    _uiState.update {
                        it.copy(errorMessage = error.localizedMessage ?: "Mock 동영상을 시작하지 못했습니다.")
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
        // Orientation changes recreate the keyed mock player surface in TopOutApp.
    }

    fun updateTargetRotation(rotation: Int) = mockVideoEngine.updateTargetRotation(rotation)

    /** Freezes the test video's first frame so selection and looped recording share one origin. */
    fun onReady(isLandscape: Boolean) {
        val current = _uiState.value
        if (current.stage != CameraStage.PREVIEW ||
            current.recordingState != RecordingState.IDLE ||
            current.isBusy
        ) return
        if (!current.mockSourceReady) {
            setError("번들 테스트 영상을 불러오지 못했습니다.")
            return
        }

        updateOrientation(isLandscape)
        val settings = _uiState.value.settings
        if (!settings.requiresTopHoldSelection && !settings.autoClipDetectionEnabled) {
            startRecordingWithoutSelection()
            return
        }

        _uiState.update { it.copy(isBusy = true, statusText = previewGuidance(settings)) }
        viewModelScope.launch {
            val frame = runCatching {
                mockVideoEngine.captureCurrentFrame(TopOutConfig.CaptureMaxLongSide)
            }.getOrNull()
            if (frame == null || frame.width <= 0 || frame.height <= 0) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusText = "Mock 동영상 프레임을 캡처하지 못했습니다."
                    )
                }
                return@launch
            }

            val mat = if (settings.autoClipDetectionEnabled) {
                MatBoundaryDetector.detect(frame)
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
            if (settings.requiresTopHoldSelection) {
                viewModelScope.launch { holdSegmenter.warmUp(frame) }
            }
        }
    }

    fun onHoldTapped(normalizedPoint: Offset, selectionZoom: Float) {
        val state = _uiState.value
        if (state.stage != CameraStage.SELECT || state.isBusy || !state.settings.requiresTopHoldSelection) return
        if (!state.modelReady) {
            setError("MobileSAM 모델이 아직 준비되지 않았습니다.")
            return
        }
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
        mockVideoEngine.setZoom(value)
    }

    fun retakeSelection() {
        selectionRequestId += 1
        mockVideoEngine.setPreviewRenderingEnabled(true)
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
        if (!state.mockSourceReady) {
            setError("번들 테스트 영상을 불러오지 못했습니다.")
            return
        }
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
        recordingAnalysisAvailable = mockVideoEngine.isAnalysisAvailable()
        if (settings.autoClipDetectionEnabled && !recordingAnalysisAvailable) {
            setError("번들 테스트 영상 분석 소스가 준비되지 않았습니다.")
            return
        }

        if (settings.autoClipDetectionEnabled) {
            val hold = state.holdMask
            personAnalyzer.configure(
                matLine = state.matLine,
                topBox = hold?.normalizedBounds,
                finishReference = if (settings.topHoldMaskingEnabled) {
                    MotionFinishReference.CLOSEST_TOP_HOLD
                } else {
                    MotionFinishReference.HIGHEST_POSE_POINT
                },
                referenceImage = state.frozenFrame,
                horizontalBinCount = settings.aspectRatio.adaptiveHorizontalBinCount(
                    TopOutConfig.MatBoundaryBins,
                    state.isLandscape
                )
            )
            personAnalyzer.resetForRecording()
        } else {
            personAnalyzer.stop()
        }
        mockVideoEngine.beginCalibration()

        _uiState.update {
            it.copy(
                stage = CameraStage.RECORDING,
                recordingState = RecordingState.STARTING,
                recordingElapsedSeconds = 0.0,
                recordingCountdown = TopOutConfig.MotionCountdownSeconds.toInt(),
                detectedRanges = emptyList(),
                isBusy = false,
                livePreviewEnabled = settings.livePreviewDuringRecordingEnabled,
                statusText = recordingCountdownStatusText(settings)
            )
        }
        mockVideoEngine.setPreviewRenderingEnabled(settings.livePreviewDuringRecordingEnabled)

        countdownJob = viewModelScope.launch {
            for (remaining in TopOutConfig.MotionCountdownSeconds.toInt() downTo 1) {
                _uiState.update {
                    it.copy(
                        recordingCountdown = remaining,
                        statusText = recordingCountdownStatusText(settings, remaining)
                    )
                }
                delay(1_000)
            }
            mockVideoEngine.startRecording(includeAudio = false)
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
            personAnalyzer.stop()
            mockVideoEngine.cancelCalibration()
            mockVideoEngine.setPreviewRenderingEnabled(true)
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

        personAnalyzer.stop()?.let(::appendDetectedRange)
        _uiState.update {
            it.copy(
                recordingState = RecordingState.FINALIZING,
                recordingCountdown = null,
                statusText = "영상 정리 중..."
            )
        }
        mockVideoEngine.stopRecording(notice)
    }

    fun onAppBackgrounded() {
        if (_uiState.value.recordingState in setOf(RecordingState.STARTING, RecordingState.RECORDING)) {
            stopRecording(RecordingStopNotice.Background)
        }
    }

    /** Shows or hides the looping mock preview while recording and analysis continue. */
    fun toggleLivePreview() {
        if (_uiState.value.recordingState != RecordingState.RECORDING) return
        val enabled = !_uiState.value.livePreviewEnabled
        if (!mockVideoEngine.setPreviewRenderingEnabled(enabled)) return

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
                rebindLatestMockVideo()
            }
            // Format changes recreate the mock PlayerView from Compose. Its callback rebinds the
            // same looping source against the correctly sized test surface.
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

    fun selectMockVideo(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { mockVideoEngine.setSource(uri) }
                .onSuccess { info ->
                    applyMockSourceInfo(info)
                    rebindLatestMockVideo()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.localizedMessage ?: "선택한 동영상을 열지 못했습니다."
                        )
                    }
                }
        }
    }

    private fun applyMockSourceInfo(info: MockVideoEngine.SourceInfo) {
        recordingAnalysisAvailable = true
        _uiState.update { state ->
            state.copy(
                mockSourceReady = true,
                mockSourceName = info.displayName,
                mockSourceDurationSeconds = info.durationSeconds,
                zoomFactor = 1f,
                isBusy = false,
                errorMessage = null,
                statusText = if (state.stage == CameraStage.PREVIEW) {
                    previewGuidance(state.settings)
                } else state.statusText
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    /** Close discards both the review file and selection, matching ReviewVC's close action. */
    fun closeReview() {
        selectionRequestId += 1
        mockVideoEngine.setPreviewRenderingEnabled(true)
        deleteCurrentRecording()
        completedRanges.clear()
        activeDetectedStart = null
        activeRecordingSettings = null
        _uiState.update { state ->
            TopOutUiState(
                settings = state.settings,
                capabilities = state.capabilities,
                modelReady = state.modelReady,
                launchVisible = false,
                zoomFactor = state.zoomFactor,
                isLandscape = state.isLandscape,
                statusText = previewGuidance(state.settings),
                mockSourceReady = state.mockSourceReady,
                mockSourceName = state.mockSourceName,
                mockSourceDurationSeconds = state.mockSourceDurationSeconds
            )
        }
    }

    /** Retake preserves the selected top hold/mat and starts the countdown again automatically. */
    fun retakeFromReview() {
        selectionRequestId += 1
        mockVideoEngine.setPreviewRenderingEnabled(true)
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
        // The mock video composable is recreated on leaving review; bindMockVideo starts the retake.
    }

    private suspend fun finishRecording(result: Result<MockVideoEngine.FinalizedRecording>) {
        storageJob?.cancel()
        // stopRecording() already closes the open detected range before encoder finalization.
        personAnalyzer.stop()
        mockVideoEngine.setPreviewRenderingEnabled(true)
        mockVideoEngine.unbind()

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
            rebindLatestMockVideo()
        }.onSuccess { finalized ->
            val settings = activeRecordingSettings ?: _uiState.value.settings
            val readyFile = finalized.file
            val probedDuration = videoExporter.durationSeconds(readyFile)
            val duration = max(
                0.1,
                if (probedDuration > 0.0) probedDuration else finalized.durationSeconds
            )
            val ranges = normalizeDetectedClipRanges(
                completedRanges.mapNotNull { range ->
                    val startSeconds = range.startSeconds.coerceIn(0.0, duration)
                    val endSeconds = range.endSeconds.coerceIn(0.0, duration)
                    if (endSeconds > startSeconds) ClipRange(startSeconds, endSeconds) else null
                }
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
                    activeDetectedStart = event.seconds
                    val settings = activeRecordingSettings ?: _uiState.value.settings
                    _uiState.update {
                        it.copy(statusText = TopOutCopy.climbingStatus(settings))
                    }
                }
                is MotionEvent.Finish -> {
                    val start = activeDetectedStart
                    if (start != null && event.seconds > start) appendDetectedRange(ClipRange(start, event.seconds))
                    activeDetectedStart = null
                    _uiState.update {
                        it.copy(
                            detectedRanges = completedRanges.toList(),
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
        if (range.endSeconds <= range.startSeconds) return
        val last = completedRanges.lastOrNull()
        if (last != null && abs(last.startSeconds - range.startSeconds) < 0.05 &&
            abs(last.endSeconds - range.endSeconds) < 0.05
        ) return
        completedRanges += range
        while (completedRanges.size > TopOutConfig.MaxStoredClipRanges) completedRanges.removeAt(0)
    }

    /** Exact short-range removal/nearest-neighbour merge policy from HoldVC+Recording.swift. */
    private fun normalizeDetectedClipRanges(input: List<ClipRange>): List<ClipRange> {
        val working = input.filter { it.endSeconds > it.startSeconds }
            .sortedWith(compareBy<ClipRange> { it.startSeconds }.thenBy { it.endSeconds })
            .toMutableList()

        while (true) {
            val shortIndex = working.indexOfFirst { it.durationSeconds < TopOutConfig.ShortClipMinimumTotalSeconds }
            if (shortIndex < 0) break
            val short = working[shortIndex]
            val previous = (shortIndex - 1).takeIf { shortIndex > 0 }
            val next = (shortIndex + 1).takeIf { it < working.size }
            val previousGap = previous?.let { max(0.0, short.startSeconds - working[it].endSeconds) }
            val nextGap = next?.let { max(0.0, working[it].startSeconds - short.endSeconds) }
            val canPrevious = previousGap != null && previousGap <= TopOutConfig.ShortClipMergeGapSeconds
            val canNext = nextGap != null && nextGap <= TopOutConfig.ShortClipMergeGapSeconds

            if (canPrevious || canNext) {
                val mergePrevious = if (canPrevious && canNext) {
                    requireNotNull(previousGap) <= requireNotNull(nextGap)
                } else canPrevious
                if (mergePrevious) {
                    val index = requireNotNull(previous)
                    working[index] = ClipRange(
                        working[index].startSeconds,
                        max(working[index].endSeconds, short.endSeconds)
                    )
                    working.removeAt(shortIndex)
                } else {
                    val index = requireNotNull(next)
                    working[shortIndex] = ClipRange(short.startSeconds, max(short.endSeconds, working[index].endSeconds))
                    working.removeAt(index)
                }
            } else {
                working.removeAt(shortIndex)
            }
        }
        return working.filter { it.endSeconds > it.startSeconds }.takeLast(TopOutConfig.MaxStoredClipRanges)
    }

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

    private fun rebindLatestMockVideo() {
        val preview = latestPreviewView ?: return
        if (_uiState.value.stage == CameraStage.REVIEW) return
        bindMockVideo(preview)
    }

    private fun deleteCurrentRecording() {
        _uiState.value.recordedVideo?.uri?.path?.let { File(it).delete() }
    }

    private fun setError(message: String) = _uiState.update { it.copy(errorMessage = message) }

    override fun onCleared() {
        storageJob?.cancel()
        countdownJob?.cancel()
        cameraBindingJob?.cancel()
        personAnalyzer.stop()
        mockVideoEngine.executeOnAnalysisThread { personAnalyzer.close() }
        mockVideoEngine.release()
        holdSegmenter.close()
        super.onCleared()
    }
}
