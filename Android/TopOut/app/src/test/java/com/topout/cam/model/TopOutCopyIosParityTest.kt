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
            "벽과 매트 경계선 · 탑 홀드가 모두 보이게 맞춘 뒤 준비 완료",
            TopOutCopy.previewGuidance(settings(auto = true, mask = true))
        )
        assertEquals(
            "탑홀드가 잘 보이게 맞춘 뒤 준비 완료",
            TopOutCopy.previewGuidance(settings(auto = false, mask = true))
        )
        assertEquals(
            "벽과 매트 경계선이 보이게 맞춘 뒤 준비 완료",
            TopOutCopy.previewGuidance(settings(auto = true, mask = false))
        )
        assertEquals("", TopOutCopy.previewGuidance(settings(auto = false, mask = false)))
    }

    @Test
    fun selectionCopyMatchesIos() {
        assertEquals(
            "탑홀드를 탭하세요 · 파란 점으로 매트 경계를 보정하세요",
            TopOutCopy.selectionGuidance(settings(auto = true, mask = true))
        )
        assertEquals(
            "파란 점으로 매트 경계를 보정한 뒤 녹화 시작을 눌러주세요",
            TopOutCopy.selectionGuidance(settings(auto = true, mask = false))
        )
        assertEquals(
            "탑홀드를 탭하세요",
            TopOutCopy.selectionGuidance(settings(auto = false, mask = true))
        )
        assertEquals(
            "Tip. 작은 홀드는 확대해서 선택할 수 있어요",
            TopOutCopy.selectionTip(settings(auto = false, mask = true))
        )
        assertEquals(
            "Tip. 파란 점은 위아래로 움직일 수 있어요",
            TopOutCopy.selectionTip(settings(auto = true, mask = false))
        )
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
        assertEquals(
            "등반 구간 감지 중 · 탑홀드 추적 중",
            TopOutCopy.climbingStatus(settings(auto = true, mask = true))
        )
        assertEquals(
            "등반 구간 감지 중 · 최고점 추적 중",
            TopOutCopy.climbingStatus(settings(auto = true, mask = false))
        )
    }
}
