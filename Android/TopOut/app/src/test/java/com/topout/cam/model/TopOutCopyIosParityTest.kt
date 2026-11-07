package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TopOutCopyIosParityTest {
    private fun settings(auto: Boolean, mask: Boolean) = AppSettings(
        autoClipDetectionEnabled = auto,
        topHoldMaskingEnabled = mask
    )

    @Test
    fun previewGuidanceMatchesAllIosFeatureCombinations() {
        assertEquals(
            "벽과 매트 경계선, 탑홀드가 모두 보이게 맞춘 뒤 준비 완료",
            TopOutCopy.previewGuidance(settings(auto = true, mask = true))
        )
        assertEquals(
            "탑홀드가 보이게 휴대폰을 거치하세요",
            TopOutCopy.previewGuidance(settings(auto = false, mask = true))
        )
        assertEquals(
            "벽과 매트 경계선이 보이게 맞춘 뒤 준비 완료",
            TopOutCopy.previewGuidance(settings(auto = true, mask = false))
        )
        assertEquals("", TopOutCopy.previewGuidance(settings(auto = false, mask = false)))
    }

    @Test
    fun selectionTipMatchesIos() {
        // 준비 완료 뒤 보정 화면 Tip. 탑홀드는 결과 화면에서 고르므로 녹화 전에는 경계선 보정만 다룬다.
        assertEquals("Tip. 파란 점을 위아래로 움직여 경계선을 보정할 수 있어요", TopOutCopy.selectionTip())
    }

    @Test
    fun recordingCopyMatchesIos() {
        assertEquals(
            "3초 후 녹화 시작",
            TopOutCopy.countdownStatus(settings(auto = true, mask = false), 3)
        )
        assertEquals(
            "3초 후 녹화 시작",
            TopOutCopy.countdownStatus(settings(auto = false, mask = false), 3)
        )
        assertEquals(
            "모션 탐지 중 · 등반 시작 대기",
            TopOutCopy.activeRecordingStatus(settings(auto = true, mask = false))
        )
        assertEquals("녹화 중", TopOutCopy.activeRecordingStatus(settings(auto = false, mask = false)))
        assertEquals("등반 구간 감지 중 · 최고점 추적 중", TopOutCopy.climbingStatus())
    }
}
