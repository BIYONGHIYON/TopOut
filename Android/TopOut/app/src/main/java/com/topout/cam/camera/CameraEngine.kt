package com.topout.cam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Rational
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.topout.cam.model.CameraCapabilities
import com.topout.cam.model.RecordingAspectRatio
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution
import com.topout.cam.model.RecordingStopNotice
import com.topout.cam.model.TopOutConfig
import com.topout.cam.localization.tr
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** CameraX counterpart of Camera.swift + Recorder.swift. */
@Suppress("UsePropertyAccessSyntax")
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val context: Context) {
    data class FinalizedRecording(
        val file: File,
        val durationSeconds: Double,
        val notice: RecordingStopNotice? = null
    )

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var previewRenderingEnabled = true
    private var analysisUseCase: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartGeneration = 0L
    private var recordingStartPending = false
    private var recordingStartTimeout: Runnable? = null
    private var activeRecordingGeneration: Long? = null
    private var startedRecordingGeneration: Long? = null
    private var discardedRecordingGeneration: Long? = null
    private var activeOutputFile: File? = null
    private var requestedStopNotice: RecordingStopNotice? = null
    private var requestedZoom = TopOutConfig.MinZoom

    /**
     * One immutable description of the camera session shown to the user.
     * Preview, analysis and recording are bound together once and keep this exact ViewPort.
     */
    private data class CameraSessionConfig(
        val lifecycleOwner: LifecycleOwner,
        val previewView: PreviewView,
        val targetRotation: Int,
        val resolution: RecordingResolution,
        val frameRate: RecordingFrameRate,
        val aspectRatio: RecordingAspectRatio,
        val isLandscape: Boolean,
        val analyzer: ImageAnalysis.Analyzer,
        val requiresAnalysis: Boolean,
        val viewPort: ViewPort
    )

    private var sessionConfig: CameraSessionConfig? = null

    /**
     * A display zoom anchor is backed by one actual rear camera. For example, an ultra-wide
     * camera is exposed as the 0.5x anchor, the normal camera as 1.0x, and a tele camera as 3.0x.
     * Zoom inside a section is digital zoom on that camera; crossing an anchor rebinds the matching
     * camera, then the active gesture resumes from that anchor.
     */
    private data class RearLensProfile(
        val cameraId: String,
        val selector: CameraSelector,
        val physicalCameraId: String? = null,
        val anchorRatio: Float,
        val nativeMinZoom: Float,
        val nativeMaxZoom: Float,
        val equivalentFocalLength: Float,
        val isLogicalMultiCamera: Boolean,
        val logicalPhysicalCameraIds: Set<String> = emptySet()
    )

    private data class LensSwitchRequest(
        val id: Long,
        val targetProfile: RearLensProfile,
        val previousProfile: RearLensProfile?,
        val targetZoom: Float,
        val frozenFrameBaseZoom: Float,
        val returnToPreviousZoom: Float?
    )

    private sealed interface LensSwitchState {
        data object Idle : LensSwitchState
        data class AwaitingGestureEnd(val request: LensSwitchRequest) : LensSwitchState
        data class Rebinding(val request: LensSwitchRequest) : LensSwitchState
    }

    private var rearLensProfiles: List<RearLensProfile> = emptyList()
    private var activeLensProfile: RearLensProfile? = null
    private var lensSwitchState: LensSwitchState = LensSwitchState.Idle
    private var nextLensSwitchId = 0L
    private var lensSwitchCleanup: (() -> Unit)? = null
    private val hasActiveLensSwitch: Boolean
        get() = lensSwitchState !is LensSwitchState.Idle
    private var effectiveMinZoom = 1f
    private var effectiveMaxZoom = TopOutConfig.CameraMaxZoom
    private var effectiveLensZoomRatios = listOf(1f)
    private var lastSubmittedZoom = Float.NaN

    private data class ExactFpsSessionKey(
        val cameraId: String,
        val resolution: RecordingResolution,
        val aspectRatio: RecordingAspectRatio,
        val isLandscape: Boolean,
        val requiresAnalysis: Boolean
    )

    /** Camera IDs are stable for the process lifetime; hardware capabilities are lens-specific. */
    private var lensDiscoveryComplete = false
    private val cachedCapabilitiesByLens = mutableMapOf<String, CameraCapabilities>()

    /** Exact 60-fps support depends on the full Preview + VideoCapture (+ Analysis) session. */
    private val unsupportedExact60Sessions = mutableSetOf<ExactFpsSessionKey>()

    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStartCancelled: (() -> Unit)? = null
    var onRecordingProgress: ((Double) -> Unit)? = null
    var onRecordingFinalized: ((Result<FinalizedRecording>) -> Unit)? = null
    var onRecordingAnalysisUnavailable: (() -> Unit)? = null
    var onZoomCorrected: ((Float) -> Unit)? = null
    internal var onLensTransition: ((LensTransitionEvent) -> Unit)? = null

    /**
     * Requests an analysis stream whose displayed dimensions already match the selected frame.
     * CameraX may choose a nearby supported size, while the shared ViewPort still guarantees the
     * exact crop. Avoiding a fixed 16:9 request reduces OEM-specific custom-ratio artifacts.
     */
    private fun analysisTargetSize(
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean,
        longSide: Int = 1280
    ): Size {
        val safeLongSide = max(320, longSide)
        val ratio = aspectRatio.widthOverHeight(isLandscape).coerceAtLeast(0.01f)
        fun even(value: Int): Int {
            val clamped = max(2, value)
            return clamped - (clamped and 1)
        }
        return if (ratio >= 1f) {
            Size(even(safeLongSide), even(round(safeLongSide / ratio).toInt()))
        } else {
            Size(even(round(safeLongSide * ratio).toInt()), even(safeLongSide))
        }
    }

    /** One source of truth for the frame visible in Preview, Analysis, and VideoCapture. */
    private fun selectedFrameViewPort(
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean,
        targetRotation: Int
    ): ViewPort {
        val ratio = aspectRatio.viewPortRatio(isLandscape)
        return ViewPort.Builder(Rational(ratio.width, ratio.height), targetRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
    }

    /** Prefer CPU analysis streams with the same orientation and ratio as the selected frame. */
    private fun recordingAnalysisTargetSizes(
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean
    ): List<Size> = listOf(960, 640, 1280, 480, 320)
        .map { longSide -> analysisTargetSize(aspectRatio, isLandscape, longSide) }
        .distinct()

    /** Recomputes the public zoom range and only exposes anchors that actually switch cameras. */
    private fun updateEffectiveZoomRange() {
        if (rearLensProfiles.isEmpty()) {
            effectiveMinZoom = 1f
            effectiveMaxZoom = TopOutConfig.CameraMaxZoom
            effectiveLensZoomRatios = listOf(effectiveMinZoom)
            return
        }

        val firstLens = rearLensProfiles.first()
        effectiveMinZoom = (firstLens.anchorRatio * firstLens.nativeMinZoom)
            .coerceAtLeast(TopOutConfig.MinZoom)

        // 첫 논리 카메라가 포함한 배율은 연속 확대하고 나머지 기준점에서만 재연결한다.
        effectiveLensZoomRatios = buildList {
            add(effectiveMinZoom)
            rearLensProfiles.drop(1).forEach { add(it.anchorRatio) }
        }.distinct().sorted()

        val lastLens = rearLensProfiles.last()
        effectiveMaxZoom = min(
            TopOutConfig.CameraMaxZoom,
            lastLens.anchorRatio * lastLens.nativeMaxZoom
        ).coerceAtLeast(effectiveMinZoom)
    }

    /**
     * Use PreviewView's measured ViewPort while its display rotation matches the capture session.
     * On an Android 16 large-screen retake the window may have rotated in Review even though we
     * intentionally restore the previous take's targetRotation; in that case PreviewView.viewPort
     * belongs to the new window rotation, so rebuild the same aspect crop with the frozen rotation.
     */
    private fun previewViewPort(
        previewView: PreviewView,
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean,
        targetRotation: Int
    ): ViewPort {
        val displayRotation = previewView.display?.rotation
        return if (displayRotation == null || displayRotation == targetRotation) {
            previewView.viewPort
                ?: selectedFrameViewPort(aspectRatio, isLandscape, targetRotation)
        } else {
            selectedFrameViewPort(aspectRatio, isLandscape, targetRotation)
        }
    }

    /**
     * Binds one long-lived WYSIWYG session. Recording never tears this session down, so the
     * selected lens, zoom and crop cannot change between the preview and the first encoded frame.
     */
    private data class BoundUnifiedSession(
        val camera: Camera,
        val frameRate: RecordingFrameRate
    )

    /**
     * Binds Preview + VideoCapture + optional ImageAnalysis as one CameraX SessionConfig.
     * CameraX 1.5+ can report frame-rate ranges guaranteed for this exact use-case combination.
     * A requested 60 fps session is accepted only when the exact 60..60 range is guaranteed;
     * otherwise the same lens/session falls back to 30 fps instead of silently recording at 30.
     */
    private fun bindUnifiedSession(
        cameraProvider: ProcessCameraProvider,
        config: CameraSessionConfig,
        profile: RearLensProfile
    ): BoundUnifiedSession {
        val quality = when (config.resolution) {
            RecordingResolution.FHD -> Quality.FHD
            RecordingResolution.UHD -> Quality.UHD
        }

        data class Attempt(
            val camera: Camera,
            val preview: Preview,
            val capture: VideoCapture<Recorder>,
            val analysis: ImageAnalysis?
        )

        fun newAnalysis(targetSize: Size): ImageAnalysis {
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        targetSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            val builder = ImageAnalysis.Builder()
                .setTargetRotation(config.targetRotation)
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                profile.physicalCameraId?.let { physicalId ->
                    Camera2Interop.Extender(builder).setPhysicalCameraId(physicalId)
                }
            }
            return builder.build().also { it.setAnalyzer(analysisExecutor, config.analyzer) }
        }

        fun tryBind(frameRate: RecordingFrameRate, targetSize: Size?): Attempt? {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .setTargetVideoEncodingBitRate(
                    config.resolution.estimatedBitrate(frameRate, config.aspectRatio).toInt()
                )
                .build()
            val captureBuilder = VideoCapture.Builder(recorder)
                .setTargetRotation(config.targetRotation)
            val previewBuilder = Preview.Builder()
                .setTargetRotation(config.targetRotation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                profile.physicalCameraId?.let { physicalId ->
                    Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalId)
                    Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalId)
                }
            }

            val capture = captureBuilder.build()
            val preview = previewBuilder.build().also { useCase ->
                useCase.setSurfaceProvider(config.previewView.surfaceProvider)
            }
            val analysis = targetSize?.let(::newAnalysis)
            val useCases = buildList {
                add(preview)
                add(capture)
                analysis?.let(::add)
            }
            val baseSession = SessionConfig.Builder(useCases)
                .setViewPort(config.viewPort)
                .build()

            val exactRate = Range(frameRate.fps, frameRate.fps)
            val supportedRanges = runCatching {
                cameraProvider.getCameraInfo(profile.selector, baseSession)
                    .getSupportedFrameRateRanges(baseSession)
            }.getOrDefault(emptySet())

            // 현재 스트림 조합에서 보장되는 경우에만 60fps를 허용한다.
            if (frameRate == RecordingFrameRate.FPS60 && exactRate !in supportedRanges) {
                analysis?.clearAnalyzer()
                return null
            }

            val session = if (exactRate in supportedRanges) {
                SessionConfig.Builder(useCases)
                    .setViewPort(config.viewPort)
                    .setFrameRateRange(exactRate)
                    .build()
            } else {
                // 정확한 범위를 제공하지 않는 기기도 30fps 호환 경로는 유지한다.
                baseSession
            }

            cameraProvider.unbindAll()
            return try {
                val bound = cameraProvider.bindToLifecycle(
                    config.lifecycleOwner,
                    profile.selector,
                    session
                )
                Attempt(bound, preview, capture, analysis)
            } catch (_: Throwable) {
                analysis?.clearAnalyzer()
                cameraProvider.unbindAll()
                null
            }
        }

        val ratesToTry = if (config.frameRate == RecordingFrameRate.FPS60) {
            listOf(RecordingFrameRate.FPS60, RecordingFrameRate.FPS30)
        } else {
            listOf(RecordingFrameRate.FPS30)
        }

        fun accept(attempt: Attempt, rate: RecordingFrameRate): BoundUnifiedSession {
            camera = attempt.camera
            previewUseCase = attempt.preview
            videoCapture = attempt.capture
            analysisUseCase = attempt.analysis
            previewRenderingEnabled = true
            activeLensProfile = profile
            lastSubmittedZoom = Float.NaN
            return BoundUnifiedSession(attempt.camera, rate)
        }

        if (config.requiresAnalysis) {
            // 자동 탐지에서는 분석을 유지할 수 있는 정확한 30fps 구성을 우선한다.
            for (rate in ratesToTry) {
                for (targetSize in recordingAnalysisTargetSizes(config.aspectRatio, config.isLandscape)) {
                    tryBind(rate, targetSize)?.let { return accept(it, rate) }
                }
            }

            // 분석 스트림을 거부하는 기기에서도 미리보기와 촬영은 유지한다.
            for (rate in ratesToTry.asReversed()) {
                val fallback = tryBind(rate, null) ?: continue
                onRecordingAnalysisUnavailable?.invoke()
                return accept(fallback, rate)
            }
        } else {
            // 자동 탐지를 쓰지 않으면 분석 스트림을 제외해 지원 가능한 촬영 설정을 넓힌다.
            for (rate in ratesToTry) {
                tryBind(rate, null)?.let { return accept(it, rate) }
            }
        }

        throw IllegalStateException(tr("error.camera_configuration"))
    }

    fun isAnalysisAvailable(): Boolean = analysisUseCase != null

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: RecordingResolution,
        frameRate: RecordingFrameRate,
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean,
        targetRotation: Int,
        analyzer: ImageAnalysis.Analyzer,
        requiresAnalysis: Boolean
    ): CameraCapabilities {
        cancelRecordingStartForSessionChange()
        cancelLensSwitch()
        val cameraProvider = provider ?: awaitCameraProvider().also { provider = it }
        cameraProvider.unbindAll()

        val cameraManager = context.getSystemService(CameraManager::class.java)

        fun bindProbe(
            selector: CameraSelector,
            physicalCameraId: String? = null
        ): Camera {
            val builder = Preview.Builder()
                .setTargetRotation(targetRotation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                physicalCameraId?.let {
                    Camera2Interop.Extender(builder).setPhysicalCameraId(it)
                }
            }
            val probePreview = builder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            return cameraProvider.bindToLifecycle(lifecycleOwner, selector, probePreview)
        }

        // 실제 후면 카메라를 열거해 별도 초광각 카메라도 시작 렌즈로 선택한다.
        if (!lensDiscoveryComplete) {
            rearLensProfiles = discoverRearLensProfiles(
                cameraProvider = cameraProvider,
                manager = cameraManager
            )
            if (rearLensProfiles.isEmpty()) {
                val fallbackProbe = bindProbe(CameraSelector.DEFAULT_BACK_CAMERA)
                val fallbackState = fallbackProbe.cameraInfo.zoomState.value
                val fallbackMin = fallbackState?.minZoomRatio ?: 1f
                val fallbackMax = fallbackState?.maxZoomRatio ?: TopOutConfig.CameraMaxZoom
                rearLensProfiles = listOf(
                    RearLensProfile(
                        cameraId = runCatching {
                            Camera2CameraInfo.from(fallbackProbe.cameraInfo).cameraId
                        }.getOrDefault("default-back"),
                        selector = CameraSelector.DEFAULT_BACK_CAMERA,
                        anchorRatio = 1f,
                        nativeMinZoom = fallbackMin,
                        nativeMaxZoom = fallbackMax,
                        equivalentFocalLength = EXPECTED_WIDE_EQUIVALENT_MM,
                        isLogicalMultiCamera = false
                    )
                )
                cameraProvider.unbindAll()
            }

            updateEffectiveZoomRange()

            // 최초 실행은 가장 낮은 광학 배율로 시작하고 재연결은 현재 배율을 유지한다.
            requestedZoom = effectiveMinZoom
            activeLensProfile = profileForDisplayZoom(requestedZoom)
            lensDiscoveryComplete = true

        } else {
            activeLensProfile = profileForDisplayZoom(requestedZoom)
        }

        var selectedProfile = activeLensProfile ?: rearLensProfiles.first()
        var selectedSelector = selectedProfile.selector
        var capabilities = cachedCapabilitiesByLens[selectedProfile.cameraId] ?: run {
            var probeResult: Camera? = null
            var lastError: Throwable? = null
            while (probeResult == null && rearLensProfiles.isNotEmpty()) {
                probeResult = try {
                    bindProbe(selectedSelector, selectedProfile.physicalCameraId)
                } catch (error: Throwable) {
                    lastError = error
                    cameraProvider.unbindAll()
                    rearLensProfiles = rearLensProfiles.filterNot {
                        it.cameraId == selectedProfile.cameraId
                    }
                    if (rearLensProfiles.isEmpty()) break
                    selectedProfile = rearLensProfiles.first()
                    selectedSelector = selectedProfile.selector
                    activeLensProfile = selectedProfile
                    updateEffectiveZoomRange()
                    requestedZoom = effectiveMinZoom
                    null
                }
            }
            val probe = probeResult ?: throw (lastError
                ?: IllegalStateException(tr("error.rear_camera")))
            queryCapabilities(probe, selectedProfile).also {
                cachedCapabilitiesByLens[selectedProfile.cameraId] = it
                cameraProvider.unbindAll()
            }
        }

        val resolvedResolution = if (capabilities.supportedFrameRates[resolution].isNullOrEmpty()) {
            when {
                capabilities.supportedFrameRates.containsKey(RecordingResolution.FHD) -> RecordingResolution.FHD
                else -> capabilities.supportedFrameRates.keys.firstOrNull() ?: RecordingResolution.FHD
            }
        } else resolution
        val allowedRates = capabilities.supportedFrameRates[resolvedResolution].orEmpty()
        val resolvedFrameRate = when {
            frameRate in allowedRates -> frameRate
            RecordingFrameRate.FPS30 in allowedRates -> RecordingFrameRate.FPS30
            RecordingFrameRate.FPS60 in allowedRates -> RecordingFrameRate.FPS60
            else -> allowedRates.firstOrNull() ?: RecordingFrameRate.FPS30
        }

        val config = CameraSessionConfig(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            targetRotation = targetRotation,
            resolution = resolvedResolution,
            frameRate = resolvedFrameRate,
            aspectRatio = aspectRatio,
            isLandscape = isLandscape,
            analyzer = analyzer,
            requiresAnalysis = requiresAnalysis,
            viewPort = previewViewPort(previewView, aspectRatio, isLandscape, targetRotation)
        )

        val boundSession = try {
            bindUnifiedSession(cameraProvider, config, selectedProfile)
        } catch (firstError: Throwable) {
            cameraProvider.unbindAll()
            rearLensProfiles = rearLensProfiles.filterNot { it.cameraId == selectedProfile.cameraId }
            if (rearLensProfiles.isEmpty()) throw firstError

            selectedProfile = rearLensProfiles.first()
            activeLensProfile = selectedProfile
            updateEffectiveZoomRange()
            requestedZoom = effectiveMinZoom
            onZoomCorrected?.invoke(requestedZoom)

            try {
                bindUnifiedSession(cameraProvider, config, selectedProfile)
            } catch (fallbackError: Throwable) {
                cameraProvider.unbindAll()
                fallbackError.addSuppressed(firstError)
                throw fallbackError
            }
        }

        camera = boundSession.camera
        sessionConfig = config.copy(frameRate = boundSession.frameRate)

        // 연결된 렌즈의 기본 기능과 현재 세션의 60fps 지원 여부를 분리해 관리한다.
        val baseCapabilities = queryCapabilities(boundSession.camera, selectedProfile)
        cachedCapabilitiesByLens[selectedProfile.cameraId] = baseCapabilities

        val exact60Key = ExactFpsSessionKey(
            cameraId = selectedProfile.cameraId,
            resolution = resolvedResolution,
            aspectRatio = aspectRatio,
            isLandscape = isLandscape,
            requiresAnalysis = requiresAnalysis
        )
        if (resolvedFrameRate == RecordingFrameRate.FPS60) {
            if (boundSession.frameRate == RecordingFrameRate.FPS60) {
                unsupportedExact60Sessions.remove(exact60Key)
            } else {
                unsupportedExact60Sessions += exact60Key
            }
        }

        capabilities = baseCapabilities
        if (exact60Key in unsupportedExact60Sessions) {
            val correctedRates = capabilities.supportedFrameRates.toMutableMap()
            correctedRates[resolvedResolution] =
                correctedRates[resolvedResolution].orEmpty() - RecordingFrameRate.FPS60 +
                    RecordingFrameRate.FPS30
            capabilities = capabilities.copy(supportedFrameRates = correctedRates)
        }
        capabilities = capabilities.copy(
            minZoomRatio = effectiveMinZoom,
            maxZoomRatio = effectiveMaxZoom,
            lensZoomRatios = effectiveLensZoomRatios
        )
        awaitRequestedZoomApplied()
        awaitPreviewStreaming(config.lifecycleOwner, previewView)
        return capabilities
    }

    private fun cancelLensSwitch() {
        val request = when (val state = lensSwitchState) {
            LensSwitchState.Idle -> null
            is LensSwitchState.AwaitingGestureEnd -> state.request
            is LensSwitchState.Rebinding -> state.request
        }
        lensSwitchState = LensSwitchState.Idle
        lensSwitchCleanup?.invoke()
        lensSwitchCleanup = null
        request?.let {
            onLensTransition?.invoke(
                LensTransitionEvent.Aborted(it.id, requestedZoom)
            )
        }
    }

    fun unbind() {
        // 연결 해제 전에 지연 작업과 관찰자를 정리해 중복 상태 이벤트를 막는다.
        cancelLensSwitch()
        cancelRecordingStartForSessionChange()
        activeRecording?.stop()
        activeRecording = null
        provider?.unbindAll()
        camera = null
        previewUseCase = null
        previewRenderingEnabled = true
        videoCapture = null
        analysisUseCase = null
        sessionConfig = null
        lastSubmittedZoom = Float.NaN
    }

    /**
     * Starts or stops only CameraX's display-preview output. VideoCapture and ImageAnalysis remain
     * bound to the same camera session, so video/audio recording and pose detection continue.
     *
     * Passing a null SurfaceProvider is CameraX's supported way to stop producing Preview data
     * without rebinding the session. Reattaching PreviewView's provider resumes the live image.
     */
    fun setPreviewRenderingEnabled(enabled: Boolean): Boolean {
        val preview = previewUseCase
        val config = sessionConfig
        if (preview == null || config == null) {
            return !enabled || activeRecording == null
        }
        if (previewRenderingEnabled == enabled) return true

        return runCatching {
            preview.setSurfaceProvider(
                if (enabled) config.previewView.surfaceProvider else null
            )
            previewRenderingEnabled = enabled
            true
        }.getOrDefault(false)
    }

    fun updateTargetRotation(rotation: Int) {
        previewUseCase?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
    }

    /**
     * The gesture controller emits at most one optical-boundary request before each stream rebase.
     * Here the display ratio is mapped to the camera whose optical anchor owns that section.
     */
    fun setZoom(zoom: Float) {
        // 렌즈 전환 중 들어오는 핀치 값은 전환 기준 배율을 덮어쓰지 못하게 한다.
        if (hasActiveLensSwitch) return

        val targetZoom = zoom.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        val targetProfile = profileForDisplayZoom(targetZoom) ?: return
        val currentProfile = activeLensProfile
        requestedZoom = targetZoom

        if (currentProfile == null || currentProfile.cameraId != targetProfile.cameraId) {
            requestLensSwitch(targetProfile, targetZoom)
        } else {
            submitZoomImmediately()
        }
    }

    private fun applyRequestedZoomToBoundCamera(onApplied: (() -> Unit)? = null) {
        lastSubmittedZoom = Float.NaN
        val future = submitZoomImmediately()
        if (future == null) {
            onApplied?.invoke()
        } else if (onApplied != null) {
            // 미리보기 배율 적용이 확인된 뒤 카메라 준비를 완료한다.
            future.addListener({ onApplied() }, mainExecutor)
        }
    }

    /** Maps the public/display zoom to digital zoom inside the currently selected real camera. */
    private fun submitZoomImmediately(): ListenableFuture<*>? {
        val activeCamera = camera ?: return null
        val profile = activeLensProfile ?: return null
        val target = requestedZoom.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        if (!lastSubmittedZoom.isNaN() && abs(lastSubmittedZoom - target) < ZOOM_SUBMIT_EPSILON) {
            return null
        }

        val zoomState = activeCamera.cameraInfo.zoomState.value
        val stateMin = zoomState?.minZoomRatio ?: profile.nativeMinZoom
        val stateMax = zoomState?.maxZoomRatio ?: profile.nativeMaxZoom
        val nativeTarget = (target / profile.anchorRatio)
            .coerceIn(max(profile.nativeMinZoom, stateMin), min(profile.nativeMaxZoom, stateMax))

        lastSubmittedZoom = target
        // 물리 렌즈에는 표시 배율이 아닌 해당 렌즈의 기본 배율 좌표를 전달한다.
        return activeCamera.cameraControl.setZoomRatio(nativeTarget)
    }

    private fun profileForDisplayZoom(zoom: Float): RearLensProfile? = rearLensProfiles
        .lastOrNull { zoom + LENS_BOUNDARY_EPSILON >= it.anchorRatio }
        ?: rearLensProfiles.firstOrNull()

    private fun maximumDisplayZoomForProfile(profile: RearLensProfile): Float {
        val profileIndex = rearLensProfiles.indexOfFirst { it.cameraId == profile.cameraId }
        val nextProfile = rearLensProfiles.getOrNull(profileIndex + 1)
        if (profileIndex < 0 || nextProfile == null) return effectiveMaxZoom
        val safeGap = min(
            LENS_SWITCH_GAP,
            (nextProfile.anchorRatio - profile.anchorRatio) * LENS_SWITCH_MAX_GAP_FRACTION
        )
        return (nextProfile.anchorRatio - safeGap)
            .coerceAtLeast(profile.anchorRatio)
            .coerceAtMost(effectiveMaxZoom)
    }

    private fun requestLensSwitch(
        targetProfile: RearLensProfile,
        transitionZoom: Float
    ) {
        if (activeRecording != null || hasActiveLensSwitch) return
        val currentProfile = activeLensProfile
        val targetZoom = transitionZoom.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        val frozenFrameBaseZoom = lastSubmittedZoom
            .takeIf { it.isFinite() && it > 0f }
            ?: targetZoom
        val returnToPreviousZoom = currentProfile
            ?.takeIf {
                targetProfile.anchorRatio > it.anchorRatio + LENS_BOUNDARY_EPSILON
            }
            ?.let(::maximumDisplayZoomForProfile)
        val zoomingIntoHigherAnchor = returnToPreviousZoom != null
        val request = LensSwitchRequest(
            id = ++nextLensSwitchId,
            targetProfile = targetProfile,
            previousProfile = currentProfile,
            targetZoom = targetZoom,
            frozenFrameBaseZoom = frozenFrameBaseZoom,
            returnToPreviousZoom = returnToPreviousZoom
        )

        requestedZoom = targetZoom
        lensSwitchState = if (zoomingIntoHigherAnchor) {
            LensSwitchState.AwaitingGestureEnd(request)
        } else {
            LensSwitchState.Rebinding(request)
        }
        onLensTransition?.invoke(
            LensTransitionEvent.Started(
                id = request.id,
                displayZoom = request.targetZoom,
                frameScale = frozenFrameScale(request),
                awaitingGestureEnd = zoomingIntoHigherAnchor
            )
        )
        if (!zoomingIntoHigherAnchor) performLensSwitch(request)
    }

    /** Applies continued pinch motion to the frozen frame without touching the old camera. */
    fun updateDeferredLensSwitchZoom(zoom: Float) {
        val state = lensSwitchState as? LensSwitchState.AwaitingGestureEnd ?: return
        val request = state.request
        val returnZoom = request.returnToPreviousZoom
        if (returnZoom != null && zoom <= returnZoom + LENS_BOUNDARY_EPSILON) {
            resumePreviousLens(request, zoom.coerceIn(effectiveMinZoom, returnZoom))
            return
        }

        val sectionMaximum = maximumDisplayZoomForProfile(request.targetProfile)
        val pendingMinimum = returnZoom
            ?.coerceAtMost(request.targetProfile.anchorRatio)
            ?: request.targetProfile.anchorRatio
        val targetZoom = zoom.coerceIn(pendingMinimum, sectionMaximum)
        val updatedRequest = request.copy(targetZoom = targetZoom)
        lensSwitchState = LensSwitchState.AwaitingGestureEnd(updatedRequest)
        requestedZoom = targetZoom
        onLensTransition?.invoke(
            LensTransitionEvent.Updated(
                id = updatedRequest.id,
                displayZoom = updatedRequest.targetZoom,
                frameScale = frozenFrameScale(updatedRequest),
                awaitingGestureEnd = true
            )
        )
    }

    private fun resumePreviousLens(request: LensSwitchRequest, zoom: Float) {
        if ((lensSwitchState as? LensSwitchState.AwaitingGestureEnd)?.request?.id != request.id) {
            return
        }
        lensSwitchState = LensSwitchState.Idle
        requestedZoom = zoom.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        submitZoomImmediately()
        onLensTransition?.invoke(
            LensTransitionEvent.Aborted(request.id, requestedZoom)
        )
    }

    /** Commits a deferred zoom-in switch at the final frozen-frame ratio. */
    fun finishDeferredLensSwitchGesture() {
        val state = lensSwitchState as? LensSwitchState.AwaitingGestureEnd ?: return
        val request = state.request
        val committedRequest = request.copy(
            targetZoom = request.targetZoom.coerceAtLeast(request.targetProfile.anchorRatio)
        )
        requestedZoom = committedRequest.targetZoom
        performLensSwitch(committedRequest)
    }

    private fun frozenFrameScale(request: LensSwitchRequest): Float {
        val previousProfile = request.previousProfile
        val zoomingIntoHigherAnchor = previousProfile != null &&
            request.targetProfile.anchorRatio >
            previousProfile.anchorRatio + LENS_BOUNDARY_EPSILON
        return if (
            zoomingIntoHigherAnchor && request.targetZoom > request.frozenFrameBaseZoom
        ) {
            (request.targetZoom / request.frozenFrameBaseZoom).coerceAtLeast(1f)
        } else {
            1f
        }
    }

    private fun currentLensSwitchId(): Long? = when (val state = lensSwitchState) {
        LensSwitchState.Idle -> null
        is LensSwitchState.AwaitingGestureEnd -> state.request.id
        is LensSwitchState.Rebinding -> state.request.id
    }

    private fun isCurrentRebind(id: Long): Boolean =
        (lensSwitchState as? LensSwitchState.Rebinding)?.request?.id == id

    private fun completeLensSwitch(request: LensSwitchRequest) {
        finishLensSwitch(request, previewReady = true)
    }

    private fun abortLensSwitch(request: LensSwitchRequest) {
        finishLensSwitch(request, previewReady = false)
    }

    private fun finishLensSwitch(request: LensSwitchRequest, previewReady: Boolean) {
        if (currentLensSwitchId() != request.id) return
        lensSwitchState = LensSwitchState.Idle
        lensSwitchCleanup?.invoke()
        lensSwitchCleanup = null
        val event = if (previewReady) {
            LensTransitionEvent.Completed(request.id, requestedZoom)
        } else {
            LensTransitionEvent.Aborted(request.id, requestedZoom)
        }
        onLensTransition?.invoke(event)
    }

    /**
     * CameraX cannot change an exact camera ID in place. Zoom-in reaches this method after finger
     * release with the final frozen-frame ratio; zoom-out reaches it immediately at its boundary.
     * The overlay remains until PreviewView is STREAMING at the same requested display zoom.
     */
    private fun performLensSwitch(request: LensSwitchRequest) {
        val cameraProvider = provider ?: run {
            abortLensSwitch(request)
            return
        }
        val config = sessionConfig ?: run {
            abortLensSwitch(request)
            return
        }
        val targetProfile = request.targetProfile
        val transitionZoom = request.targetZoom.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        val previousProfile = request.previousProfile
        val wasAwaitingGestureEnd = lensSwitchState is LensSwitchState.AwaitingGestureEnd
        lensSwitchState = LensSwitchState.Rebinding(request)
        if (wasAwaitingGestureEnd) {
            onLensTransition?.invoke(
                LensTransitionEvent.Updated(
                    id = request.id,
                    displayZoom = transitionZoom,
                    frameScale = frozenFrameScale(request),
                    awaitingGestureEnd = false
                )
            )
        }

        if (previousProfile?.cameraId == targetProfile.cameraId) {
            requestedZoom = transitionZoom
            val future = submitZoomImmediately()
            if (future == null) {
                completeLensSwitch(request)
            } else {
                future.addListener(
                    {
                        if (isCurrentRebind(request.id)) {
                            if (runCatching { future.get() }.isSuccess) {
                                completeLensSwitch(request)
                            } else {
                                abortLensSwitch(request)
                            }
                        }
                    },
                    mainExecutor
                )
            }
            return
        }

        requestedZoom = transitionZoom

        var rebindStarted = false
        val startRebind = Runnable {
            if (rebindStarted) return@Runnable
            if (!isCurrentRebind(request.id)) return@Runnable
            rebindStarted = true

            var switchFinished = false
            var sawIdleAfterSwitchRequest =
                config.previewView.previewStreamState.value != PreviewView.StreamState.STREAMING
            var previewStreamReady = false
            var boundaryZoomReady = false
            var readyFinishPosted = false
            var currentAttemptId = 0
            var streamObserver: Observer<PreviewView.StreamState>? = null
            var timeoutRunnable: Runnable? = null

            fun cleanupLocalSwitch() {
                if (switchFinished) return
                switchFinished = true
                streamObserver?.let { config.previewView.previewStreamState.removeObserver(it) }
                timeoutRunnable?.let(config.previewView::removeCallbacks)
            }

            fun finishSwitch(previewReady: Boolean) {
                if (switchFinished) return
                cleanupLocalSwitch()
                if (!isCurrentRebind(request.id)) return
                if (previewReady) completeLensSwitch(request) else abortLensSwitch(request)
            }

            fun finishSwitchWhenFullyReady(attemptId: Int) {
                if (attemptId != currentAttemptId) return
                if (previewStreamReady && boundaryZoomReady && !readyFinishPosted) {
                    readyFinishPosted = true
                    // 배율 적용 뒤 한 화면 주기를 기다린 후 전환 프레임을 페이드한다.
                    config.previewView.postOnAnimation {
                        if (attemptId != currentAttemptId) return@postOnAnimation
                        readyFinishPosted = false
                        if (previewStreamReady && boundaryZoomReady) {
                            finishSwitch(previewReady = true)
                        }
                    }
                }
            }

            fun beginAttempt(): Int {
                currentAttemptId += 1
                val attemptId = currentAttemptId
                streamObserver?.let { config.previewView.previewStreamState.removeObserver(it) }
                sawIdleAfterSwitchRequest =
                    config.previewView.previewStreamState.value != PreviewView.StreamState.STREAMING
                previewStreamReady = false
                boundaryZoomReady = false
                readyFinishPosted = false
                val observer = Observer<PreviewView.StreamState> { streamState ->
                    if (switchFinished ||
                        attemptId != currentAttemptId ||
                        !isCurrentRebind(request.id)
                    ) return@Observer
                    when (streamState) {
                        PreviewView.StreamState.IDLE -> {
                            sawIdleAfterSwitchRequest = true
                            previewStreamReady = false
                        }
                        PreviewView.StreamState.STREAMING -> {
                            if (sawIdleAfterSwitchRequest) {
                                previewStreamReady = true
                                finishSwitchWhenFullyReady(attemptId)
                            }
                        }
                    }
                }
                streamObserver = observer
                config.previewView.previewStreamState.observe(config.lifecycleOwner, observer)
                return attemptId
            }

            fun awaitBoundaryZoom(
                future: ListenableFuture<*>?,
                attemptId: Int,
                onFailure: () -> Unit
            ) {
                if (attemptId != currentAttemptId) return
                boundaryZoomReady = false
                if (future == null) {
                    boundaryZoomReady = true
                    finishSwitchWhenFullyReady(attemptId)
                    return
                }
                future.addListener(
                    {
                        if (!switchFinished &&
                            isCurrentRebind(request.id) &&
                            attemptId == currentAttemptId
                        ) {
                            if (runCatching { future.get() }.isSuccess) {
                                boundaryZoomReady = true
                                finishSwitchWhenFullyReady(attemptId)
                            } else {
                                onFailure()
                            }
                        }
                    },
                    mainExecutor
                )
            }

            fun scheduleTimeout(attemptId: Int, onTimeout: () -> Unit) {
                timeoutRunnable?.let(config.previewView::removeCallbacks)
                timeoutRunnable = Runnable {
                    if (!switchFinished &&
                        isCurrentRebind(request.id) &&
                        attemptId == currentAttemptId
                    ) onTimeout()
                }.also { timeout ->
                    config.previewView.postDelayed(timeout, LENS_SWITCH_READY_TIMEOUT_MS)
                }
            }

            lensSwitchCleanup = { cleanupLocalSwitch() }

            fun bindProfile(profile: RearLensProfile): Camera {
                val bound = bindUnifiedSession(cameraProvider, config, profile)
                // 대상 렌즈가 현재 60fps를 유지하지 못하면 전환을 취소하고 이전 렌즈를 복구한다.
                if (bound.frameRate != config.frameRate) {
                    cameraProvider.unbindAll()
                    throw IllegalStateException(tr("error.lens_transition"))
                }
                camera = bound.camera
                activeLensProfile = profile
                sessionConfig = config
                return bound.camera
            }

            var restoringPreviousProfile = false
            fun restorePreviousProfile() {
                val profile = previousProfile
                if (profile == null || restoringPreviousProfile) {
                    finishSwitch(previewReady = false)
                    return
                }
                restoringPreviousProfile = true
                val restored = runCatching {
                    val restoreAttemptId = beginAttempt()
                    scheduleTimeout(restoreAttemptId) {
                        finishSwitch(previewReady = false)
                    }
                    bindProfile(profile)
                    requestedZoom = transitionZoom.coerceIn(
                        profile.anchorRatio,
                        maximumDisplayZoomForProfile(profile)
                    )
                    awaitBoundaryZoom(
                        future = submitZoomImmediately(),
                        attemptId = restoreAttemptId,
                        onFailure = { finishSwitch(previewReady = false) }
                    )
                }.isSuccess
                if (!restored) finishSwitch(previewReady = false)
            }

            try {
                val targetAttemptId = beginAttempt()
                scheduleTimeout(targetAttemptId, ::restorePreviousProfile)
                bindProfile(targetProfile)
                // 확대는 손을 뗄 때의 배율을, 축소는 전환을 시작한 경계 배율을 사용한다.
                requestedZoom = transitionZoom
                awaitBoundaryZoom(
                    future = submitZoomImmediately(),
                    attemptId = targetAttemptId,
                    onFailure = ::restorePreviousProfile
                )
            } catch (_: Throwable) {
                restorePreviousProfile()
            }
        }

        // 정지 프레임이 한 번 그려진 뒤 기존 카메라를 해제한다.
        config.previewView.postOnAnimation {
            if (isCurrentRebind(request.id)) {
                config.previewView.postOnAnimation(startRebind)
            }
        }
        // 화면 콜백이 멈춰도 대체 지연 작업으로 전환을 한 번만 진행한다.
        config.previewView.postDelayed(startRebind, LENS_SWITCH_REBIND_FALLBACK_MS)
    }

    /**
     * Discovers every public rear camera, computes its 35-mm-equivalent focal length, and maps it
     * to display anchors such as 0.5x, 1.0x, 3.0x, and 5.0x. Unlike the previous implementation,
     * this does not require one logical camera to advertise a sub-1x CameraX zoom range.
     */
    private fun discoverRearLensProfiles(
        cameraProvider: ProcessCameraProvider,
        manager: CameraManager
    ): List<RearLensProfile> {
        data class Candidate(
            val cameraId: String,
            val selector: CameraSelector,
            val physicalCameraId: String?,
            val equivalentFocalLength: Float,
            val nativeMinZoom: Float,
            val nativeMaxZoom: Float,
            val isLogicalMultiCamera: Boolean,
            val logicalPhysicalCameraIds: Set<String> = emptySet()
        )

        fun equivalentFocalLength(characteristics: CameraCharacteristics): Float? {
            val sensorWidth = characteristics
                .get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?.width
                ?.takeIf { it > 0f }
                ?: return null
            return characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.asSequence()
                ?.filter { it > 0f }
                ?.map { it * FULL_FRAME_SENSOR_WIDTH_MM / sensorWidth }
                ?.minByOrNull { abs(it - EXPECTED_WIDE_EQUIVALENT_MM) }
        }
        val candidates = mutableListOf<Candidate>()
        cameraProvider.availableCameraInfos.forEach publicLoop@ { info ->
            val camera2Info = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
                ?: return@publicLoop
            val publicCameraId = runCatching { camera2Info.cameraId }.getOrNull()
                ?: return@publicLoop
            val characteristics = runCatching {
                manager.getCameraCharacteristics(publicCameraId)
            }.getOrNull() ?: return@publicLoop
            if (
                characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) return@publicLoop

            val selector = exactCameraSelector(publicCameraId)
            if (!runCatching { cameraProvider.hasCamera(selector) }.getOrDefault(false)) {
                return@publicLoop
            }
            val availableCapabilities = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            val logical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                availableCapabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
            } else {
                false
            }
            val logicalPhysicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logical) {
                runCatching { characteristics.physicalCameraIds.toSet() }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            val zoomState = info.zoomState.value
            val camera2Max = characteristics
                .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: TopOutConfig.CameraMaxZoom
            // 연결 전에는 Camera2의 정적 배율 범위를 우선해 초광각 지원을 판단한다.
            val camera2ZoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // CameraX가 보정한 Camera2 특성 조회 경로를 사용한다.
                runCatching {
                    camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                    )
                }.getOrNull()
            } else {
                null
            }
            val publicMinZoom = camera2ZoomRatioRange?.lower
                ?.takeIf { it.isFinite() && it > 0f }
                ?: zoomState?.minZoomRatio?.takeIf { it.isFinite() && it > 0f }
                ?: 1f
            val publicMaxZoom = camera2ZoomRatioRange?.upper
                ?.takeIf { it.isFinite() && it >= publicMinZoom }
                ?: zoomState?.maxZoomRatio?.takeIf { it.isFinite() && it >= publicMinZoom }
                ?: camera2Max.takeIf { it.isFinite() && it >= publicMinZoom }
                ?: TopOutConfig.CameraMaxZoom
            equivalentFocalLength(characteristics)?.let { equivalent ->
                candidates += Candidate(
                    cameraId = publicCameraId,
                    selector = selector,
                    physicalCameraId = null,
                    equivalentFocalLength = equivalent,
                    nativeMinZoom = publicMinZoom
                        .coerceAtLeast(TopOutConfig.MinZoom),
                    nativeMaxZoom = min(
                        TopOutConfig.CameraMaxZoom,
                        publicMaxZoom
                    ).coerceAtLeast(1f),
                    isLogicalMultiCamera = logical,
                    logicalPhysicalCameraIds = logicalPhysicalCameraIds
                )
            }

            // 논리 카메라의 물리 구성원으로만 노출된 렌즈도 직접 고정해 사용한다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logical) {
                logicalPhysicalCameraIds.forEach physicalLoop@ { physicalId ->
                    val physicalCharacteristics = runCatching {
                        manager.getCameraCharacteristics(physicalId)
                    }.getOrNull() ?: return@physicalLoop
                    val equivalent = equivalentFocalLength(physicalCharacteristics)
                        ?: return@physicalLoop
                    val physicalMax = physicalCharacteristics
                        .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?: TopOutConfig.CameraMaxZoom
                    candidates += Candidate(
                        cameraId = "$publicCameraId/$physicalId",
                        selector = selector,
                        physicalCameraId = physicalId,
                        equivalentFocalLength = equivalent,
                        nativeMinZoom = 1f,
                        nativeMaxZoom = min(TopOutConfig.CameraMaxZoom, physicalMax)
                            .coerceAtLeast(1f),
                        isLogicalMultiCamera = false
                    )
                }
            }
        }

        if (candidates.isEmpty()) return emptyList()
        val reference = candidates
            .minByOrNull { abs(it.equivalentFocalLength - EXPECTED_WIDE_EQUIVALENT_MM) }
            ?: return emptyList()
        val referenceFocal = reference.equivalentFocalLength

        val profiles = candidates
            .map { candidate ->
                val rawAnchor = candidate.equivalentFocalLength / referenceFocal
                RearLensProfile(
                    cameraId = candidate.cameraId,
                    selector = candidate.selector,
                    physicalCameraId = candidate.physicalCameraId,
                    anchorRatio = snapPhysicalLensAnchor(rawAnchor),
                    nativeMinZoom = candidate.nativeMinZoom,
                    nativeMaxZoom = candidate.nativeMaxZoom,
                    equivalentFocalLength = candidate.equivalentFocalLength,
                    isLogicalMultiCamera = candidate.isLogicalMultiCamera,
                    logicalPhysicalCameraIds = candidate.logicalPhysicalCameraIds
                )
            }
            .filter { it.anchorRatio in TopOutConfig.MinZoom..TopOutConfig.CameraMaxZoom }

        // 논리 그룹 밖의 망원 렌즈는 별도 전환 대상으로 유지한다.
        selectHybridRearLensCandidates(
            candidates = profiles.map { profile ->
                HybridRearLensCandidate(
                    value = profile,
                    cameraId = profile.cameraId,
                    physicalCameraId = profile.physicalCameraId,
                    anchorRatio = profile.anchorRatio,
                    nativeMinZoom = profile.nativeMinZoom,
                    nativeMaxZoom = profile.nativeMaxZoom,
                    equivalentFocalLength = profile.equivalentFocalLength,
                    isLogicalMultiCamera = profile.isLogicalMultiCamera,
                    logicalPhysicalCameraIds = profile.logicalPhysicalCameraIds
                )
            },
            referenceFocalLength = referenceFocal,
            expectedWideEquivalent = EXPECTED_WIDE_EQUIVALENT_MM,
            boundaryEpsilon = LENS_BOUNDARY_EPSILON,
            transitionGap = LENS_SWITCH_GAP,
            maximumGapFraction = LENS_SWITCH_MAX_GAP_FRACTION
        )?.let { hybridProfiles ->
            return hybridProfiles.map { it.value }
        }

        return profiles
            .groupBy { it.anchorRatio }
            .mapNotNull { (_, sameAnchor) ->
                sameAnchor.minWithOrNull(
                    compareBy<RearLensProfile> {
                        abs(it.equivalentFocalLength / referenceFocal - it.anchorRatio)
                    }.thenBy { profile ->
                        when {
                            profile.anchorRatio == 1f &&
                                profile.isLogicalMultiCamera &&
                                profile.physicalCameraId == null -> 0
                            profile.anchorRatio != 1f &&
                                !profile.isLogicalMultiCamera -> 0
                            else -> 1
                        }
                    }.thenByDescending { it.nativeMaxZoom }
                )
            }
            .sortedBy { it.anchorRatio }
            .let { profiles ->
                val hasWide = profiles.any { abs(it.anchorRatio - 1f) <= LENS_BOUNDARY_EPSILON }
                if (hasWide) profiles else {
                    profiles + RearLensProfile(
                        cameraId = reference.cameraId,
                        selector = reference.selector,
                        physicalCameraId = reference.physicalCameraId,
                        anchorRatio = 1f,
                        nativeMinZoom = reference.nativeMinZoom,
                        nativeMaxZoom = reference.nativeMaxZoom,
                        equivalentFocalLength = reference.equivalentFocalLength,
                        isLogicalMultiCamera = reference.isLogicalMultiCamera,
                        logicalPhysicalCameraIds = reference.logicalPhysicalCameraIds
                    )
                }
            }
            .distinctBy { it.anchorRatio }
            .sortedBy { it.anchorRatio }
    }

    private fun snapPhysicalLensAnchor(rawRatio: Float): Float {
        if (rawRatio < 0.8f) {
            // 12~14mm 초광각은 일반 카메라 표기와 같이 0.5배로 표시한다.
            return TopOutConfig.MinZoom
        }
        if (abs(rawRatio - 1f) <= 0.25f) return 1f
        val nearestInteger = round(rawRatio)
        if (abs(rawRatio - nearestInteger) <= 0.18f) return nearestInteger
        return round(rawRatio * 10f) / 10f
    }

    private fun exactCameraSelector(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }
                        .getOrDefault(false)
                }
            }
            .build()

    private companion object {
        const val ZOOM_SUBMIT_EPSILON = 0.0005f
        const val LENS_BOUNDARY_EPSILON = 0.001f
        const val LENS_SWITCH_GAP = 0.1f
        const val LENS_SWITCH_MAX_GAP_FRACTION = 0.25f
        const val LENS_SWITCH_REBIND_FALLBACK_MS = 100L
        const val LENS_SWITCH_READY_TIMEOUT_MS = 2_500L
        const val PREVIEW_STREAM_READY_TIMEOUT_MS = 3_000L
        const val ZOOM_APPLY_TIMEOUT_MS = 1_500L
        const val FULL_FRAME_SENSOR_WIDTH_MM = 36f
        const val EXPECTED_WIDE_EQUIVALENT_MM = 26f
    }

    fun startRecording(includeAudio: Boolean = true) {
        if (activeRecording != null) {
            // 취소된 녹화 객체의 종료 이벤트 전에는 새 시작 요청을 거부한다.
            if (discardedRecordingGeneration == activeRecordingGeneration) {
                onRecordingStartCancelled?.invoke()
            }
            return
        }
        if (recordingStartPending) return
        val capture = videoCapture ?: run {
            onRecordingFinalized?.invoke(
                Result.failure(IllegalStateException(tr("error.camera_session_not_ready")))
            )
            return
        }
        val startGeneration = ++recordingStartGeneration
        recordingStartPending = true
        fun beginOnce() {
            if (!recordingStartPending || startGeneration != recordingStartGeneration) return
            recordingStartTimeout?.let(mainHandler::removeCallbacks)
            recordingStartTimeout = null
            recordingStartPending = false
            // 재연결 전 세션의 지연 배율 콜백은 녹화를 시작하지 못하게 한다.
            if (videoCapture !== capture || activeRecording != null) {
                onRecordingFinalized?.invoke(
                    Result.failure(IllegalStateException(tr("error.camera_session_changed")))
                )
                return
            }
            beginRecording(capture, includeAudio, startGeneration)
        }

        // 배율 요청이 응답하지 않으면 1.5초 뒤 현재 연결된 세션으로 한 번만 시작한다.
        val timeout = Runnable { beginOnce() }
        recordingStartTimeout = timeout
        mainHandler.postDelayed(timeout, ZOOM_APPLY_TIMEOUT_MS)

        // 확인된 배율을 우선하고 실패한 요청은 시간 제한 대체 경로로 넘긴다.
        runCatching {
            lastSubmittedZoom = Float.NaN
            submitZoomImmediately()
        }.onSuccess { future ->
            if (future == null) {
                beginOnce()
            } else {
                future.addListener(
                    {
                        if (runCatching { future.get() }.isSuccess) beginOnce()
                    },
                    mainExecutor
                )
            }
        }
    }

    /** Invalidates an asynchronous zoom-confirmed start without disturbing the bound session. */
    private fun cancelPendingRecordingStart() {
        recordingStartTimeout?.let(mainHandler::removeCallbacks)
        recordingStartTimeout = null
        recordingStartGeneration++
        recordingStartPending = false
    }

    /** Cancels either a pending zoom gate or a recorder created before its Start event arrived. */
    fun cancelRecordingStart() {
        cancelPendingRecordingStart()
        val generation = activeRecordingGeneration ?: return
        val recording = activeRecording ?: return
        discardedRecordingGeneration = generation
        recording.stop()
    }

    /**
     * A surface/lifecycle rebind can invalidate a start without passing through the ViewModel's
     * stop action. Cancel only the pre-Start portion here and notify the UI; an established
     * recording keeps its normal CameraX Finalize path when the session is unbound.
     */
    private fun cancelRecordingStartForSessionChange() {
        val hadPendingZoomGate = recordingStartPending
        cancelPendingRecordingStart()
        val generation = activeRecordingGeneration
        val hasPreparedRecorder = generation != null &&
            startedRecordingGeneration != generation &&
            activeRecording != null
        if (hasPreparedRecorder) {
            discardedRecordingGeneration = generation
            activeRecording?.stop()
        }
        if (hadPendingZoomGate || hasPreparedRecorder) onRecordingStartCancelled?.invoke()
    }

    private fun acceptsRecordingEvents(startGeneration: Long): Boolean =
        activeRecordingGeneration == startGeneration &&
            discardedRecordingGeneration != startGeneration &&
            recordingStartGeneration == startGeneration

    private fun beginRecording(
        capture: VideoCapture<Recorder>,
        includeAudio: Boolean,
        startGeneration: Long
    ) {
        if (activeRecording != null || videoCapture !== capture) return
        requestedStopNotice = null
        val output = File(context.cacheDir, "topout_raw_${System.currentTimeMillis()}.mp4")
        activeOutputFile = output
        activeRecordingGeneration = startGeneration
        activeRecording = runCatching {
            val basePending = capture.output.prepareRecording(
                context,
                FileOutputOptions.Builder(output).build()
            )
            val pending = if (
                includeAudio &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { basePending.withAudioEnabled() }.getOrElse { basePending }
            } else {
                basePending
            }
            pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (acceptsRecordingEvents(startGeneration)) {
                            startedRecordingGeneration = startGeneration
                            onRecordingStarted?.invoke()
                        } else if (activeRecordingGeneration == startGeneration) {
                            activeRecording?.stop()
                        }
                    }
                    is VideoRecordEvent.Status -> {
                        if (acceptsRecordingEvents(startGeneration)) {
                            onRecordingProgress?.invoke(
                                event.recordingStats.recordedDurationNanos / 1_000_000_000.0
                            )
                        }
                    }
                    is VideoRecordEvent.Finalize -> handleFinalize(event, startGeneration, output)
                }
            }
        }.getOrElse { error ->
            output.delete()
            activeOutputFile = null
            activeRecordingGeneration = null
            startedRecordingGeneration = null
            onRecordingFinalized?.invoke(Result.failure(error))
            null
        }
    }

    fun stopRecording(notice: RecordingStopNotice? = null) {
        cancelPendingRecordingStart()
        if (notice != null && requestedStopNotice == null) requestedStopNotice = notice
        // 중지 즉시 분석기를 분리해 파일 마무리 중 CPU 사용을 줄인다.
        analysisUseCase?.clearAnalyzer()
        activeRecording?.stop()
    }

    private fun handleFinalize(
        event: VideoRecordEvent.Finalize,
        startGeneration: Long,
        output: File
    ) {
        val ownsActiveRecording = activeRecordingGeneration == startGeneration
        if (ownsActiveRecording) {
            activeRecording = null
            activeRecordingGeneration = null
            if (startedRecordingGeneration == startGeneration) startedRecordingGeneration = null
            if (activeOutputFile === output) activeOutputFile = null
        }
        if (discardedRecordingGeneration == startGeneration) {
            discardedRecordingGeneration = null
            output.delete()
            if (ownsActiveRecording) requestedStopNotice = null
            return
        }
        val file = output
        val requestedNotice = if (ownsActiveRecording) requestedStopNotice else null
        if (ownsActiveRecording) requestedStopNotice = null

        if (event.hasError()) {
            val recoverable = event.error in setOf(
                VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
                VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
            )
            val notice = requestedNotice ?: when (event.error) {
                VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> RecordingStopNotice.lowStorage(null)
                VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> RecordingStopNotice.CaptureInterruption
                else -> RecordingStopNotice.SessionError
            }
            val partialFile = file.takeIf {
                recoverable &&
                    it.exists() &&
                    it.length() > 0L &&
                    event.recordingStats.recordedDurationNanos > 0L
            }
            if (partialFile != null) {
                onRecordingFinalized?.invoke(
                    Result.success(
                        FinalizedRecording(
                            partialFile,
                            event.recordingStats.recordedDurationNanos / 1_000_000_000.0,
                            notice
                        )
                    )
                )
            } else {
                file.delete()
                onRecordingFinalized?.invoke(
                    Result.failure(event.cause ?: IllegalStateException(tr("error.recording_code", event.error)))
                )
            }
            return
        }

        if (!file.exists() || file.length() == 0L) {
            onRecordingFinalized?.invoke(Result.failure(IllegalStateException(tr("error.recording_file_missing"))))
            return
        }
        onRecordingFinalized?.invoke(
            Result.success(
                FinalizedRecording(
                    file,
                    event.recordingStats.recordedDurationNanos / 1_000_000_000.0,
                    requestedNotice
                )
            )
        )
    }

    private fun queryCapabilities(
        camera: Camera,
        profile: RearLensProfile
    ): CameraCapabilities {
        val qualities = Recorder.getVideoCapabilities(camera.cameraInfo).getSupportedQualities(DynamicRange.SDR)
        val manager = context.getSystemService(CameraManager::class.java)
        // CameraX가 선택한 실제 카메라의 기능을 조회한다.
        val cameraId = profile.physicalCameraId ?: runCatching {
            Camera2CameraInfo.from(camera.cameraInfo).cameraId
        }.getOrElse {
                manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.first()
            }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()

        fun matchesSize(candidate: Size, target: Size): Boolean =
            (candidate.width == target.width && candidate.height == target.height) ||
                (candidate.width == target.height && candidate.height == target.width)

        val recorderSizes = streamMap?.getOutputSizes(MediaRecorder::class.java).orEmpty()
        fun supportsRecordingSize(size: Size): Boolean = recorderSizes.any { matchesSize(it, size) }

        fun supports60(size: Size): Boolean {
            val hasRange = aeRanges.any { it.lower <= 60 && it.upper >= 60 }
            if (!hasRange || streamMap == null || !supportsRecordingSize(size)) return false
            val actualSize = recorderSizes.firstOrNull { matchesSize(it, size) } ?: size
            val regularDuration = runCatching {
                streamMap.getOutputMinFrameDuration(MediaRecorder::class.java, actualSize)
            }.getOrDefault(0L)
            val regular60 = regularDuration in 1L..17_500_000L
            val highSpeed60 = runCatching {
                streamMap.highSpeedVideoSizes.any { candidate ->
                    matchesSize(candidate, size) && streamMap.getHighSpeedVideoFpsRangesFor(candidate)
                        .any { it.lower <= 60 && it.upper >= 60 }
                }
            }.getOrDefault(false)
            return regular60 || highSpeed60
        }

        val map = buildMap {
            if (Quality.FHD in qualities && supportsRecordingSize(Size(1920, 1080))) {
                put(
                    RecordingResolution.FHD,
                    if (supports60(Size(1920, 1080))) setOf(RecordingFrameRate.FPS30, RecordingFrameRate.FPS60)
                    else setOf(RecordingFrameRate.FPS30)
                )
            }
            if (Quality.UHD in qualities && supportsRecordingSize(Size(3840, 2160))) {
                put(
                    RecordingResolution.UHD,
                    if (supports60(Size(3840, 2160))) setOf(RecordingFrameRate.FPS30, RecordingFrameRate.FPS60)
                    else setOf(RecordingFrameRate.FPS30)
                )
            }
        }
        return CameraCapabilities(
            supportedFrameRates = map.ifEmpty {
                mapOf(RecordingResolution.FHD to setOf(RecordingFrameRate.FPS30))
            }
        )
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                continuation.resume(future.get())
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }, mainExecutor)
    }

    private suspend fun awaitRequestedZoomApplied() {
        withTimeoutOrNull(ZOOM_APPLY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                applyRequestedZoomToBoundCamera {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    private suspend fun awaitPreviewStreaming(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        if (previewView.previewStreamState.value == PreviewView.StreamState.STREAMING) return

        val didStartStreaming = withTimeoutOrNull(PREVIEW_STREAM_READY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var observer: Observer<PreviewView.StreamState>
                observer = Observer { state ->
                    if (state == PreviewView.StreamState.STREAMING && continuation.isActive) {
                        previewView.previewStreamState.removeObserver(observer)
                        continuation.resume(Unit)
                    }
                }
                previewView.previewStreamState.observe(lifecycleOwner, observer)
                continuation.invokeOnCancellation {
                    mainExecutor.execute {
                        previewView.previewStreamState.removeObserver(observer)
                    }
                }
            }
        }
        if (didStartStreaming == null) {
            throw IllegalStateException(tr("error.preview_stream"))
        }
    }

    fun release() {
        unbind()
        analysisExecutor.shutdown()
    }
}
