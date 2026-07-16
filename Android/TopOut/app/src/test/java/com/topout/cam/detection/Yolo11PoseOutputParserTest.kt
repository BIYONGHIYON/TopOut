package com.topout.cam.detection

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Yolo11PoseOutputParserTest {
    private val transform = YoloLetterboxTransform(
        sourceWidth = 640,
        sourceHeight = 640,
        inputSize = 640,
        scale = 1f,
        padLeft = 0f,
        padTop = 0f
    )

    @Test
    fun parsesChannelsFirstOutput() {
        val candidateCount = 2
        val data = FloatArray(56 * candidateCount)
        fun set(candidate: Int, channel: Int, value: Float) {
            data[channel * candidateCount + candidate] = value
        }
        set(0, 0, 320f)
        set(0, 1, 300f)
        set(0, 2, 200f)
        set(0, 3, 400f)
        set(0, 4, 0.9f)
        for (keypoint in 0 until YoloPoseLandmark.COUNT) {
            val offset = 5 + keypoint * 3
            set(0, offset, 200f + keypoint)
            set(0, offset + 1, 100f + keypoint)
            set(0, offset + 2, 0.8f)
        }
        set(1, 4, 0.1f)

        val result = Yolo11PoseOutputParser.parse(
            data = data,
            shape = longArrayOf(1, 56, candidateCount.toLong()),
            transform = transform,
            personConfidenceThreshold = 0.25f,
            nmsIouThreshold = 0.45f
        )

        assertEquals(1, result.size)
        assertEquals(17, result.first().keypoints.size)
        assertEquals(0.9f, result.first().confidence, 0.0001f)
        assertEquals(200f / 640f, result.first().keypoints.getValue(0).position.x, 0.0001f)
    }

    @Test
    fun parsesCandidatesFirstOutput() {
        val data = FloatArray(56)
        data[0] = 320f
        data[1] = 320f
        data[2] = 100f
        data[3] = 200f
        data[4] = 0.8f
        for (keypoint in 0 until YoloPoseLandmark.COUNT) {
            val offset = 5 + keypoint * 3
            data[offset] = 320f
            data[offset + 1] = 320f
            data[offset + 2] = 0.7f
        }

        val result = Yolo11PoseOutputParser.parse(
            data = data,
            shape = longArrayOf(1, 1, 56),
            transform = transform,
            personConfidenceThreshold = 0.25f,
            nmsIouThreshold = 0.45f
        )

        assertEquals(1, result.size)
        assertEquals(0.5f, result.first().keypoints.getValue(0).position.x, 0.0001f)
        assertEquals(0.5f, result.first().keypoints.getValue(0).position.y, 0.0001f)
    }

    @Test
    fun letterboxMappingRemovesVerticalPadding() {
        val wideTransform = YoloLetterboxTransform(
            sourceWidth = 640,
            sourceHeight = 320,
            inputSize = 640,
            scale = 1f,
            padLeft = 0f,
            padTop = 160f
        )

        val point = wideTransform.modelPointToNormalized(320f, 320f)

        assertEquals(0.5f, point.x, 0.0001f)
        assertEquals(0.5f, point.y, 0.0001f)
    }

    @Test
    fun trackingAssociationKeepsNearbyPerson() {
        val previous = Rect(0.1f, 0.1f, 0.3f, 0.7f)
        val nearby = YoloPoseResult(previous, 0.70f, emptyMap())
        val distant = YoloPoseResult(Rect(0.7f, 0.1f, 0.9f, 0.7f), 0.78f, emptyMap())

        val selected = Yolo11PoseOutputParser.selectPrimary(
            candidates = listOf(nearby, distant),
            previousBox = previous,
            useTrackingAssociation = true
        )

        assertNotNull(selected)
        assertTrue(selected === nearby)
    }
}
