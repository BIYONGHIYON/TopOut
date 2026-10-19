package com.topout.cam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

// 후면 렌즈 탐색과 CameraEngine의 렌즈 전환이 함께 쓰는 기준값이다.
internal const val LENS_BOUNDARY_EPSILON = 0.001f
internal const val LENS_SWITCH_GAP = 0.1f
internal const val LENS_SWITCH_MAX_GAP_FRACTION = 0.25f
internal const val FULL_FRAME_SENSOR_WIDTH_MM = 36f
internal const val EXPECTED_WIDE_EQUIVALENT_MM = 26f

/**
 * A display zoom anchor is backed by one actual rear camera. For example, an ultra-wide
 * camera is exposed as the 0.5x anchor, the normal camera as 1.0x, and a tele camera as 3.0x.
 * Zoom inside a section is digital zoom on that camera; crossing an anchor rebinds the matching
 * camera, then the active gesture resumes from that anchor.
 */
internal data class RearLensProfile(
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

/**
 * Discovers every public rear camera, computes its 35-mm-equivalent focal length, and maps it
 * to display anchors such as 0.5x, 1.0x, 3.0x, and 5.0x. Unlike the previous implementation,
 * this does not require one logical camera to advertise a sub-1x CameraX zoom range.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun discoverRearLensProfiles(
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

@OptIn(ExperimentalCamera2Interop::class)
internal fun exactCameraSelector(cameraId: String): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            cameraInfos.filter { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }
                    .getOrDefault(false)
            }
        }
        .build()
