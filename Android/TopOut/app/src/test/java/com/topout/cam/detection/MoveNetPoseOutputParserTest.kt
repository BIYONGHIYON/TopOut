package com.topout.cam.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveNetPoseOutputParserTest {
    @Test
    fun parseMapsModelCoordinatesToSourceCoordinates() {
        val values = FloatArray(PoseLandmark.COUNT * 3)
        for (index in 0 until PoseLandmark.COUNT) {
            values[index * 3] = 0.25f
            values[index * 3 + 1] = 0.75f
            values[index * 3 + 2] = 0.9f
        }
        val result = MoveNetPoseOutputParser.parse(
            values,
            MoveNetCropRegion(xMin = 0.1f, yMin = 0.2f, width = 0.4f, height = 0.6f)
        )
        assertNotNull(result)
        val wrist = result!!.keypoints.getValue(PoseLandmark.LEFT_WRIST)
        assertEquals(0.4f, wrist.position.x, 0.0001f)
        assertEquals(0.35f, wrist.position.y, 0.0001f)
        assertEquals(0.9f, wrist.confidence, 0.0001f)
    }

    @Test
    fun initialCropRegionPadsWideFrameVertically() {
        val crop = MoveNetPoseOutputParser.initialCropRegion(imageWidth = 640, imageHeight = 320)
        assertEquals(0f, crop.xMin, 0.0001f)
        assertEquals(-0.5f, crop.yMin, 0.0001f)
        assertEquals(1f, crop.width, 0.0001f)
        assertEquals(2f, crop.height, 0.0001f)
    }

    @Test
    fun nextCropRegionExpandsReliableJointBoundsByOneAndAHalf() {
        val values = FloatArray(PoseLandmark.COUNT * 3)
        for (index in 0 until PoseLandmark.COUNT) {
            values[index * 3] = 0.5f
            values[index * 3 + 1] = 0.5f
            values[index * 3 + 2] = 0.8f
        }
        fun set(index: Int, y: Float, x: Float) {
            values[index * 3] = y
            values[index * 3 + 1] = x
        }
        set(PoseLandmark.LEFT_SHOULDER, 0.35f, 0.40f)
        set(PoseLandmark.RIGHT_SHOULDER, 0.35f, 0.60f)
        set(PoseLandmark.LEFT_HIP, 0.55f, 0.43f)
        set(PoseLandmark.RIGHT_HIP, 0.55f, 0.57f)
        set(PoseLandmark.LEFT_WRIST, 0.30f, 0.30f)
        set(PoseLandmark.RIGHT_WRIST, 0.32f, 0.70f)
        set(PoseLandmark.LEFT_ANKLE, 0.75f, 0.42f)
        set(PoseLandmark.RIGHT_ANKLE, 0.73f, 0.60f)
        val pose = MoveNetPoseOutputParser.parse(
            values,
            MoveNetCropRegion(0f, 0f, 1f, 1f)
        )!!
        val crop = MoveNetPoseOutputParser.nextCropRegion(pose, 1000, 1000)
        assertEquals(0.675f, crop.width, 0.0001f)
        assertEquals(0.675f, crop.height, 0.0001f)
        assertTrue(crop.xMin <= 0.30f)
        assertTrue(crop.yMin <= 0.30f)
        assertTrue(crop.xMin + crop.width >= 0.70f)
        assertTrue(crop.yMin + crop.height >= 0.75f)
    }
}
