package com.topout.cam.model

/**
 * User-facing copy shared with the completed iOS app.
 *
 * Keeping mode-dependent text in one place prevents the Android UI from silently drifting from
 * HoldVC/ReviewVC when feature switches are combined in different ways.
 */
object TopOutCopy {
    fun previewGuidance(settings: AppSettings): String = when {
        settings.requiresTopHoldSelection && settings.autoClipDetectionEnabled ->
            "벽과 매트 경계선 · 탑 홀드가 모두 보이게 맞춘 뒤 준비 완료"
        settings.requiresTopHoldSelection ->
            "탑홀드가 잘 보이게 맞춘 뒤 준비 완료"
        settings.autoClipDetectionEnabled ->
            "벽과 매트 경계선이 보이게 맞춘 뒤 준비 완료"
        else -> ""
    }

    fun readyButtonTitle(settings: AppSettings): String =
        if (settings.autoClipDetectionEnabled || settings.requiresTopHoldSelection) {
            "준비 완료"
        } else {
            "녹화 시작"
        }

    fun selectionGuidance(settings: AppSettings): String = when {
        settings.autoClipDetectionEnabled && settings.requiresTopHoldSelection ->
            "탑홀드를 탭하세요 · 파란 점으로 매트 경계를 보정하세요"
        settings.autoClipDetectionEnabled ->
            "파란 점으로 매트 경계를 보정한 뒤 녹화 시작을 눌러주세요"
        else -> "탑홀드를 탭하세요"
    }

    fun selectionTip(settings: AppSettings): String =
        if (settings.requiresTopHoldSelection) {
            "Tip. 작은 홀드는 확대해서 선택할 수 있어요"
        } else {
            "Tip. 파란 점은 위아래로 움직일 수 있어요"
        }

    fun countdownStatus(settings: AppSettings, remaining: Int? = null): String {
        if (remaining != null && remaining > 0) {
            return if (settings.autoClipDetectionEnabled) {
                "${remaining}초 후 녹화 시작 · 매트 보정 중"
            } else {
                "${remaining}초 후 녹화 시작"
            }
        }
        return if (settings.autoClipDetectionEnabled) {
            "녹화 준비 중 · 매트 보정 중"
        } else {
            "녹화 준비 중"
        }
    }

    fun activeRecordingStatus(settings: AppSettings): String =
        if (settings.autoClipDetectionEnabled) "모션 탐지 중 · 등반 시작 대기" else "녹화 중"

    fun climbingStatus(settings: AppSettings): String =
        if (settings.topHoldMaskingEnabled) {
            "등반 구간 감지 중 · 탑홀드 추적 중"
        } else {
            "등반 구간 감지 중 · 최고점 추적 중"
        }
}
