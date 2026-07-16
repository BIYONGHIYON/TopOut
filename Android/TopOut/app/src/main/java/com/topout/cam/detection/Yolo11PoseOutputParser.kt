package com.topout.cam.detection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class YoloPoseKeypoint(
    val position: Offset,
    val confidence: Float
)

internal data class YoloPoseResult(
    val boundingBox: Rect,
    val confidence: Float,
    val keypoints: Map<Int, YoloPoseKeypoint>
)

internal data class YoloLetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inputSize: Int,
    val scale: Float,
    val padLeft: Float,
    val padTop: Float
) {
    fun modelPointToNormalized(x: Float, y: Float): Offset {
        val sourceX = (x - padLeft) / max(scale, 0.000001f)
        val sourceY = (y - padTop) / max(scale, 0.000001f)
        return Offset(
            (sourceX / max(1, sourceWidth)).coerceIn(0f, 1f),
            (sourceY / max(1, sourceHeight)).coerceIn(0f, 1f)
        )
    }

    fun modelBoxToNormalized(centerX: Float, centerY: Float, width: Float, height: Float): Rect {
        val topLeft = modelPointToNormalized(centerX - width * 0.5f, centerY - height * 0.5f)
        val bottomRight = modelPointToNormalized(centerX + width * 0.5f, centerY + height * 0.5f)
        return Rect(
            left = min(topLeft.x, bottomRight.x),
            top = min(topLeft.y, bottomRight.y),
            right = max(topLeft.x, bottomRight.x),
            bottom = max(topLeft.y, bottomRight.y)
        )
    }
}

/** Parses the standard YOLO11n-pose ONNX output: 4 box + 1 person score + 17x3 keypoints. */
internal object Yolo11PoseOutputParser {
    private const val CHANNEL_COUNT = 5 + YoloPoseLandmark.COUNT * 3

    fun parse(
        data: FloatArray,
        shape: LongArray,
        transform: YoloLetterboxTransform,
        personConfidenceThreshold: Float,
        nmsIouThreshold: Float
    ): List<YoloPoseResult> {
        val layout = resolveLayout(shape, data.size) ?: return emptyList()
        val candidates = ArrayList<YoloPoseResult>()
        for (candidateIndex in 0 until layout.candidateCount) {
            val confidence = layout.value(data, candidateIndex, 4)
            if (!confidence.isFinite() || confidence < personConfidenceThreshold) continue

            val centerX = layout.value(data, candidateIndex, 0)
            val centerY = layout.value(data, candidateIndex, 1)
            val width = layout.value(data, candidateIndex, 2)
            val height = layout.value(data, candidateIndex, 3)
            if (!centerX.isFinite() || !centerY.isFinite() ||
                !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f
            ) continue

            val box = transform.modelBoxToNormalized(centerX, centerY, width, height)
            if (box.width <= 0f || box.height <= 0f) continue

            val keypoints = LinkedHashMap<Int, YoloPoseKeypoint>(YoloPoseLandmark.COUNT)
            for (index in 0 until YoloPoseLandmark.COUNT) {
                val offset = 5 + index * 3
                val x = layout.value(data, candidateIndex, offset)
                val y = layout.value(data, candidateIndex, offset + 1)
                val keypointConfidence = layout.value(data, candidateIndex, offset + 2)
                if (!x.isFinite() || !y.isFinite() || !keypointConfidence.isFinite()) continue
                keypoints[index] = YoloPoseKeypoint(
                    position = transform.modelPointToNormalized(x, y),
                    confidence = keypointConfidence.coerceIn(0f, 1f)
                )
            }
            candidates += YoloPoseResult(box, confidence.coerceIn(0f, 1f), keypoints)
        }
        return nonMaximumSuppression(candidates, nmsIouThreshold)
    }

    fun selectPrimary(
        candidates: List<YoloPoseResult>,
        previousBox: Rect?,
        useTrackingAssociation: Boolean
    ): YoloPoseResult? {
        if (candidates.isEmpty()) return null
        if (!useTrackingAssociation || previousBox == null) {
            return candidates.maxByOrNull { it.confidence }
        }
        return candidates.maxByOrNull { candidate ->
            val overlap = intersectionOverUnion(candidate.boundingBox, previousBox)
            val centerDistance = centerDistance(candidate.boundingBox, previousBox)
            val proximity = (1f - centerDistance).coerceIn(0f, 1f)
            candidate.confidence + overlap * 0.35f + proximity * 0.15f
        }
    }

    private fun nonMaximumSuppression(
        candidates: List<YoloPoseResult>,
        iouThreshold: Float
    ): List<YoloPoseResult> {
        val remaining = candidates.sortedByDescending { it.confidence }.toMutableList()
        val selected = ArrayList<YoloPoseResult>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            selected += best
            remaining.removeAll { intersectionOverUnion(best.boundingBox, it.boundingBox) > iouThreshold }
        }
        return selected
    }

    internal fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val intersectionWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val intersectionHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val ax = (a.left + a.right) * 0.5f
        val ay = (a.top + a.bottom) * 0.5f
        val bx = (b.left + b.right) * 0.5f
        val by = (b.top + b.bottom) * 0.5f
        return (hypot((ax - bx).toDouble(), (ay - by).toDouble()) / hypot(1.0, 1.0)).toFloat()
    }

    private fun resolveLayout(shape: LongArray, dataSize: Int): OutputLayout? {
        if (shape.size != 3 || shape[0] != 1L) return null
        val second = shape[1].toInt()
        val third = shape[2].toInt()
        return when {
            second == CHANNEL_COUNT && CHANNEL_COUNT * third == dataSize ->
                OutputLayout(candidateCount = third, channelsFirst = true)
            third == CHANNEL_COUNT && CHANNEL_COUNT * second == dataSize ->
                OutputLayout(candidateCount = second, channelsFirst = false)
            else -> null
        }
    }

    private data class OutputLayout(
        val candidateCount: Int,
        val channelsFirst: Boolean
    ) {
        fun value(data: FloatArray, candidate: Int, channel: Int): Float = if (channelsFirst) {
            data[channel * candidateCount + candidate]
        } else {
            data[candidate * CHANNEL_COUNT + channel]
        }
    }
}
