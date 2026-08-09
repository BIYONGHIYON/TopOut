package com.topout.cam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.media.MediaRecorder
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
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var previewRenderingEnabled = true
    private var analysisUseCase: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
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
     * Zoom inside a section is digital zoom on that camera; crossing an anchor requires a new
     * gesture and rebinds the matching camera.
     */
    private data class RearLensProfile(
        val cameraId: String,
        val selector: CameraSelector,
        val physicalCameraId: String? = null,
        val anchorRatio: Float,
        val nativeMinZoom: Float,
        val nativeMaxZoom: Float,
        val equivalentFocalLength: Float,
        val isLogicalMultiCamera: Boolean
    )

    private var rearLensProfiles: List<RearLensProfile> = emptyList()
    private var activeLensProfile: RearLensProfile? = null
    private var pendingLensProfile: RearLensProfile? = null
    private var pendingLensZoom: Float? = null
    private var lensSwitchInProgress = false
    private var lensSwitchGeneration = 0L
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
    var onRecordingProgress: ((Double) -> Unit)? = null
    var onRecordingFinalized: ((Result<FinalizedRecording>) -> Unit)? = null
    var onRecordingAnalysisUnavailable: (() -> Unit)? = null
    var onZoomCorrected: ((Float) -> Unit)? = null
    var onLensSwitchingChanged: ((Boolean) -> Unit)? = null

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

        effectiveMinZoom = rearLensProfiles.minOf { profile ->
            profile.anchorRatio * profile.nativeMinZoom
        }.coerceAtLeast(TopOutConfig.MinZoom)

        // The first profile owns everything below its optical anchor. If that profile is a logical
        // camera with native 0.5x support, 1.0x is not a camera-switch boundary and must not force
        // the user to release/re-pinch there. Remaining profile anchors are real rebind points.
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

            // Never advertise/accept 60 fps when CameraX cannot guarantee 60 for this exact
            // Preview + VideoCapture (+ Analysis) configuration. This prevents OEMs from quietly
            // running a user-selected 60-fps session at 30 fps.
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
                // 30 fps remains the compatibility floor. Older/quirky OEMs may not expose an
                // exact 30..30 range even though their normal SDR recording pipeline is 30 fps.
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
            // Automatic clip detection needs ImageAnalysis. Prefer an exact 30-fps session with
            // analysis over a nominal 60-fps recording that would have to drop analysis entirely.
            for (rate in ratesToTry) {
                for (targetSize in recordingAnalysisTargetSizes(config.aspectRatio, config.isLandscape)) {
                    tryBind(rate, targetSize)?.let { return accept(it, rate) }
                }
            }

            // Keep Preview + VideoCapture usable even on OEM stream combinations that reject YUV
            // analysis. The ViewModel then blocks auto-detection recording with a clear message.
            for (rate in ratesToTry.asReversed()) {
                val fallback = tryBind(rate, null) ?: continue
                onRecordingAnalysisUnavailable?.invoke()
                return accept(fallback, rate)
            }
        } else {
            // Manual/top-hold-only recording does not need a continuous ImageAnalysis stream.
            // Binding only Preview + VideoCapture avoids unnecessarily disqualifying exact 60 fps
            // on devices whose three-stream combination is limited to 30 fps.
            for (rate in ratesToTry) {
                tryBind(rate, null)?.let { return accept(it, rate) }
            }
        }

        throw IllegalStateException("이 기기에서 선택한 카메라 녹화 구성을 시작할 수 없습니다.")
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

        // Enumerate the actual rear cameras instead of assuming DEFAULT_BACK_CAMERA can reach
        // 0.5x. On phones that expose ultra-wide as a separate Camera2/CameraX ID, this is the
        // only reliable way to start on the real ultra-wide lens.
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

            // Cold start always selects the lowest real optical anchor (normally 0.5x). Later
            // lifecycle rebinds preserve the user's current requested zoom.
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
                ?: IllegalStateException("사용 가능한 후면 카메라를 찾지 못했습니다."))
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

        // Refresh the base hardware capabilities for the lens that actually bound. Exact 60-fps
        // support is session-specific, so keep that knowledge separately instead of poisoning the
        // lens cache when only one aspect/orientation/analysis combination cannot sustain 60 fps.
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
        applyRequestedZoomToBoundCamera()
        return capabilities
    }

    fun unbind() {
        activeRecording?.stop()
        activeRecording = null
        provider?.unbindAll()
        camera = null
        previewUseCase = null
        previewRenderingEnabled = true
        videoCapture = null
        analysisUseCase = null
        sessionConfig = null
        pendingLensProfile = null
        pendingLensZoom = null
        lensSwitchGeneration += 1
        if (lensSwitchInProgress) onLensSwitchingChanged?.invoke(false)
        lensSwitchInProgress = false
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
     * The gesture controller already prevents crossing more than one optical boundary per pinch.
     * Here the display ratio is mapped to the camera whose optical anchor owns that section.
     */
    fun setZoom(zoom: Float) {
        // Pinch samples continue arriving while CameraX tears down and opens the next camera.
        // Never let those samples overwrite the transition boundary zoom.
        if (lensSwitchInProgress) return

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
            // Start the recorder only after CameraControl confirms the current preview zoom, so
            // the first encoded frame uses the same zoom already shown to the user.
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
        // Every bound lens uses its own native zoom coordinate. In particular, a physical
        // ultra-wide pinned through Camera2Interop must start at native 1.0x; sending the public
        // display ratio (0.5) through CONTROL_ZOOM_RATIO can be outside the logical camera's
        // supported range on Samsung/OEM devices and is a common source of broken 0.5x startup.
        return activeCamera.cameraControl.setZoomRatio(nativeTarget)
    }

    private fun profileForDisplayZoom(zoom: Float): RearLensProfile? = rearLensProfiles
        .lastOrNull { zoom + LENS_BOUNDARY_EPSILON >= it.anchorRatio }
        ?: rearLensProfiles.firstOrNull()

    private fun requestLensSwitch(
        targetProfile: RearLensProfile,
        transitionZoom: Float
    ) {
        if (activeRecording != null || lensSwitchInProgress) return
        pendingLensProfile = targetProfile
        pendingLensZoom = transitionZoom.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        performPendingLensSwitch()
    }

    /**
     * CameraX cannot change an exact camera ID in place. The display zoom is frozen at the first
     * boundary sample that requested the switch. Input remains locked until PreviewView reports
     * that the newly bound camera is STREAMING, then the active pinch is rebased from that ratio.
     */
    private fun performPendingLensSwitch() {
        val cameraProvider = provider ?: return
        val config = sessionConfig ?: return
        val targetProfile = pendingLensProfile ?: return
        val transitionZoom = pendingLensZoom
            ?.coerceIn(effectiveMinZoom, effectiveMaxZoom)
            ?: targetProfile.anchorRatio.coerceIn(effectiveMinZoom, effectiveMaxZoom)
        val previousProfile = activeLensProfile

        if (previousProfile?.cameraId == targetProfile.cameraId) {
            pendingLensProfile = null
            pendingLensZoom = null
            requestedZoom = transitionZoom
            submitZoomImmediately()
            onZoomCorrected?.invoke(requestedZoom)
            return
        }

        pendingLensProfile = null
        pendingLensZoom = null
        requestedZoom = transitionZoom
        onZoomCorrected?.invoke(requestedZoom)

        lensSwitchInProgress = true
        lensSwitchGeneration += 1
        val generation = lensSwitchGeneration
        onLensSwitchingChanged?.invoke(true)

        mainExecutor.execute {
            var switchFinished = false
            var sawIdleAfterSwitchRequest =
                config.previewView.previewStreamState.value != PreviewView.StreamState.STREAMING
            var streamObserver: Observer<PreviewView.StreamState>? = null

            fun finishSwitch() {
                if (switchFinished || generation != lensSwitchGeneration) return
                switchFinished = true
                streamObserver?.let { config.previewView.previewStreamState.removeObserver(it) }
                lensSwitchInProgress = false
                onLensSwitchingChanged?.invoke(false)
            }

            val observer = Observer<PreviewView.StreamState> { streamState ->
                when (streamState) {
                    PreviewView.StreamState.IDLE -> sawIdleAfterSwitchRequest = true
                    PreviewView.StreamState.STREAMING -> {
                        if (sawIdleAfterSwitchRequest) finishSwitch()
                    }
                }
            }
            streamObserver = observer
            config.previewView.previewStreamState.observe(
                config.lifecycleOwner,
                observer
            )
            config.previewView.postDelayed(
                { finishSwitch() },
                LENS_SWITCH_READY_TIMEOUT_MS
            )

            fun bindProfile(profile: RearLensProfile): Camera {
                val bound = bindUnifiedSession(cameraProvider, config, profile)
                // A zoom gesture must never silently turn a user-selected exact 60-fps session
                // into 30 fps. If the target physical lens cannot sustain the current session,
                // reject that lens transition and restore the previous camera instead.
                if (bound.frameRate != config.frameRate) {
                    cameraProvider.unbindAll()
                    throw IllegalStateException("현재 촬영 설정에서는 이 렌즈 전환을 사용할 수 없습니다.")
                }
                camera = bound.camera
                activeLensProfile = profile
                sessionConfig = config
                return bound.camera
            }

            try {
                bindProfile(targetProfile)
                // Always initialize the newly opened lens at the boundary that triggered it.
                // Samples generated while this camera was loading were ignored by setZoom().
                requestedZoom = transitionZoom
                submitZoomImmediately()
                onZoomCorrected?.invoke(requestedZoom)
            } catch (_: Throwable) {
                if (previousProfile != null) {
                    val restored = runCatching {
                        bindProfile(previousProfile)
                        val nextAnchor = rearLensProfiles
                            .firstOrNull { it.anchorRatio > previousProfile.anchorRatio }
                            ?.anchorRatio
                        requestedZoom = if (nextAnchor == null) {
                            transitionZoom.coerceAtLeast(previousProfile.anchorRatio)
                        } else {
                            transitionZoom.coerceIn(
                                previousProfile.anchorRatio,
                                (nextAnchor - LENS_SWITCH_GAP)
                                    .coerceAtLeast(previousProfile.anchorRatio)
                            )
                        }
                        submitZoomImmediately()
                        onZoomCorrected?.invoke(requestedZoom)
                    }.isSuccess
                    if (!restored) finishSwitch()
                } else {
                    finishSwitch()
                }
            }
        }
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
            val isLogicalMultiCamera: Boolean
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
            val publicCameraId = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
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
            val zoomState = info.zoomState.value
            val camera2Max = characteristics
                .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: TopOutConfig.CameraMaxZoom
            equivalentFocalLength(characteristics)?.let { equivalent ->
                candidates += Candidate(
                    cameraId = publicCameraId,
                    selector = selector,
                    physicalCameraId = null,
                    equivalentFocalLength = equivalent,
                    nativeMinZoom = (zoomState?.minZoomRatio ?: 1f)
                        .coerceAtLeast(TopOutConfig.MinZoom),
                    nativeMaxZoom = min(
                        TopOutConfig.CameraMaxZoom,
                        zoomState?.maxZoomRatio ?: camera2Max
                    ).coerceAtLeast(1f),
                    isLogicalMultiCamera = logical
                )
            }

            // Some OEMs hide ultra-wide/tele IDs from CameraX's public camera list but expose
            // them as physical members of a logical rear camera. Pin all use cases to that ID.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logical) {
                characteristics.physicalCameraIds.forEach physicalLoop@ { physicalId ->
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

        return candidates
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
                    isLogicalMultiCamera = candidate.isLogicalMultiCamera
                )
            }
            .filter { it.anchorRatio in TopOutConfig.MinZoom..TopOutConfig.CameraMaxZoom }
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
                        isLogicalMultiCamera = reference.isLogicalMultiCamera
                    )
                }
            }
            .distinctBy { it.anchorRatio }
            .sortedBy { it.anchorRatio }
            .let { profiles ->
                // Modern Samsung/Pixel logical rear cameras already expose their lens system as a
                // continuous CameraControl zoom range (commonly 0.5x..10x+). In that case let the
                // OEM logical camera perform all ultra-wide/wide/tele hand-offs. Rebinding pinned
                // physical members is slower, can break exact FPS negotiation, and used to send an
                // invalid public 0.5 ratio into a physical stream. Physical profiles remain only as
                // the fallback for devices whose logical CameraX camera cannot reach below 1x.
                val seamlessLogical = profiles.firstOrNull { profile ->
                    abs(profile.anchorRatio - 1f) <= LENS_BOUNDARY_EPSILON &&
                        profile.physicalCameraId == null &&
                        profile.isLogicalMultiCamera &&
                        profile.nativeMinZoom < 0.95f
                }
                if (seamlessLogical == null) profiles else listOf(seamlessLogical)
            }
    }

    private fun snapPhysicalLensAnchor(rawRatio: Float): Float {
        if (rawRatio < 0.8f) {
            // Consumer camera UIs conventionally expose 12-14 mm ultra-wide cameras as 0.5x.
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
        const val LENS_SWITCH_READY_TIMEOUT_MS = 2_500L
        const val FULL_FRAME_SENSOR_WIDTH_MM = 36f
        const val EXPECTED_WIDE_EQUIVALENT_MM = 26f
    }

    fun startRecording(includeAudio: Boolean = true) {
        if (activeRecording != null) return
        val capture = videoCapture ?: run {
            onRecordingFinalized?.invoke(
                Result.failure(IllegalStateException("카메라 녹화 세션이 준비되지 않았습니다."))
            )
            return
        }
        // The same bound session has remained visible since preview. Re-submit only the current
        // zoom and start encoding after CameraControl acknowledges it; no use case is rebound here.
        applyRequestedZoomToBoundCamera {
            beginRecording(capture, includeAudio)
        }
    }

    private fun beginRecording(capture: VideoCapture<Recorder>, includeAudio: Boolean) {
        requestedStopNotice = null
        val output = File(context.cacheDir, "topout_raw_${System.currentTimeMillis()}.mp4")
        activeOutputFile = output
        val basePending = capture.output.prepareRecording(context, FileOutputOptions.Builder(output).build())
        val pending = if (
            includeAudio &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { basePending.withAudioEnabled() }.getOrElse { basePending }
        } else {
            basePending
        }
        activeRecording = runCatching {
            pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onRecordingStarted?.invoke()
                    is VideoRecordEvent.Status -> onRecordingProgress?.invoke(
                        event.recordingStats.recordedDurationNanos / 1_000_000_000.0
                    )
                    is VideoRecordEvent.Finalize -> handleFinalize(event)
                }
            }
        }.getOrElse { error ->
            output.delete()
            activeOutputFile = null
            onRecordingFinalized?.invoke(Result.failure(error))
            null
        }
    }

    fun stopRecording(notice: RecordingStopNotice? = null) {
        if (notice != null && requestedStopNotice == null) requestedStopNotice = notice
        // No more automatic-detection frames are needed once the user taps stop. Detaching the
        // analyzer releases CPU pressure while Recorder flushes the encoder and closes the MP4.
        analysisUseCase?.clearAnalyzer()
        activeRecording?.stop()
    }

    private fun handleFinalize(event: VideoRecordEvent.Finalize) {
        val file = activeOutputFile
        activeRecording = null
        activeOutputFile = null
        val requestedNotice = requestedStopNotice
        requestedStopNotice = null

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
            val partialFile = file?.takeIf {
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
                file?.delete()
                onRecordingFinalized?.invoke(
                    Result.failure(event.cause ?: IllegalStateException("녹화를 완료하지 못했습니다. 오류 코드: ${event.error}"))
                )
            }
            return
        }

        if (file == null || !file.exists() || file.length() == 0L) {
            onRecordingFinalized?.invoke(Result.failure(IllegalStateException("녹화 파일이 생성되지 않았습니다.")))
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
        // Query the exact physical/logical camera selected by CameraX. Picking the first back camera
        // can report the ultra-wide lens capabilities on multi-camera devices such as the Z Flip3.
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

    fun release() {
        unbind()
        analysisExecutor.shutdown()
    }
}
