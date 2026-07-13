package com.topout.cam.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingAspectRatioTest {
    @Test
    fun portraitOutputSizesAreDistinct() {
        val sizes = RecordingAspectRatio.entries.map {
            it.outputSize(RecordingResolution.FHD, isLandscape = false)
        }

        assertEquals(4, sizes.distinct().size)
        assertEquals(IntSize(1080, 1920), sizes[0])
        assertEquals(IntSize(1080, 1350), sizes[1])
        assertEquals(IntSize(1080, 1440), sizes[2])
        assertEquals(IntSize(1080, 1080), sizes[3])
    }

    @Test
    fun cameraViewPortRatiosAreExactAndDistinct() {
        val portrait = RecordingAspectRatio.entries.map { it.viewPortRatio(false) }
        val landscape = RecordingAspectRatio.entries.map { it.viewPortRatio(true) }

        assertEquals(listOf(IntSize(9, 16), IntSize(4, 5), IntSize(3, 4), IntSize(1, 1)), portrait)
        assertEquals(listOf(IntSize(16, 9), IntSize(5, 4), IntSize(4, 3), IntSize(1, 1)), landscape)
        assertEquals(4, portrait.distinct().size)
        assertEquals(4, landscape.distinct().size)
    }

    @Test
    fun landscapeOutputSizesUseTheSameSelectedRatio() {
        val sizes = RecordingAspectRatio.entries.map {
            it.outputSize(RecordingResolution.FHD, isLandscape = true)
        }

        assertEquals(
            listOf(
                IntSize(1920, 1080),
                IntSize(1350, 1080),
                IntSize(1440, 1080),
                IntSize(1080, 1080)
            ),
            sizes
        )
    }
}
