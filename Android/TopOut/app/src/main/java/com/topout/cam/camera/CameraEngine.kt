package com.topout.cam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.media.MediaRecorder
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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

    /** Camera IDs and capabilities are stable for the process lifetime. */
    private var lensDiscoveryComplete = false
    private var cachedCapabilities: CameraCapabilities? = null

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

    /** The measured PreviewView is the frame source of truth; the ratio fallback is only for startup. */
    private fun previewViewPort(
        previewView: PreviewView,
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean,
        targetRotation: Int
    ): ViewPort = previewView.viewPort
        ?: selectedFrameViewPort(aspectRatio, isLandscape, targetRotation)

    /**
     * Binds one long-lived WYSIWYG session. Recording never tears this session down, so the
     * selected lens, zoom and crop cannot change between the preview and the first encoded frame.
     */
    private fun bindUnifiedSession(
        cameraProvider: ProcessCameraProvider,
        config: CameraSessionConfig,
        profile: RearLensProfile
    ): Camera {
        val quality = when (config.resolution) {
            RecordingResolution.FHD -> Quality.FHD
            RecordingResolution.UHD -> Quality.UHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .setTargetVideoEncodingBitRate(
                config.resolution.estimatedBitrate(config.frameRate, config.aspectRatio).toInt()
            )
            .build()
        val captureBuilder = VideoCapture.Builder(recorder)
            .setTargetRotation(config.targetRotation)
            .setTargetFrameRate(Range(config.frameRate.fps, config.frameRate.fps))
        val previewBuilder = Preview.Builder()
            .setTargetRotation(config.targetRotation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            profile.physicalCameraId?.let { physicalId ->
                Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalId)
                Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalId)
            }
        }
        val capture = captureBuilder.build()
        // Keep Preview as part of the unified CameraX session, but allow its SurfaceProvider to
        // be detached during recording. VideoCapture and ImageAnalysis stay bound and continue
        // working while CameraX stops producing the display-only preview stream.
        val preview = previewBuilder.build().also { useCase ->
            useCase.setSurfaceProvider(config.previewView.surfaceProvider)
        }
        previewRenderingEnabled = true

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

        fun tryBind(analysis: ImageAnalysis?): Camera? {
            cameraProvider.unbindAll()
            val groupBuilder = UseCaseGroup.Builder()
                .setViewPort(config.viewPort)
                .addUseCase(preview)
                .addUseCase(capture)
            if (analysis != null) groupBuilder.addUseCase(analysis)
            return try {
                cameraProvider.bindToLifecycle(
                    config.lifecycleOwner,
                    profile.selector,
                    groupBuilder.build()
                )
            } catch (_: Throwable) {
                analysis?.clearAnalyzer()
                cameraProvider.unbindAll()
                null
            }
        }

        var bound: Camera? = null
        var selectedAnalysis: ImageAnalysis? = null
        for (targetSize in recordingAnalysisTargetSizes(config.aspectRatio, config.isLandscape)) {
            val analysis = newAnalysis(targetSize)
            val candidate = tryBind(analysis)
            if (candidate != null) {
                bound = candidate
                selectedAnalysis = analysis
                break
            }
        }

        // Live camera and recorder are never separated. If the device cannot expose a third YUV
        // stream, automatic range analysis is dropped without changing the visible/recorded frame.
        val boundCamera = bound ?: run {
            val fallbackCamera = tryBind(null)
                ?: throw IllegalStateException("이 기기에서 선택한 카메라 녹화 구성을 시작할 수 없습니다.")
            onRecordingAnalysisUnavailable?.invoke()
            fallbackCamera
        }
        camera = boundCamera
        previewUseCase = preview
        videoCapture = capture
        analysisUseCase = selectedAnalysis
        activeLensProfile = profile
        lastSubmittedZoom = Float.NaN
        return boundCamera
    }

    fun isAnalysisAvailable(): Boolean = analysisUseCase != null

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: RecordingResolution,
        frameRate: RecordingFrameRate,
        aspectRatio: RecordingAspectRatio,
        isLandscape: Boolean,
        analyzer: ImageAnalysis.Analyzer
    ): CameraCapabilities {
        val cameraProvider = provider ?: awaitCameraProvider().also { provider = it }
        cameraProvider.unbindAll()

        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
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
                        anchorRatio = fallbackMin.coerceAtLeast(1f),
                        nativeMinZoom = fallbackMin,
                        nativeMaxZoom = fallbackMax,
                        equivalentFocalLength = EXPECTED_WIDE_EQUIVALENT_MM,
                        isLogicalMultiCamera = false
                    )
                )
                cameraProvider.unbindAll()
            }

            effectiveLensZoomRatios = rearLensProfiles.map { it.anchorRatio }
            effectiveMinZoom = effectiveLensZoomRatios.first()
            val lastLens = rearLensProfiles.last()
            effectiveMaxZoom = min(
                TopOutConfig.CameraMaxZoom,
                lastLens.anchorRatio * lastLens.nativeMaxZoom
            ).coerceAtLeast(effectiveMinZoom)

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
        var capabilities = cachedCapabilities ?: run {
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
                    effectiveLensZoomRatios = rearLensProfiles.map { it.anchorRatio }
                    effectiveMinZoom = effectiveLensZoomRatios.first()
                    val lastLens = rearLensProfiles.last()
                    effectiveMaxZoom = min(
                        TopOutConfig.CameraMaxZoom,
                        lastLens.anchorRatio * lastLens.nativeMaxZoom
                    ).coerceAtLeast(effectiveMinZoom)
                    requestedZoom = effectiveMinZoom
                    null
                }
            }
            val probe = probeResult ?: throw (lastError
                ?: IllegalStateException("사용 가능한 후면 카메라를 찾지 못했습니다."))
            queryCapabilities(probe).also {
                cachedCapabilities = it
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
            viewPort = previewViewPort(previewView, aspectRatio, isLandscape, targetRotation)
        )

        val boundCamera = try {
            bindUnifiedSession(cameraProvider, config, selectedProfile)
        } catch (firstError: Throwable) {
            cameraProvider.unbindAll()
            rearLensProfiles = rearLensProfiles.filterNot { it.cameraId == selectedProfile.cameraId }
            if (rearLensProfiles.isEmpty()) throw firstError

            selectedProfile = rearLensProfiles.first()
            activeLensProfile = selectedProfile
            effectiveLensZoomRatios = rearLensProfiles.map { it.anchorRatio }
            effectiveMinZoom = effectiveLensZoomRatios.first()
            val lastLens = rearLensProfiles.last()
            effectiveMaxZoom = min(
                TopOutConfig.CameraMaxZoom,
                lastLens.anchorRatio * lastLens.nativeMaxZoom
            ).coerceAtLeast(effectiveMinZoom)
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

        camera = boundCamera
        sessionConfig = config

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
        val request = if (
            profile.physicalCameraId != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            // A physical member of a logical camera uses the logical camera's public zoom
            // coordinate: 0.5 is the full ultra-wide view, 1.0 is the wide anchor, and 3.0 is
            // the tele anchor. Dividing by the anchor here would incorrectly crop it.
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, target)
                .build()
            Camera2CameraControl.from(activeCamera.cameraControl)
                .setCaptureRequestOptions(options)
        } else {
            // A separately bindable camera has its own native 1.0x coordinate.
            activeCamera.cameraControl.setZoomRatio(nativeTarget)
        }
        return request
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
                camera = bound
                activeLensProfile = profile
                return bound
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
                    nativeMinZoom = (zoomState?.minZoomRatio ?: 1f).coerceAtLeast(1f),
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

    private fun queryCapabilities(camera: Camera): CameraCapabilities {
        val qualities = Recorder.getVideoCapabilities(camera.cameraInfo).getSupportedQualities(DynamicRange.SDR)
        val manager = context.getSystemService(CameraManager::class.java)
        // Query the exact physical/logical camera selected by CameraX. Picking the first back camera
        // can report the ultra-wide lens capabilities on multi-camera devices such as the Z Flip3.
        val cameraId = runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }
            .getOrElse {
                manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: manager.cameraIdList.first()
            }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()

        fun supports60(size: Size): Boolean {
            val hasRange = aeRanges.any { it.lower <= 60 && it.upper >= 60 }
            if (!hasRange || streamMap == null) return false
            val recorderSizes = streamMap.getOutputSizes(MediaRecorder::class.java).orEmpty()
            if (recorderSizes.none { it.width == size.width && it.height == size.height }) return false
            val regularDuration = runCatching {
                streamMap.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            }.getOrDefault(0L)
            val regular60 = regularDuration in 1L..17_500_000L
            val highSpeed60 = runCatching {
                streamMap.highSpeedVideoSizes.any { candidate ->
                    candidate == size && streamMap.getHighSpeedVideoFpsRangesFor(candidate)
                        .any { it.lower <= 60 && it.upper >= 60 }
                }
            }.getOrDefault(false)
            return regular60 || highSpeed60
        }

        val map = buildMap {
            if (Quality.FHD in qualities) {
                put(
                    RecordingResolution.FHD,
                    if (supports60(Size(1920, 1080))) setOf(RecordingFrameRate.FPS30, RecordingFrameRate.FPS60)
                    else setOf(RecordingFrameRate.FPS30)
                )
            }
            if (Quality.UHD in qualities) {
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
