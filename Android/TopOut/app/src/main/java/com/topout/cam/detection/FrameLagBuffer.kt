package com.topout.cam.detection

import java.util.ArrayDeque

/**
 * Keeps only the most recent [lag] samples and returns the sample exactly [lag] observations behind.
 *
 * The current value is stored after the reference is selected, so a lag of three compares
 * observation N with observation N - 3. Until three prior observations exist, null is returned.
 */
internal class FrameLagBuffer<T : Any>(private val lag: Int) {
    private val values = ArrayDeque<T>()

    init {
        require(lag > 0) { "lag must be positive" }
    }

    fun referenceFor(current: T): T? {
        val reference = if (values.size >= lag) values.first else null
        values.addLast(current)
        while (values.size > lag) values.removeFirst()
        return reference
    }

    fun clear() {
        values.clear()
    }
}
