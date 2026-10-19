package com.topout.cam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import com.topout.cam.model.CameraCapabilities
import com.topout.cam.model.RecordingFrameRate
import com.topout.cam.model.RecordingResolution

@Suppress("UsePropertyAccessSyntax")
@OptIn(ExperimentalCamera2Interop::class)
internal fun queryCameraCapabilities(
    context: Context,
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
