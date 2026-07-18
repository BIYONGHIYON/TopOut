package com.topout.cam.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameLagBufferTest {
    @Test
    fun comparesCurrentSampleWithExactlyThreeSamplesEarlier() {
        val buffer = FrameLagBuffer<Int>(lag = 3)

        assertNull(buffer.referenceFor(10))
        assertNull(buffer.referenceFor(20))
        assertNull(buffer.referenceFor(30))
        assertEquals(10, buffer.referenceFor(40))
        assertEquals(20, buffer.referenceFor(50))
    }

    @Test
    fun clearRequiresThreeFreshSamplesBeforeComparisonResumes() {
        val buffer = FrameLagBuffer<Int>(lag = 3)
        listOf(1, 2, 3, 4).forEach(buffer::referenceFor)

        buffer.clear()

        assertNull(buffer.referenceFor(5))
        assertNull(buffer.referenceFor(6))
        assertNull(buffer.referenceFor(7))
        assertEquals(5, buffer.referenceFor(8))
    }
}
