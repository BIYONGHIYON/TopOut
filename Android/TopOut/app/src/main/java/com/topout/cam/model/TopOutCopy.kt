package com.topout.cam.model

import com.topout.cam.localization.tr

/**
 * User-facing copy shared with the completed iOS app.
 *
 * Keeping mode-dependent text in one place prevents the Android UI from silently drifting from
 * HoldVC/ReviewVC when feature switches are combined in different ways.
 */
object TopOutCopy {
    fun previewGuidance(settings: AppSettings): String = when {
        settings.topHoldMaskingEnabled && settings.autoClipDetectionEnabled ->
            tr("camera.preview_both")
        settings.topHoldMaskingEnabled ->
            tr("camera.preview_hold")
        settings.autoClipDetectionEnabled ->
            tr("camera.preview_mat")
        else -> ""
    }

    /** 준비 완료 뒤 보정 화면 Tip. */
    fun selectionTip(): String = tr("camera.tip_adjust_line")

    fun countdownStatus(settings: AppSettings, remaining: Int? = null): String {
        if (remaining != null && remaining > 0) {
            return tr("camera.countdown", remaining)
        }
        return tr("camera.preparing")
    }

    fun activeRecordingStatus(settings: AppSettings): String =
        if (settings.autoClipDetectionEnabled) tr("camera.waiting_motion") else tr("camera.recording")

    /** 탑홀드는 결과 화면에서만 고르므로 녹화 중에는 항상 가장 높이 올라간 순간을 추적한다. */
    fun climbingStatus(): String = tr("camera.tracking_peak")
}
