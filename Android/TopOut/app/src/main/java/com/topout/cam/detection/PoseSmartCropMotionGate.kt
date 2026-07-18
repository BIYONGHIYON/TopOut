package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Unlocks MoveNet smart cropping only after sustained human-scale joint motion.
 *
 * A window is accepted when at least [minimumMovedLandmarks] keypoints that are present in all
 * [requiredFrames] have moved by [minimumMovementRatio] of the detected person's bounding-box
 * diagonal between the first and last frame.
 */
internal class PoseSmartCropMotionGate(
    private val requiredFrames: Int = 3,
    private val minimumMovedLandmarks: Int = 3,
    private val minimumMovementRatio: Float = 0.05f
) {
    internal data class LandmarkMovement(
        val type: Int,
        val distance: Float,
        val ratioToPersonSize: Float
    )

    internal data class Observation(
        val frameCount: Int,
        val requiredFrameCount: Int,
        val personSize: Float,
        val movementThreshold: Float,
        val commonLandmarkCount: Int,
        val movedLandmarkCount: Int,
        val largestMovements: List<LandmarkMovement>,
        val shouldUnlock: Boolean
    )

    private data class Snapshot(
        val landmarks: Map<Int, Offset>,
        val personSize: Float
    )

    private val snapshots = ArrayDeque<Snapshot>(requiredFrames)

    init {
        require(requiredFrames >= 2) { "스마트 크롭 확인 프레임은 2개 이상이어야 합니다." }
        require(minimumMovedLandmarks >= 1) { "이동 관절 수는 1개 이상이어야 합니다." }
        require(minimumMovementRatio > 0f) { "이동 비율은 0보다 커야 합니다." }
    }

    fun observe(landmarks: Map<Int, Offset>): Observation {
        val personSize = boundingBoxDiagonal(landmarks.values)
        if (!personSize.isFinite() || personSize <= MINIMUM_PERSON_SIZE) {
            reset()
            return Observation(
                frameCount = 0,
                requiredFrameCount = requiredFrames,
                personSize = personSize.takeIf { it.isFinite() } ?: 0f,
                movementThreshold = 0f,
                commonLandmarkCount = 0,
                movedLandmarkCount = 0,
                largestMovements = emptyList(),
                shouldUnlock = false
            )
        }

        snapshots.addLast(
            Snapshot(
                landmarks = LinkedHashMap(landmarks),
                personSize = personSize
            )
        )
        while (snapshots.size > requiredFrames) snapshots.removeFirst()

        val window = snapshots.toList()
        val movementThreshold = window.maxOf { it.personSize } * minimumMovementRatio
        if (window.size < requiredFrames) {
            return Observation(
                frameCount = window.size,
                requiredFrameCount = requiredFrames,
                personSize = personSize,
                movementThreshold = movementThreshold,
                commonLandmarkCount = 0,
                movedLandmarkCount = 0,
                largestMovements = emptyList(),
                shouldUnlock = false
            )
        }

        val first = window.first()
        val last = window.last()
        val referencePersonSize = window.maxOf { it.personSize }
        val commonLandmarks = first.landmarks.keys.filter { type ->
            window.all { snapshot -> type in snapshot.landmarks }
        }
        val movements = commonLandmarks.map { type ->
            val start = first.landmarks.getValue(type)
            val end = last.landmarks.getValue(type)
            val distance = distance(start, end)
            LandmarkMovement(
                type = type,
                distance = distance,
                ratioToPersonSize = if (referencePersonSize > 0f) distance / referencePersonSize else 0f
            )
        }.sortedByDescending { it.distance }
        val movedCount = movements.count { it.distance >= movementThreshold }

        return Observation(
            frameCount = window.size,
            requiredFrameCount = requiredFrames,
            personSize = referencePersonSize,
            movementThreshold = movementThreshold,
            commonLandmarkCount = commonLandmarks.size,
            movedLandmarkCount = movedCount,
            largestMovements = movements.take(DIAGNOSTIC_MOVEMENT_COUNT),
            shouldUnlock = movedCount >= minimumMovedLandmarks
        )
    }

    fun reset() {
        snapshots.clear()
    }

    private fun boundingBoxDiagonal(points: Collection<Offset>): Float {
        if (points.isEmpty()) return 0f
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite()) continue
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return 0f
        return distance(Offset(minX, minY), Offset(maxX, maxY))
    }

    private fun distance(first: Offset, second: Offset): Float {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val MINIMUM_PERSON_SIZE = 0.0001f
        const val DIAGNOSTIC_MOVEMENT_COUNT = 3
    }
}
