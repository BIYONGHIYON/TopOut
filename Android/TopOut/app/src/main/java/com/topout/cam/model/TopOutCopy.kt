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
        settings.requiresTopHoldSelection && settings.autoClipDetectionEnabled ->
            tr("camera.preview_both")
        settings.requiresTopHoldSelection ->
            tr("camera.preview_hold")
        settings.autoClipDetectionEnabled ->
            tr("camera.preview_mat")
        else -> ""
    }

    fun readyButtonTitle(settings: AppSettings): String =
        if (settings.autoClipDetectionEnabled || settings.requiresTopHoldSelection) {
            tr("camera.ready")
        } else {
            tr("camera.start_recording")
        }

    fun selectionGuidance(settings: AppSettings): String = when {
        settings.autoClipDetectionEnabled && settings.requiresTopHoldSelection ->
            tr("camera.selection_both")
        settings.autoClipDetectionEnabled ->
            tr("camera.selection_mat")
        else -> tr("camera.selection_hold")
    }

    fun selectionTip(settings: AppSettings): String =
        if (settings.requiresTopHoldSelection) {
            ""
        } else {
            tr("camera.tip_adjust_line")
        }

    fun countdownStatus(settings: AppSettings, remaining: Int? = null): String {
        if (remaining != null && remaining > 0) {
            return tr("camera.countdown", remaining)
        }
        return tr("camera.preparing")
    }

    fun activeRecordingStatus(settings: AppSettings): String =
        if (settings.autoClipDetectionEnabled) tr("camera.waiting_motion") else tr("camera.recording")

    fun climbingStatus(settings: AppSettings): String =
        if (settings.topHoldMaskingEnabled) {
            tr("camera.tracking_hold")
        } else {
            tr("camera.tracking_peak")
        }
}
