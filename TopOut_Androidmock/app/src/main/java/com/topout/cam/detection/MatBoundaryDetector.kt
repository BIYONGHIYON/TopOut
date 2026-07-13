package com.topout.cam.detection

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.topout.cam.model.TopOutConfig
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Direct Kotlin port of MatDetector.swift. */
object MatBoundaryDetector {
    private enum class EdgeMode { CHROMA_ONLY, BRIGHTNESS_FALLBACK }

    private data class Rgb(val r: Int, val g: Int, val b: Int)
    private data class Bin(val index: Int, val x0: Int, val x1: Int, val centerXNorm: Float)
    private data class EdgeSample(val bin: Bin, val y: Int, val diff: Float, val lower: Rgb)
    private data class BaseCandidate(
        val y: Int,
        val allSamples: List<EdgeSample>,
        val run: List<EdgeSample>,
        val medianDiff: Float,
        val lowerUniformDeviation: Float,
        val score: Float,
        val edgeMode: EdgeMode
    )
    private data class RefinedPoint(val x: Float, val y: Float, val diff: Float)

    fun detect(bitmap: Bitmap, horizontalBinCount: Int = TopOutConfig.MatBoundaryBins): List<Offset> {
        val image = SmallImage.from(bitmap, TopOutConfig.MatDetectWidth) ?: return defaultLine()
        val topY = max(4, (image.height * TopOutConfig.MatSearchTopY).toInt())
        val bottomY = min(image.height - 5, (image.height * TopOutConfig.MatSearchBottomY).toInt())
        if (topY >= bottomY) return defaultLine()

        val bins = makeBins(image.width, horizontalBinCount)
        if (bins.size < 4) return defaultLine()

        val base = findBestHorizontalBoundary(image, bins, topY, bottomY, EdgeMode.CHROMA_ONLY)
            ?: findBestHorizontalBoundary(image, bins, topY, bottomY, EdgeMode.BRIGHTNESS_FALLBACK)
            ?: return defaultLine()

        return simplify(refineBoundaryPoints(image, bins, base, topY, bottomY))
    }

    private fun findBestHorizontalBoundary(
        image: SmallImage,
        bins: List<Bin>,
        topY: Int,
        bottomY: Int,
        mode: EdgeMode
    ): BaseCandidate? {
        val candidates = mutableListOf<BaseCandidate>()
        val upperBand = sampleBand(image.height)
        val lowerBand = sampleLowerBand(image.height)
        val gap = max(2, upperBand / 2)
        val minY = upperBand + gap + 1
        val maxY = image.height - lowerBand - gap - 2

        var y = topY
        while (y <= bottomY) {
            if (y in minY..maxY) {
                evaluateHorizontalLine(y, image, bins, upperBand, lowerBand, gap, mode)?.let(candidates::add)
            }
            y += 2
        }
        return chooseBoundaryCandidate(candidates, max(3, upperBand + gap))
    }

    private fun chooseBoundaryCandidate(candidates: List<BaseCandidate>, clusterGap: Int): BaseCandidate? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedWith(compareBy<BaseCandidate> { it.y }.thenByDescending { it.score })
        val clusters = mutableListOf<MutableList<BaseCandidate>>()
        var current = mutableListOf<BaseCandidate>()
        var previousY: Int? = null
        for (candidate in sorted) {
            if (previousY != null && candidate.y - previousY <= clusterGap) {
                current += candidate
            } else {
                if (current.isNotEmpty()) clusters += current
                current = mutableListOf(candidate)
            }
            previousY = candidate.y
        }
        if (current.isNotEmpty()) clusters += current

        val representatives = clusters.mapNotNull { it.maxByOrNull(BaseCandidate::score) }
        val best = representatives.maxByOrNull(BaseCandidate::score) ?: return null
        val high = representatives.filter { best.score - it.score <= TopOutConfig.MatUpperTieScoreTolerance }
        return if (high.size >= 2) high.minByOrNull(BaseCandidate::y) ?: best else best
    }

    private fun evaluateHorizontalLine(
        y: Int,
        image: SmallImage,
        bins: List<Bin>,
        upperBand: Int,
        lowerBand: Int,
        gap: Int,
        mode: EdgeMode
    ): BaseCandidate? {
        val samples = bins.map { bin ->
            val upper = averageRgb(image, bin.x0, bin.x1, y - gap - upperBand, y - gap)
            val lower = averageRgb(image, bin.x0, bin.x1, y + gap, y + gap + lowerBand)
            EdgeSample(bin, y, edgeColorDistance(upper, lower, mode), lower)
        }
        val strong = samples.filter { it.diff >= TopOutConfig.MatMinEdgeScore }
        val requiredCoverage = max(2, ceil(bins.size * TopOutConfig.MatBoundaryMinCoverageRatio).toInt())
        val selected = bestLowerUniformRun(strong, requiredCoverage) ?: return null
        if (selected.second > TopOutConfig.MatLowerUniformityTolerance) return null

        val runMedian = median(selected.first.map(EdgeSample::diff))
        val coverage = selected.first.size.toFloat() / max(bins.size, 1)
        val score = -selected.second * 4f + coverage * 3f
        return BaseCandidate(y, samples, selected.first, runMedian, selected.second, score, mode)
    }

    private fun refineBoundaryPoints(
        image: SmallImage,
        bins: List<Bin>,
        base: BaseCandidate,
        topY: Int,
        bottomY: Int
    ): List<RefinedPoint> {
        val upperBand = sampleBand(image.height)
        val lowerBand = sampleLowerBand(image.height)
        val gap = max(2, upperBand / 2)
        val window = max(4, (image.height * TopOutConfig.MatLocalRefineWindowRatio).toInt())
        val minY = max(max(topY, upperBand + gap + 1), base.y - window)
        val maxY = min(min(bottomY, image.height - lowerBand - gap - 2), base.y + window)
        val supportIndices = base.run.map { it.bin.index }.toSet()
        val support = bins.filter { it.index in supportIndices }.ifEmpty { bins }

        val points = support.map { bin ->
            var bestY = base.y
            var bestDiff = 0f
            var bestScore = -Float.MAX_VALUE
            for (y in minY..maxY) {
                val diff = edgeDiff(image, bin, y, upperBand, lowerBand, gap, base.edgeMode)
                if (diff >= TopOutConfig.MatMinEdgeScore) {
                    val consistencyPenalty = abs(diff - base.medianDiff) * 0.35f
                    val score = diff - consistencyPenalty
                    if (score > bestScore) {
                        bestScore = score
                        bestY = y
                        bestDiff = diff
                    }
                }
            }
            if (bestScore == -Float.MAX_VALUE) {
                bestY = base.y
                bestDiff = edgeDiff(image, bin, base.y, upperBand, lowerBand, gap, base.edgeMode)
            }
            RefinedPoint(bin.centerXNorm, bestY.toFloat() / max(image.height - 1, 1), bestDiff)
        }
        return smooth(points)
    }

    private fun edgeDiff(
        image: SmallImage,
        bin: Bin,
        y: Int,
        upperBand: Int,
        lowerBand: Int,
        gap: Int,
        mode: EdgeMode
    ): Float {
        val upper = averageRgb(image, bin.x0, bin.x1, y - gap - upperBand, y - gap)
        val lower = averageRgb(image, bin.x0, bin.x1, y + gap, y + gap + lowerBand)
        return edgeColorDistance(upper, lower, mode)
    }

    private fun simplify(points: List<RefinedPoint>): List<Offset> {
        if (points.size < 2) return defaultLine()
        val n = points.size.toFloat()
        val sx = points.sumOf { it.x.toDouble() }.toFloat()
        val sy = points.sumOf { it.y.toDouble() }.toFloat()
        val sxx = points.sumOf { (it.x * it.x).toDouble() }.toFloat()
        val sxy = points.sumOf { (it.x * it.y).toDouble() }.toFloat()
        val denominator = max(0.0001f, n * sxx - sx * sx)
        val a = (n * sxy - sx * sy) / denominator
        val b = (sy - a * sx) / n
        val leftX = 0.06f
        val rightX = 0.94f
        return listOf(
            Offset(leftX, (a * leftX + b).coerceIn(TopOutConfig.MatSearchTopY, TopOutConfig.MatSearchBottomY)),
            Offset(rightX, (a * rightX + b).coerceIn(TopOutConfig.MatSearchTopY, TopOutConfig.MatSearchBottomY))
        )
    }

    private fun sampleBand(height: Int) = max(3, (height * TopOutConfig.MatSampleStripRatio).toInt())
    private fun sampleLowerBand(height: Int): Int {
        val upper = sampleBand(height)
        return max(upper, (upper * TopOutConfig.MatLowerSampleMultiplier).toInt())
    }

    private fun makeBins(width: Int, requestedCount: Int): List<Bin> {
        val count = max(4, requestedCount)
        val left = 0.06f
        val right = 0.94f
        val usable = right - left
        return (0 until count).map { index ->
            val l = left + usable * index / count
            val r = left + usable * (index + 1) / count
            Bin(
                index,
                max(0, (width * l).toInt()),
                min(width - 1, (width * r).toInt()),
                (l + r) * 0.5f
            )
        }
    }

    private fun bestLowerUniformRun(samples: List<EdgeSample>, requiredCoverage: Int): Pair<List<EdgeSample>, Float>? {
        if (samples.isEmpty()) return null
        val runs = mutableListOf<MutableList<EdgeSample>>()
        var current = mutableListOf<EdgeSample>()
        var previous: Int? = null
        for (sample in samples.sortedBy { it.bin.index }) {
            if (previous != null && sample.bin.index == previous + 1) current += sample
            else {
                if (current.isNotEmpty()) runs += current
                current = mutableListOf(sample)
            }
            previous = sample.bin.index
        }
        if (current.isNotEmpty()) runs += current

        var best: List<EdgeSample>? = null
        var bestDeviation = Float.MAX_VALUE
        for (run in runs.filter { it.size >= requiredCoverage }) {
            val deviation = lowerColorUniformity(run.map(EdgeSample::lower))
            if (deviation < bestDeviation - 0.001f ||
                (abs(deviation - bestDeviation) <= 0.001f && run.size > (best?.size ?: 0))
            ) {
                best = run
                bestDeviation = deviation
            }
        }
        return best?.let { it to bestDeviation }
    }

    private fun lowerColorUniformity(colors: List<Rgb>): Float {
        if (colors.isEmpty()) return Float.MAX_VALUE
        val center = Rgb(
            median(colors.map { it.r.toFloat() }).toInt(),
            median(colors.map { it.g.toFloat() }).toInt(),
            median(colors.map { it.b.toFloat() }).toInt()
        )
        return colors.map { chromaDistance(it, center) }.average().toFloat()
    }

    private fun smooth(points: List<RefinedPoint>): List<RefinedPoint> {
        if (points.size < 3) return points
        return points.mapIndexed { index, point ->
            if (index == 0 || index == points.lastIndex) point
            else point.copy(y = median(listOf(points[index - 1].y, point.y, points[index + 1].y)))
        }
    }

    private fun averageRgb(image: SmallImage, x0In: Int, x1In: Int, y0In: Int, y1In: Int): Rgb {
        val x0 = x0In.coerceIn(0, image.width - 1)
        val x1 = x1In.coerceIn(x0, image.width - 1)
        val y0 = y0In.coerceIn(0, image.height - 1)
        val y1 = y1In.coerceIn(y0, image.height - 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = image.rgb(x, y)
                r += c.r
                g += c.g
                b += c.b
                count++
                x += 2
            }
            y += 2
        }
        if (count == 0) return Rgb(0, 0, 0)
        return Rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun edgeColorDistance(a: Rgb, b: Rgb, mode: EdgeMode): Float = when (mode) {
        EdgeMode.CHROMA_ONLY -> chromaDistance(a, b)
        EdgeMode.BRIGHTNESS_FALLBACK -> chromaDistance(a, b) + lumaDistance(a, b) * 0.35f
    }

    private fun chromaDistance(a: Rgb, b: Rgb): Float {
        val la = luma(a)
        val lb = luma(b)
        val ar = a.r - la
        val ag = a.g - la
        val ab = a.b - la
        val br = b.r - lb
        val bg = b.g - lb
        val bb = b.b - lb
        return (abs(ar - br) + abs(ag - bg) + abs(ab - bb)) / 3f
    }

    private fun lumaDistance(a: Rgb, b: Rgb) = abs(luma(a) - luma(b))
    private fun luma(c: Rgb) = c.r * 0.2126f + c.g * 0.7152f + c.b * 0.0722f

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) * 0.5f else sorted[mid]
    }

    private fun defaultLine() = listOf(
        Offset(0.06f, TopOutConfig.MatDefaultY),
        Offset(0.94f, TopOutConfig.MatDefaultY)
    )

    private class SmallImage private constructor(
        val width: Int,
        val height: Int,
        private val pixels: IntArray
    ) {
        fun rgb(x: Int, y: Int): Rgb {
            val color = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
            return Rgb(
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color)
            )
        }

        companion object {
            fun from(source: Bitmap, maxWidth: Int): SmallImage? = runCatching {
                val scale = min(1f, maxWidth.toFloat() / source.width.coerceAtLeast(1))
                val width = max(1, (source.width * scale).toInt())
                val height = max(1, (source.height * scale).toInt())
                val scaled = if (width == source.width && height == source.height) source
                else Bitmap.createScaledBitmap(source, width, height, false)
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (scaled !== source) scaled.recycle()
                SmallImage(width, height, pixels)
            }.getOrNull()
        }
    }
}
