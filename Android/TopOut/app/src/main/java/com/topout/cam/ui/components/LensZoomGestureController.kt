package com.topout.cam.ui.components

import kotlin.math.abs
import kotlin.math.min

/**
 * Splits one continuous camera zoom range into physical-lens sections.
 *
 * A pinch may move freely inside one lens section, but it stops immediately before the next
 * physical lens anchor. The user must release and start a new pinch to cross that boundary.
 * This avoids an optical-lens hand-off in the middle of a gesture. CameraEngine maps each section
 * to the matching real rear camera and rebinds only when a new gesture enters the next anchor.
 */
internal class LensZoomGestureController(
    minimumZoom: Float,
    maximumZoom: Float,
    lensAnchors: List<Float>,
    private val transitionGap: Float = DEFAULT_TRANSITION_GAP
) {
    private enum class Direction { IN, OUT }

    private data class Segment(
        val anchor: Float,
        val upperStop: Float
    )

    private val minimumZoom = minimumZoom
    private val maximumZoom = maximumZoom.coerceAtLeast(minimumZoom)
    private val anchors: List<Float> = buildList {
        add(this@LensZoomGestureController.minimumZoom)
        lensAnchors
            .asSequence()
            .filter { it.isFinite() }
            .map { it.coerceIn(this@LensZoomGestureController.minimumZoom, this@LensZoomGestureController.maximumZoom) }
            .forEach(::add)
    }
        .sorted()
        .fold(mutableListOf()) { result, value ->
            if (result.isEmpty() || abs(result.last() - value) > ANCHOR_MERGE_EPSILON) {
                result += value
            }
            result
        }

    private val segments: List<Segment> = anchors.mapIndexed { index, anchor ->
        val upperStop = if (index == anchors.lastIndex) {
            maximumZoom
        } else {
            val nextAnchor = anchors[index + 1]
            val safeGap = min(transitionGap, (nextAnchor - anchor) * MAX_GAP_FRACTION)
            (nextAnchor - safeGap).coerceAtLeast(anchor)
        }
        Segment(anchor = anchor, upperStop = upperStop)
    }

    private var startZoom = minimumZoom
    private var cumulativeScale = 1f
    private var direction: Direction? = null
    private var activeSegmentIndex = 0
    private var crossingScale = 1f
    private var crossingBaseZoom = minimumZoom
    private var crossedBoundary = false

    fun begin(zoom: Float) {
        startZoom = normalizeToLegalZoom(zoom.coerceIn(minimumZoom, maximumZoom))
        cumulativeScale = 1f
        direction = null
        activeSegmentIndex = segmentIndexFor(startZoom)
        crossingScale = 1f
        crossingBaseZoom = startZoom
        crossedBoundary = false
    }

    fun update(eventScaleFactor: Float): Float {
        if (!eventScaleFactor.isFinite() || eventScaleFactor <= 0f) return currentZoom()
        cumulativeScale *= eventScaleFactor.coerceIn(MIN_EVENT_SCALE, MAX_EVENT_SCALE)

        if (direction == null) {
            direction = when {
                cumulativeScale > 1f + DIRECTION_DEAD_ZONE -> Direction.IN
                cumulativeScale < 1f - DIRECTION_DEAD_ZONE -> Direction.OUT
                else -> null
            }
            if (direction != null) configureGesture(direction!!)
        }

        val currentDirection = direction ?: return startZoom
        val segment = segments[activeSegmentIndex]
        val rawZoom = if (crossedBoundary) {
            crossingBaseZoom * (cumulativeScale / crossingScale)
        } else {
            startZoom * cumulativeScale
        }

        return when (currentDirection) {
            Direction.IN -> rawZoom.coerceIn(segment.anchor, segment.upperStop)
            Direction.OUT -> rawZoom.coerceIn(segment.anchor, segment.upperStop)
        }
    }

    fun end() {
        direction = null
        cumulativeScale = 1f
        crossedBoundary = false
    }

    private fun configureGesture(newDirection: Direction) {
        when (newDirection) {
            Direction.IN -> {
                val boundaryIndex = segments.indexOfFirst { approximately(startZoom, it.upperStop) }
                if (boundaryIndex in 0 until segments.lastIndex) {
                    activeSegmentIndex = boundaryIndex + 1
                    crossingScale = cumulativeScale
                    crossingBaseZoom = segments[activeSegmentIndex].anchor
                    crossedBoundary = true
                } else {
                    activeSegmentIndex = segmentIndexFor(startZoom)
                }
            }

            Direction.OUT -> {
                val anchorIndex = anchors.indexOfFirst { approximately(startZoom, it) }
                if (anchorIndex > 0) {
                    activeSegmentIndex = anchorIndex - 1
                    crossingScale = cumulativeScale
                    crossingBaseZoom = segments[activeSegmentIndex].upperStop
                    crossedBoundary = true
                } else {
                    activeSegmentIndex = segmentIndexFor(startZoom)
                }
            }
        }
    }

    private fun currentZoom(): Float {
        val segment = segments[activeSegmentIndex]
        val rawZoom = if (crossedBoundary) {
            crossingBaseZoom * (cumulativeScale / crossingScale)
        } else {
            startZoom * cumulativeScale
        }
        return rawZoom.coerceIn(segment.anchor, segment.upperStop)
    }

    private fun segmentIndexFor(zoom: Float): Int {
        val anchorIndex = anchors.indexOfLast { it <= zoom + BOUNDARY_EPSILON }
        return anchorIndex.coerceIn(0, segments.lastIndex)
    }

    /** Values inside an intentionally blocked transition gap are snapped to its nearest edge. */
    private fun normalizeToLegalZoom(zoom: Float): Float {
        for (index in 0 until segments.lastIndex) {
            val lowerEdge = segments[index].upperStop
            val upperEdge = segments[index + 1].anchor
            if (zoom > lowerEdge && zoom < upperEdge) {
                return if (zoom - lowerEdge <= upperEdge - zoom) lowerEdge else upperEdge
            }
        }
        return zoom
    }

    private fun approximately(first: Float, second: Float): Boolean =
        abs(first - second) <= BOUNDARY_EPSILON

    private companion object {
        const val DEFAULT_TRANSITION_GAP = 0.1f
        const val MAX_GAP_FRACTION = 0.25f
        const val ANCHOR_MERGE_EPSILON = 0.04f
        const val BOUNDARY_EPSILON = 0.015f
        const val DIRECTION_DEAD_ZONE = 0.003f
        const val MIN_EVENT_SCALE = 0.5f
        const val MAX_EVENT_SCALE = 2f
    }
}
