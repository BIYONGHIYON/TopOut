package com.topout.cam.ui.components

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Splits one continuous camera zoom range into physical-lens sections.
 *
 * A pinch moves freely inside one lens section. Zoom-in can continue against a frozen frame until
 * release; zoom-out holds its boundary while CameraEngine immediately rebinds the previous lens.
 */
internal class LensZoomGestureController(
    minimumZoom: Float,
    maximumZoom: Float,
    lensAnchors: List<Float>,
    private val transitionGap: Float = DEFAULT_TRANSITION_GAP,
    zoomSensitivity: Float = DEFAULT_ZOOM_SENSITIVITY
) {
    private enum class Direction { IN, OUT }

    private data class Segment(
        val anchor: Float,
        val upperStop: Float
    )

    private data class PendingTransition(
        var zoom: Float,
        val baseZoom: Float,
        val baseScale: Float,
        val minimumZoom: Float,
        val maximumZoom: Float,
        val canContinueWhileHeld: Boolean
    )

    private val minimumZoom = minimumZoom
    private val maximumZoom = maximumZoom.coerceAtLeast(minimumZoom)
    private val zoomSensitivity = zoomSensitivity
        .takeIf { it.isFinite() }
        ?.coerceIn(MIN_ZOOM_SENSITIVITY, MAX_ZOOM_SENSITIVITY)
        ?: DEFAULT_ZOOM_SENSITIVITY
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
    private var pendingTransition: PendingTransition? = null

    fun begin(zoom: Float) {
        startZoom = normalizeToLegalZoom(zoom.coerceIn(minimumZoom, maximumZoom))
        cumulativeScale = 1f
        direction = null
        activeSegmentIndex = segmentIndexFor(startZoom)
        crossingScale = 1f
        crossingBaseZoom = startZoom
        crossedBoundary = false
        pendingTransition = null
    }

    fun update(eventScaleFactor: Float): Float {
        pendingTransition?.let { return it.zoom }
        if (!accumulateScale(eventScaleFactor)) return currentZoom()

        if (direction == null) {
            direction = when {
                cumulativeScale > 1f + DIRECTION_DEAD_ZONE -> Direction.IN
                cumulativeScale < 1f - DIRECTION_DEAD_ZONE -> Direction.OUT
                else -> null
            }
            if (direction != null) configureGesture(direction!!)
        }

        pendingTransition?.let { return it.zoom }
        if (direction == null) return startZoom
        val segment = segments[activeSegmentIndex]
        val rawZoom = if (crossedBoundary) {
            crossingBaseZoom * (cumulativeScale / crossingScale)
        } else {
            startZoom * cumulativeScale
        }

        return when {
            activeSegmentIndex < segments.lastIndex && rawZoom > segment.upperStop ->
                requestTransition(
                    zoom = segments[activeSegmentIndex + 1].anchor,
                    targetSegmentIndex = activeSegmentIndex + 1,
                    allowReturnToPreviousSegment = true
                )
            activeSegmentIndex > 0 && rawZoom < segment.anchor ->
                requestTransition(
                    zoom = segments[activeSegmentIndex - 1].upperStop,
                    targetSegmentIndex = activeSegmentIndex - 1
                )
            else -> rawZoom.coerceIn(segment.anchor, segment.upperStop)
        }
    }

    /**
     * Continues a zoom-in gesture against the frozen transition frame. The physical camera stays
     * unchanged until CameraPreview reports onScaleEnd, while the target remains inside the next
     * camera's own display-zoom section.
     */
    fun updatePendingTransition(eventScaleFactor: Float): Float {
        val transition = pendingTransition ?: return currentZoom()
        if (!accumulateScale(eventScaleFactor)) return transition.zoom
        val rawZoom = transition.baseZoom * (cumulativeScale / transition.baseScale)
        return rawZoom
            .coerceIn(transition.minimumZoom, transition.maximumZoom)
            .also { transition.zoom = it }
    }

    fun hasContinuablePendingTransition(): Boolean =
        pendingTransition?.canContinueWhileHeld == true

    fun end() {
        direction = null
        cumulativeScale = 1f
        crossedBoundary = false
        pendingTransition = null
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
                    requestTransition(
                        crossingBaseZoom,
                        activeSegmentIndex,
                        allowReturnToPreviousSegment = true
                    )
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
                    requestTransition(crossingBaseZoom, activeSegmentIndex)
                } else {
                    activeSegmentIndex = segmentIndexFor(startZoom)
                }
            }
        }
    }

    private fun currentZoom(): Float {
        pendingTransition?.let { return it.zoom }
        val segment = segments[activeSegmentIndex]
        val rawZoom = if (crossedBoundary) {
            crossingBaseZoom * (cumulativeScale / crossingScale)
        } else {
            startZoom * cumulativeScale
        }
        return rawZoom.coerceIn(segment.anchor, segment.upperStop)
    }

    private fun requestTransition(
        zoom: Float,
        targetSegmentIndex: Int,
        allowReturnToPreviousSegment: Boolean = false
    ): Float {
        val targetSegment = segments[targetSegmentIndex.coerceIn(0, segments.lastIndex)]
        pendingTransition = PendingTransition(
            zoom = zoom,
            baseZoom = zoom,
            baseScale = cumulativeScale,
            minimumZoom = if (allowReturnToPreviousSegment) {
                minimumZoom
            } else {
                targetSegment.anchor
            },
            maximumZoom = targetSegment.upperStop,
            canContinueWhileHeld = allowReturnToPreviousSegment
        )
        return zoom
    }

    private fun accumulateScale(eventScaleFactor: Float): Boolean {
        if (!eventScaleFactor.isFinite() || eventScaleFactor <= 0f) return false
        val boundedScale = eventScaleFactor.coerceIn(MIN_EVENT_SCALE, MAX_EVENT_SCALE)
        cumulativeScale *= boundedScale.toDouble().pow(zoomSensitivity.toDouble()).toFloat()
        return true
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
        const val DEFAULT_ZOOM_SENSITIVITY = 0.95f
        const val MIN_ZOOM_SENSITIVITY = 0.1f
        const val MAX_ZOOM_SENSITIVITY = 1f
        const val MIN_EVENT_SCALE = 0.5f
        const val MAX_EVENT_SCALE = 2f
    }
}
